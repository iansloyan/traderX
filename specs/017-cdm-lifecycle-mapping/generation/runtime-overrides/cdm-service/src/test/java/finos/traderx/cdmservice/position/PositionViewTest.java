package finos.traderx.cdmservice.position;

import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.ACCOUNT;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.CDM_JSON;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.INJECTOR;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.VALIDATOR;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.directTrade;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.fill1;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.fill2;
import static finos.traderx.cdmservice.lifecycle.LifecycleFixtures.newOrder;
import static org.assertj.core.api.Assertions.assertThat;

import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.event.common.TradeState;
import cdm.event.position.PortfolioState;
import cdm.event.position.Position;
import finos.traderx.cdmservice.lifecycle.BookedTrade;
import finos.traderx.cdmservice.lifecycle.LifecycleAdapter;
import finos.traderx.cdmservice.lifecycle.LifecycleFailureRepository;
import finos.traderx.cdmservice.lifecycle.LifecycleFixtures;
import finos.traderx.cdmservice.lifecycle.PendingCancelRepository;
import finos.traderx.cdmservice.lifecycle.PendingTradeRepository;
import finos.traderx.cdmservice.store.CdmDocumentRepository;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import finos.traderx.cdmservice.validation.CdmValidationResult.Status;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Positions derived from the TradeStates the real lifecycle adapter stored (H2, same DDL as
 * runtime). The fixture trades: account 22214 buys 750 + 750 IBM at 179.500 (order fills) and
 * sells 200 IBM at 181.250 (direct trade).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PositionViewTest {

  @Autowired
  private CdmDocumentRepository documents;
  @Autowired
  private PendingTradeRepository pendingTrades;
  @Autowired
  private PendingCancelRepository pendingCancels;
  @Autowired
  private LifecycleFailureRepository failureRows;
  @Autowired
  private PlatformTransactionManager transactionManager;

  private LifecycleAdapter adapter;
  private CdmStore store;
  private PositionView view;

  @BeforeEach
  void setUp() {
    clean();
    store = new CdmStore(documents, CDM_JSON, transactionManager, Clock.systemUTC());
    adapter = LifecycleFixtures.adapter(store, pendingTrades, pendingCancels, failureRows);
    view = new PositionView(store, CDM_JSON, VALIDATOR, INJECTOR);
  }

  @AfterEach
  void clean() {
    documents.deleteAll();
    pendingTrades.deleteAll();
    pendingCancels.deleteAll();
    failureRows.deleteAll();
  }

  private void bookFixtureTrades() {
    adapter.onOrder(newOrder());
    adapter.onBookedTrade(fill1());
    adapter.onBookedTrade(fill2());
    adapter.onBookedTrade(directTrade());
  }

  private static int quantity(Position position) {
    return position.getPriceQuantity().get(0).getQuantity().getValue().getValue().intValueExact();
  }

  /** The account's side of the position's product: receiver (long) or payer (short). */
  private static int sign(Position position) {
    var payerReceiver = position.getProduct().getNonTransferableProduct().getEconomicTerms()
        .getPayout().get(0).getSettlementPayout().getPayerReceiver();
    return payerReceiver.getReceiver() == CounterpartyRoleEnum.PARTY_1 ? 1 : -1;
  }

  @Test
  void positionQuantityMatchesSumOfSignedExecutions() {
    bookFixtureTrades();

    PortfolioState state = view.positions(ACCOUNT);

    int signedExecutions = List.of(fill1(), fill2(), directTrade()).stream()
        .mapToInt(t -> ("Buy".equals(t.side()) ? 1 : -1) * t.quantity())
        .sum();
    assertThat(signedExecutions).isEqualTo(1300);
    assertThat(state.getPositions()).hasSize(1);
    Position ibm = state.getPositions().get(0);
    assertThat(sign(ibm) * quantity(ibm)).isEqualTo(signedExecutions);
  }

  @Test
  void shortPositionIsTheAccountPayingTheShares() {
    adapter.onBookedTrade(directTrade()); // sells 200, nothing held before

    Position ibm = view.positions(ACCOUNT).getPositions().get(0);

    assertThat(quantity(ibm)).isEqualTo(200);
    assertThat(sign(ibm)).isEqualTo(-1);
    assertThat(view.projection(ACCOUNT).get(0).quantity()).isEqualTo(-200);
  }

  @Test
  void portfolioStateValidatesAndHasLineageToItsTradesAndInitialState() {
    bookFixtureTrades();

    PositionView.AccountPositions positions = view.view(ACCOUNT);

    assertThat(positions.validation().status())
        .as("%s", positions.validation().messages()).isEqualTo(Status.VALID);
    assertThat(VALIDATOR.validate(positions.portfolioState()).status()).isEqualTo(Status.VALID);
    var lineage = positions.portfolioState().getLineage();
    assertThat(lineage.getPortfolioStateReference()).singleElement()
        .satisfies(ref -> assertThat(ref.getExternalReference()).isNotNull()
            .isEqualTo(positions.initialState().getMeta().getExternalKey()));
    assertThat(lineage.getEventReference()).allSatisfy(r ->
        assertThat(r.getGlobalReference()).isNotBlank());
    assertThat(lineage.getTradeReference()).allSatisfy(r ->
        assertThat(r.getGlobalReference()).isNotBlank());
    // The three executions: CDM WorkflowSteps and the trades they formed.
    assertThat(lineage.getEventReference()).hasSize(3);
    assertThat(lineage.getTradeReference()).hasSize(3);
    assertThat(positions.trades()).extracting(PositionView.Contribution::cdmRef)
        .containsExactly("cdm:trade:trd-017-0001", "cdm:trade:trd-017-0002",
            "cdm:trade:trd-017-0100");
    assertThat(positions.cdmRef().toString()).isEqualTo("cdm:position:22214");
  }

  @Test
  void accountWithoutTradesIsTheInitialPortfolioState() {
    PositionView.AccountPositions positions = view.view(ACCOUNT);

    // Rune leaves absent lists null.
    assertThat(positions.portfolioState().getPositions()).isNull();
    assertThat(positions.portfolioState().getLineage().getPortfolioStateReference()).isNull();
    assertThat(positions.portfolioState()).isEqualTo(positions.initialState());
    // CDM 7.8.0 cannot validate an initial state: condition Initialisation requires positions
    // to be absent, while NonTransferable ("positions -> product as NonTransferableProduct
    // exists") is false when they are. Reported as is, never hidden; it is the only message.
    assertThat(positions.validation().status()).isEqualTo(Status.INVALID);
    assertThat(positions.validation().messages()).singleElement().asString()
        .contains("PortfolioStateNonTransferable");
    assertThat(view.projection(ACCOUNT)).isEmpty();
    assertThat(view.position(ACCOUNT, "IBM")).isEmpty();
  }

  @Test
  void tradeStatesThatCannotBeCountedAreSkippedNotFatal() throws Exception {
    adapter.onBookedTrade(directTrade()); // the one good trade: sells 200
    TradeState good = CDM_JSON.readValue(
        store.latest(CdmRef.parse("cdm:trade:trd-017-0100"), TradeState.class).orElseThrow()
            .json(), TradeState.class);
    // No Buyer/Seller role for the account: must not silently count as a sale.
    TradeState.TradeStateBuilder noRoles = good.toBuilder();
    noRoles.getTrade().setPartyRole(List.of());
    store.put(CdmRef.parse("cdm:trade:x-no-roles"), noRoles.build(), null,
        new CdmValidationResult(Status.VALID, List.of()));
    // A fractional quantity (intValueExact throws): must not fail the whole account.
    TradeState.TradeStateBuilder fractional = good.toBuilder();
    fractional.getTrade().getTradeLot().get(0).getPriceQuantity().get(0).getQuantity()
        .getValue().setValue(new BigDecimal("1.5"));
    store.put(CdmRef.parse("cdm:trade:x-fractional"), fractional.build(), null,
        new CdmValidationResult(Status.VALID, List.of()));

    PositionView.AccountPositions positions = view.view(ACCOUNT);

    assertThat(positions.positions()).singleElement()
        .satisfies(p -> assertThat(p.quantity()).isEqualTo(-200));
    assertThat(positions.skipped()).extracting(PositionView.Skipped::cdmRef)
        .containsExactlyInAnyOrder("cdm:trade:x-no-roles", "cdm:trade:x-fractional");
    assertThat(positions.skipped()).filteredOn(k -> k.cdmRef().endsWith("no-roles"))
        .singleElement().satisfies(k -> assertThat(k.reason()).contains("neither the Buyer"));
  }

  @Test
  void tradesOfOtherAccountsAreNotCounted() {
    bookFixtureTrades();

    assertThat(view.projection(52355)).isEmpty();
    assertThat(view.positions(52355).getPositions()).isNull();
  }

  /**
   * The 009 position for the same trades, computed exactly as 009 trade-processor's
   * {@code TradeService.processTrade} books it (signed quantity; average cost basis at scale 3,
   * HALF_UP, reset to 0 when flat).
   */
  private static Map<String, Object[]> positionService009(List<BookedTrade> trades) {
    Map<String, Object[]> positions = new LinkedHashMap<>();
    for (BookedTrade trade : trades) {
      Object[] p = positions.computeIfAbsent(trade.security(),
          s -> new Object[] {0, BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP)});
      int oldQuantity = (int) p[0];
      BigDecimal oldAverage = (BigDecimal) p[1];
      BigDecimal price = trade.price().setScale(3, RoundingMode.HALF_UP);
      int signed = ("Buy".equals(trade.side()) ? 1 : -1) * trade.quantity();
      int newQuantity = oldQuantity + signed;
      BigDecimal notional = oldAverage.multiply(BigDecimal.valueOf(oldQuantity))
          .add(price.multiply(BigDecimal.valueOf(signed)));
      p[0] = newQuantity;
      p[1] = newQuantity == 0 ? BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP)
          : notional.divide(BigDecimal.valueOf(newQuantity), 3, RoundingMode.HALF_UP);
    }
    return positions;
  }

  @Test
  void positionProjectionMatchesPositionServiceShape() {
    bookFixtureTrades();

    List<PositionProjection> projection = view.projection(ACCOUNT);

    // 009 position-service Position fields (accountId, security, quantity, averageCostBasis,
    // updated), plus cdmRef.
    assertThat(Arrays.stream(PositionProjection.class.getRecordComponents())
        .map(RecordComponent::getName))
        .containsExactly("accountId", "security", "quantity", "averageCostBasis", "updated",
            "cdmRef");
    Map<String, Object[]> expected = positionService009(List.of(fill1(), fill2(), directTrade()));
    assertThat(projection).hasSize(expected.size());
    PositionProjection ibm = projection.get(0);
    assertThat(ibm.accountId()).isEqualTo(ACCOUNT);
    assertThat(ibm.security()).isEqualTo("IBM");
    assertThat(ibm.quantity()).isEqualTo(expected.get("IBM")[0]).isEqualTo(1300);
    assertThat(ibm.averageCostBasis()).isEqualTo(expected.get("IBM")[1]);
    assertThat(ibm.updated()).isEqualTo(directTrade().created());
    assertThat(ibm.cdmRef()).isEqualTo("cdm:position:22214:IBM");
  }

  @Test
  void singlePositionIsTheCdmPositionOfThatSecurity() {
    bookFixtureTrades();

    assertThat(view.position(ACCOUNT, "IBM")).hasValueSatisfying(p ->
        assertThat(quantity(p)).isEqualTo(1300));
    assertThat(view.position(ACCOUNT, "MSFT")).isEmpty();
  }

  @Test
  void positionTradesSumToQuantity() {
    bookFixtureTrades();

    PositionView.AccountPositions positions = view.view(ACCOUNT);

    for (PositionProjection position : positions.positions()) {
      List<PositionView.Contribution> trades = positions.tradesOf(position.security());
      assertThat(trades).extracting(PositionView.Contribution::tradeId)
          .containsExactly("trd-017-0001", "trd-017-0002", "trd-017-0100");
      assertThat(trades).extracting(PositionView.Contribution::side)
          .containsExactly("Buy", "Buy", "Sell");
      assertThat(trades.stream().mapToInt(PositionView.Contribution::signedQuantity).sum())
          .isEqualTo(position.quantity()).isEqualTo(1300);
    }
    assertThat(positions.tradesOf("MSFT")).isEmpty();
  }
}
