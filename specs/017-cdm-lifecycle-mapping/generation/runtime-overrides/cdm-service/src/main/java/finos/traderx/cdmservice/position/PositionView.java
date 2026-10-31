package finos.traderx.cdmservice.position;

import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.PartyRole;
import cdm.base.staticdata.party.PartyRoleEnum;
import cdm.event.common.Lineage;
import cdm.event.common.Trade;
import cdm.event.common.TradeState;
import cdm.event.common.metafields.ReferenceWithMetaTrade;
import cdm.event.position.PortfolioState;
import cdm.event.position.Position;
import cdm.event.position.metafields.ReferenceWithMetaPortfolioState;
import cdm.event.workflow.EventTimestamp;
import cdm.event.workflow.EventTimestampQualificationEnum;
import cdm.event.workflow.WorkflowStep;
import cdm.event.workflow.metafields.ReferenceWithMetaWorkflowStep;
import cdm.observable.asset.PriceQuantity;
import cdm.product.template.Product;
import cdm.product.template.TradeLot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Injector;
import com.regnosys.rosetta.common.hashing.GlobalKeyProcessStep;
import com.regnosys.rosetta.common.hashing.NonNullHashCollector;
import com.rosetta.model.lib.RosettaModelObject;
import com.rosetta.model.lib.RosettaModelObjectBuilder;
import com.rosetta.model.metafields.MetaFields;
import finos.traderx.cdmservice.lifecycle.CdmProducts;
import finos.traderx.cdmservice.lifecycle.Side;
import finos.traderx.cdmservice.ref.CdmRef;
import finos.traderx.cdmservice.security.SecurityMapper;
import finos.traderx.cdmservice.store.CdmStore;
import finos.traderx.cdmservice.store.ProducedBy;
import finos.traderx.cdmservice.store.StoredCdm;
import finos.traderx.cdmservice.validation.CdmValidationResult;
import finos.traderx.cdmservice.validation.CdmValidator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * An account's positions derived from the stored CDM {@link TradeState}s: every trade state
 * under a {@code cdm:trade:<tradeId>} ref (order fills and direct trades alike, one trade per
 * fill) whose {@code Party1} counterparty is the account. Nothing is stored: the view is
 * computed on read, so it always reflects the latest trade states.
 *
 * <p><b>Quantity.</b> Each trade counts its trade lot quantity, plus when the account is the
 * {@code Buyer} and minus when it is the {@code Seller}; a position is the sum per security. A
 * trade state the account is neither buyer nor seller of, or that cannot be read or counted, is
 * skipped and listed in {@link AccountPositions#skipped()}; it never fails the account.
 * This is the quantity 009 trade-processor books into {@code Positions} for the same trades.
 * {@code averageCostBasis} repeats 009's arithmetic trade by trade, in execution order.
 *
 * <p><b>CDM shape.</b> The result is a CDM 7.8.0 {@link PortfolioState}: one {@link Position}
 * per security, whose product is the TraderX cash equity on that security
 * ({@link CdmProducts#cashEquity}) with the account's side of the net position (receiver when
 * long, payer when short, so a short position is expressed by direction, because CDM quantities
 * are non-negative), and one price/quantity: the absolute net shares and, when positive, the
 * average cost per share. Its lineage references the CDM execution {@link WorkflowStep}s and the
 * {@link Trade}s it aggregates, and the account's initial (empty) portfolio state: CDM's
 * {@code Initialisation} condition only allows positions in a state that has a previous state.
 * {@link #positions} of an account without CDM trades is that initial state (the REST API
 * answers 404 pending for it instead, Ruling R30). Both carry Rune global keys
 * ({@code meta.globalKey}, which the lineage references use; not the store's SHA-256 key, see
 * Ruling R29); their external keys are {@code <accountId>} and {@code <accountId>:initial}.
 * The initial state is referenced by its external key: having no content, it gets no Rune
 * global key.
 *
 * <p><b>Validation.</b> The state is validated with {@link CdmValidator} on every read and the
 * result returned with it. A state with positions validates. The initial state cannot: in CDM
 * 7.8.0 its condition {@code Initialisation} requires {@code positions} to be absent, while
 * {@code NonTransferable} ({@code positions -> product as NonTransferableProduct exists}) is
 * false when they are, so the initial state reports {@code INVALID} with that single
 * data-rule message (a CDM model inconsistency, recorded in research.md).
 */
@Component
public class PositionView {

  private static final Logger log = LoggerFactory.getLogger(PositionView.class);
  private static final GlobalKeyProcessStep GLOBAL_KEYS =
      new GlobalKeyProcessStep(NonNullHashCollector::new);

  /**
   * One trade state counted in a position. Keys come in the two kinds of Ruling R29:
   * {@code storeGlobalKey} is the store's SHA-256 of the trade state's canonical JSON (what
   * {@code cdm_documents} and the lineage/trade APIs navigate by); the {@code ...CdmGlobalKey}s
   * are Rune {@code meta.globalKey}s, which CDM-internal references use: the portfolio state's
   * {@code lineage.tradeReference} holds {@code tradeCdmGlobalKey} and its
   * {@code lineage.eventReference} holds {@code executionStepCdmGlobalKey}.
   *
   * @param cdmGlobalKey              the trade state's Rune key
   * @param tradeCdmGlobalKey         its trade's Rune key
   * @param executionStepCdmGlobalKey the Rune key of the execution step that formed it
   */
  public record Contribution(String cdmRef, String tradeId, String security, int signedQuantity,
      BigDecimal price, Instant executedAt, CdmValidationResult validation,
      String storeGlobalKey, String cdmGlobalKey, String tradeCdmGlobalKey,
      String executionStepCdmGlobalKey) {

    /** {@code Buy} if the account bought (a positive contribution), else {@code Sell}. */
    public String side() {
      return signedQuantity >= 0 ? "Buy" : "Sell";
    }
  }

  /** A trade state of the account that could not be counted, and why (never silently dropped). */
  public record Skipped(String cdmRef, String reason) {
  }

  /**
   * An account's positions.
   *
   * @param cdmRef         {@code cdm:position:<accountId>}
   * @param portfolioState the CDM portfolio state (the initial state when there are no trades)
   * @param initialState   the account's initial, empty portfolio state it is linked to
   * @param validation     {@link CdmValidator} result of {@code portfolioState}
   * @param trades         the trade states counted, in execution order
   * @param positions      the same positions as {@link PositionProjection}s, by security
   * @param skipped        the account's trade states that could not be counted
   */
  public record AccountPositions(int accountId, CdmRef cdmRef, PortfolioState portfolioState,
      PortfolioState initialState, CdmValidationResult validation, List<Contribution> trades,
      List<PositionProjection> positions, List<Skipped> skipped) {

    /** Whether any CDM trade of the account is counted (else the state is the initial one). */
    public boolean hasTrades() {
      return !trades.isEmpty();
    }

    /**
     * The trade states counted in the position of {@code security}, in execution order: their
     * signed quantities sum to that position's quantity.
     */
    public List<Contribution> tradesOf(String security) {
      return trades.stream().filter(c -> Objects.equals(security, c.security())).toList();
    }
  }

  private final CdmStore store;
  private final ObjectMapper cdmObjectMapper;
  private final CdmValidator validator;
  private final CdmProducts products;

  public PositionView(CdmStore store, @Qualifier("cdmObjectMapper") ObjectMapper cdmObjectMapper,
      CdmValidator validator, @Qualifier("cdmInjector") Injector cdmInjector) {
    this.store = store;
    this.cdmObjectMapper = cdmObjectMapper;
    this.validator = validator;
    this.products = new CdmProducts(cdmInjector);
  }

  /** {@code cdm:position:<accountId>}: the account's portfolio. */
  public static CdmRef portfolioRef(int accountId) {
    return new CdmRef(CdmRef.Kind.POSITION, Integer.toString(accountId));
  }

  /** {@code cdm:position:<accountId>:<security>}: one position. */
  public static CdmRef positionRef(int accountId, String security) {
    return new CdmRef(CdmRef.Kind.POSITION, accountId + ":" + security);
  }

  /** The account's CDM portfolio state. */
  public PortfolioState positions(int accountId) {
    return view(accountId).portfolioState();
  }

  /** The account's positions in the 009 position-service shape plus {@code cdmRef}. */
  public List<PositionProjection> projection(int accountId) {
    return view(accountId).positions();
  }

  /** The CDM position of one security, if the account has traded it. */
  public Optional<Position> position(int accountId, String security) {
    AccountPositions view = view(accountId);
    List<? extends Position> held = view.portfolioState().getPositions();
    return (held == null ? List.<Position>of() : held).stream()
        .filter(p -> Objects.equals(security, ticker(p.getProduct())))
        .map(p -> (Position) p)
        .findFirst();
  }

  /** The account's positions, CDM portfolio state and the trades they are derived from. */
  public AccountPositions view(int accountId) {
    List<Skipped> skipped = new ArrayList<>();
    List<Counted> counted = countedTrades(accountId, skipped);
    Map<String, Holding> holdings = new TreeMap<>();
    for (Counted trade : counted) {
      holdings.computeIfAbsent(trade.contribution().security(), s -> new Holding(trade.security()))
          .add(trade);
    }

    PortfolioState initial = keyed(PortfolioState.builder()
        .setLineage(Lineage.builder())
        .setMeta(MetaFields.builder().setExternalKey(accountId + ":initial"))
        .build());
    PortfolioState state = initial;
    if (!holdings.isEmpty()) {
      Lineage.LineageBuilder lineage = Lineage.builder()
          // By external key: Rune gives the empty initial state no global key (its content
          // hashes to nothing), so a global reference to it would be null.
          .addPortfolioStateReference(ReferenceWithMetaPortfolioState.builder()
              .setExternalReference(initial.getMeta().getExternalKey()));
      for (Counted trade : counted) {
        if (trade.stepKey() != null) {
          lineage.addEventReference(ReferenceWithMetaWorkflowStep.builder()
              .setGlobalReference(trade.stepKey()));
        }
        if (trade.tradeKey() != null) {
          lineage.addTradeReference(ReferenceWithMetaTrade.builder()
              .setGlobalReference(trade.tradeKey()));
        }
      }
      PortfolioState.PortfolioStateBuilder builder = PortfolioState.builder()
          .setLineage(lineage)
          .setMeta(MetaFields.builder().setExternalKey(Integer.toString(accountId)));
      holdings.values().forEach(h -> builder.addPositions(h.toCdm(products)));
      state = keyed(builder.build());
    }

    List<PositionProjection> projections = holdings.values().stream()
        .map(h -> new PositionProjection(accountId, h.ticker, h.quantity, h.averageCostBasis,
            h.updated, positionRef(accountId, h.ticker).toString()))
        .toList();
    return new AccountPositions(accountId, portfolioRef(accountId), state, initial,
        validator.validate(state), counted.stream().map(Counted::contribution).toList(),
        projections, List.copyOf(skipped));
  }

  // --- reading the trade states ----------------------------------------------------------------

  /** A counted trade state, with the CDM keys its portfolio-state lineage references. */
  private record Counted(Contribution contribution, Security security, String stepKey,
      String tradeKey) {
  }

  /**
   * The account's trade states, in execution order (the execution time of the accepted step that
   * produced each; write order for equal times). Two queries: the latest trade state of every
   * trade, then the accepted steps they were produced by.
   */
  private List<Counted> countedTrades(int accountId, List<Skipped> skipped) {
    String account = Integer.toString(accountId);
    List<Read> mine = new ArrayList<>();
    for (StoredCdm stored : store.latestOfType(TradeState.class)) {
      if (stored.ref().kind() != CdmRef.Kind.TRADE) {
        continue;
      }
      TradeState state = read(stored, TradeState.class);
      if (state == null) {
        // Unreadable: the account is unknown, so it is listed for every account.
        skipped.add(new Skipped(stored.ref().toString(), "stored TradeState is unreadable"));
      } else if (state.getTrade() != null && account.equals(party1(state.getTrade()))) {
        mine.add(new Read(stored, state));
      }
    }
    if (mine.isEmpty()) {
      return List.of();
    }

    Map<String, StoredCdm> steps = new HashMap<>();
    store.byGlobalKeys(WorkflowStep.class, producerKeys(mine.stream().map(Read::stored).toList()))
        .forEach(s -> steps.putIfAbsent(s.ref() + " " + s.globalKey(), s));

    List<Counted> counted = new ArrayList<>();
    for (Read r : mine) {
      ProducedBy producer = r.stored().producedBy();
      StoredCdm stepRow = producer == null ? null
          : steps.get(producer.ref() + " " + producer.storeGlobalKey());
      WorkflowStep step = stepRow == null ? null : read(stepRow, WorkflowStep.class);
      Instant executedAt = executionTime(step);
      try {
        counted.add(count(accountId, r, step, executedAt == null ? r.stored().createdAt()
            : executedAt));
      } catch (RuntimeException e) {
        // One bad trade state never fails the whole account (e.g. a fractional quantity).
        log.warn("Trade state {} not counted in positions: {}", r.stored().ref(), e.toString());
        skipped.add(new Skipped(r.stored().ref().toString(),
            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      }
    }
    // Stable: equal execution times keep write order.
    counted.sort(Comparator.comparing(c -> c.contribution().executedAt()));
    return counted;
  }

  private record Read(StoredCdm stored, TradeState state) {
  }

  /**
   * The trade's contribution; throws (and the caller skips it) if it has no security or
   * quantity, a non-integral quantity, or the account is neither its buyer nor its seller.
   */
  private Counted count(int accountId, Read r, WorkflowStep step, Instant executedAt) {
    Trade trade = r.state().getTrade();
    Security security = security(trade);
    String ticker = SecurityMapper.identifierValue(security, AssetIdTypeEnum.EXCHANGE_CODE);
    PriceQuantity pq = priceQuantity(trade);
    if (ticker == null || pq == null || pq.getQuantity() == null
        || pq.getQuantity().getValue() == null) {
      throw new IllegalArgumentException("trade has no security or quantity");
    }
    int quantity = pq.getQuantity().getValue().getValue().intValueExact();
    String account = Integer.toString(accountId);
    int sign;
    if (account.equals(role(trade, PartyRoleEnum.BUYER))) {
      sign = 1;
    } else if (account.equals(role(trade, PartyRoleEnum.SELLER))) {
      sign = -1;
    } else {
      throw new IllegalArgumentException("account " + account
          + " is neither the Buyer nor the Seller of the trade");
    }
    BigDecimal price = pq.getPrice() == null || pq.getPrice().isEmpty() ? null
        : pq.getPrice().get(0).getValue().getValue();
    String stepKey = step == null || step.getMeta() == null ? null : step.getMeta().getGlobalKey();
    String tradeKey = trade.getMeta() == null ? null : trade.getMeta().getGlobalKey();
    String stateKey = r.state().getMeta() == null ? null : r.state().getMeta().getGlobalKey();
    Contribution contribution = new Contribution(r.stored().ref().toString(),
        r.stored().ref().key(), ticker, sign * quantity, price, executedAt,
        r.stored().validation(), r.stored().globalKey(), stateKey, tradeKey, stepKey);
    return new Counted(contribution, security, stepKey, tradeKey);
  }

  /** The store global keys of the steps that produced {@code rows}. */
  private static Set<String> producerKeys(List<StoredCdm> rows) {
    Set<String> keys = new LinkedHashSet<>();
    rows.stream().map(StoredCdm::producedBy).filter(Objects::nonNull)
        .map(ProducedBy::storeGlobalKey).forEach(keys::add);
    return keys;
  }

  private <T extends RosettaModelObject> T read(StoredCdm stored, Class<T> type) {
    try {
      return cdmObjectMapper.readValue(stored.json(), type);
    } catch (JsonProcessingException e) {
      log.warn("Stored {} {} under {} is unreadable; not counted in positions",
          type.getSimpleName(), stored.globalKey(), stored.ref(), e);
      return null;
    }
  }

  private static String party1(Trade trade) {
    return trade.getCounterparty() == null ? null : trade.getCounterparty().stream()
        .filter(c -> c.getRole() == CounterpartyRoleEnum.PARTY_1)
        .map(Counterparty::getPartyReference)
        .filter(Objects::nonNull)
        .map(r -> r.getExternalReference())
        .findFirst().orElse(null);
  }

  private static String role(Trade trade, PartyRoleEnum role) {
    return trade.getPartyRole() == null ? null : trade.getPartyRole().stream()
        .filter(r -> r.getRole() == role)
        .map(PartyRole::getPartyReference)
        .filter(Objects::nonNull)
        .map(r -> r.getExternalReference())
        .findFirst().orElse(null);
  }

  private static PriceQuantity priceQuantity(Trade trade) {
    if (trade.getTradeLot() == null) {
      return null;
    }
    for (TradeLot lot : trade.getTradeLot()) {
      if (lot.getPriceQuantity() != null && !lot.getPriceQuantity().isEmpty()) {
        return lot.getPriceQuantity().get(0);
      }
    }
    return null;
  }

  private static Security security(Trade trade) {
    try {
      return trade.getProduct().getEconomicTerms().getPayout().get(0).getSettlementPayout()
          .getUnderlier().getObservable().getValue().getAsset().getInstrument().getSecurity();
    } catch (NullPointerException | IndexOutOfBoundsException e) {
      return null;
    }
  }

  private static String ticker(Product product) {
    try {
      return SecurityMapper.identifierValue(product.getNonTransferableProduct().getEconomicTerms()
          .getPayout().get(0).getSettlementPayout().getUnderlier().getObservable().getValue()
          .getAsset().getInstrument().getSecurity(), AssetIdTypeEnum.EXCHANGE_CODE);
    } catch (NullPointerException | IndexOutOfBoundsException e) {
      return null;
    }
  }

  private static Instant executionTime(WorkflowStep step) {
    if (step == null || step.getTimestamp() == null) {
      return null;
    }
    return step.getTimestamp().stream()
        .filter(t -> t.getQualification() == EventTimestampQualificationEnum.EXECUTION_DATE_TIME
            && t.getDateTime() != null)
        .map(EventTimestamp::getDateTime)
        .map(dt -> dt.toInstant())
        .findFirst().orElse(null);
  }

  @SuppressWarnings("unchecked")
  private static <T extends RosettaModelObject> T keyed(T object) {
    RosettaModelObjectBuilder builder = object.toBuilder();
    GLOBAL_KEYS.runProcessStep(builder.getType(), builder);
    return (T) builder.build();
  }

  // --- aggregation ---------------------------------------------------------------------------

  /** One security's running position, booked as 009 trade-processor books it. */
  private static final class Holding {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);

    private final String ticker;
    private final Security security;
    private int quantity;
    private BigDecimal averageCostBasis = ZERO;
    private Instant updated;

    Holding(Security security) {
      this.security = security;
      this.ticker = SecurityMapper.identifierValue(security, AssetIdTypeEnum.EXCHANGE_CODE);
    }

    void add(Counted trade) {
      Contribution c = trade.contribution();
      BigDecimal price = (c.price() == null ? BigDecimal.ZERO : c.price())
          .setScale(3, RoundingMode.HALF_UP);
      int newQuantity = quantity + c.signedQuantity();
      BigDecimal notional = averageCostBasis.multiply(BigDecimal.valueOf(quantity))
          .add(price.multiply(BigDecimal.valueOf(c.signedQuantity())));
      averageCostBasis = newQuantity == 0 ? ZERO
          : notional.divide(BigDecimal.valueOf(newQuantity), 3, RoundingMode.HALF_UP);
      quantity = newQuantity;
      updated = c.executedAt();
    }

    Position toCdm(CdmProducts products) {
      Side side = quantity < 0 ? Side.SELL : Side.BUY;
      BigDecimal price = averageCostBasis.signum() > 0 ? averageCostBasis : null;
      return Position.builder()
          .addPriceQuantity(CdmProducts.priceQuantity(price, Math.abs(quantity), false))
          .setProduct(Product.builder()
              .setNonTransferableProduct(products.cashEquity(security, side)))
          .build();
    }
  }
}
