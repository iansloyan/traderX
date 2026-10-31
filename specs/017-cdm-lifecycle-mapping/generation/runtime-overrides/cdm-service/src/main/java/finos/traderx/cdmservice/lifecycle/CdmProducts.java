package finos.traderx.cdmservice.lifecycle;

import cdm.base.datetime.AdjustableOrAdjustedOrRelativeDate;
import cdm.base.datetime.BusinessDayConventionEnum;
import cdm.base.datetime.DayTypeEnum;
import cdm.base.datetime.PeriodEnum;
import cdm.base.datetime.RelativeDateOffset;
import cdm.base.math.FinancialUnitEnum;
import cdm.base.math.NonNegativeQuantitySchedule;
import cdm.base.math.UnitType;
import cdm.base.math.metafields.FieldWithMetaNonNegativeQuantitySchedule;
import cdm.base.staticdata.asset.common.Asset;
import cdm.base.staticdata.asset.common.Instrument;
import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.party.AssetAncillaryPartyRoleEnum;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.PayerReceiver;
import cdm.event.common.functions.Create_NonTransferableProduct;
import cdm.observable.asset.Observable;
import cdm.observable.asset.PriceExpressionEnum;
import cdm.observable.asset.PriceQuantity;
import cdm.observable.asset.PriceSchedule;
import cdm.observable.asset.PriceTypeEnum;
import cdm.observable.asset.metafields.FieldWithMetaPriceSchedule;
import cdm.product.common.settlement.SettlementDate;
import cdm.product.common.settlement.SettlementTerms;
import cdm.product.common.settlement.SettlementTypeEnum;
import cdm.product.common.settlement.TransferSettlementEnum;
import cdm.product.template.NonTransferableProduct;
import cdm.product.template.Underlier;
import com.google.inject.Injector;
import com.rosetta.model.metafields.ReferenceWithMetaDate;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The CDM product TraderX trades: a cash equity, i.e. a {@code SettlementPayout} whose underlier
 * is the CDM {@link Security}, settled delivery-versus-payment in USD two business days after the
 * trade date. Also the price and quantity conventions: quantities in shares
 * ({@link FinancialUnitEnum#SHARE}), prices as an {@link PriceTypeEnum#ASSET_PRICE} in USD per
 * share, normalised to {@value #PRICE_SCALE} decimals.
 */
public final class CdmProducts {

  /** TraderX trades in a single currency. */
  public static final String CURRENCY = "USD";
  /** Settlement lag in business days after the trade date (T+2). */
  public static final int SETTLEMENT_LAG_DAYS = 2;
  /** Decimals of every price (and cash amount) in the CDM lifecycle objects. */
  public static final int PRICE_SCALE = 3;
  /**
   * The external key of every trade date ({@code tradeDate [metadata id]}), so the relative
   * settlement date can name its anchor ({@code dateRelativeTo}).
   */
  public static final String TRADE_DATE_KEY = "tradeDate";

  private final Create_NonTransferableProduct createProduct;

  public CdmProducts(Injector cdmInjector) {
    this.createProduct = cdmInjector.getInstance(Create_NonTransferableProduct.class);
  }

  /**
   * The cash-equity product on {@code security}, built with CDM's
   * {@code Create_NonTransferableProduct}. The account is {@code Party1} and the venue
   * {@code Party2}; the seller delivers the shares, so the payer of the settlement payout is the
   * seller and the receiver the buyer. With no side (a rejected request without one) the
   * payer/receiver is left out and the product does not validate, which is reported, not hidden.
   */
  public NonTransferableProduct cashEquity(Security security, Side accountSide) {
    Underlier underlier = Underlier.builder()
        .setObservableValue(Observable.builder()
            .setAsset(Asset.builder()
                .setInstrument(Instrument.builder().setSecurity(security))))
        .build();
    PayerReceiver payerReceiver = accountSide == null ? null : PayerReceiver.builder()
        .setPayer(accountSide == Side.SELL ? CounterpartyRoleEnum.PARTY_1
            : CounterpartyRoleEnum.PARTY_2)
        .setReceiver(accountSide == Side.SELL ? CounterpartyRoleEnum.PARTY_2
            : CounterpartyRoleEnum.PARTY_1)
        .build();
    NonTransferableProduct.NonTransferableProductBuilder product =
        createProduct.evaluate(underlier, payerReceiver).toBuilder();
    product.getOrCreateEconomicTerms().getOrCreatePayout(0).getOrCreateSettlementPayout()
        .setSettlementTerms(settlementTerms());
    return product.build();
  }

  /**
   * DVP, physical delivery of the shares against USD, on trade date + 2 business days.
   * {@code securitySettlementCentre}/{@code cashSettlementCentre} are required by the
   * {@code SettlementPayout} condition {@code SettlementCentreRequired} for a security underlier;
   * CDM types them as the role constants themselves, so they carry no TraderX data.
   */
  static SettlementTerms settlementTerms() {
    return SettlementTerms.builder()
        .setSettlementType(SettlementTypeEnum.PHYSICAL)
        .setTransferSettlementType(TransferSettlementEnum.DELIVERY_VERSUS_PAYMENT)
        .setSettlementCurrencyValue(CURRENCY)
        .setSettlementDate(SettlementDate.builder()
            .setAdjustableOrRelativeDate(AdjustableOrAdjustedOrRelativeDate.builder()
                .setRelativeDate(RelativeDateOffset.builder()
                    .setPeriodMultiplier(SETTLEMENT_LAG_DAYS)
                    .setPeriod(PeriodEnum.D)
                    .setDayType(DayTypeEnum.BUSINESS)
                    .setBusinessDayConvention(BusinessDayConventionEnum.NONE)
                    .setDateRelativeTo(ReferenceWithMetaDate.builder()
                        .setExternalReference(TRADE_DATE_KEY)))))
        .setSecuritySettlementCentre(AssetAncillaryPartyRoleEnum.SECURITY_SETTLEMENT_CENTRE)
        .setCashSettlementCentre(AssetAncillaryPartyRoleEnum.CASH_SETTLEMENT_CENTRE)
        .build();
  }

  /**
   * One price/quantity: {@code shares} shares at {@code pricePerShare} USD per share. Either may
   * be {@code null} (a rejected request may lack them). With {@code withConsideration} the price
   * also carries the gross consideration in USD ({@code derivedQuantity} = price x shares, which
   * CDM's {@code PriceQuantityTriangulation} checks).
   */
  public static PriceQuantity priceQuantity(BigDecimal pricePerShare, Integer shares,
      boolean withConsideration) {
    PriceQuantity.PriceQuantityBuilder pq = PriceQuantity.builder();
    if (pricePerShare != null) {
      BigDecimal price = price(pricePerShare);
      PriceSchedule.PriceScheduleBuilder schedule = PriceSchedule.builder()
          .setValue(price)
          .setUnit(usd())
          .setPerUnitOf(UnitType.builder().setFinancialUnit(FinancialUnitEnum.SHARE))
          .setPriceType(PriceTypeEnum.ASSET_PRICE)
          .setPriceExpression(PriceExpressionEnum.ABSOLUTE_TERMS);
      if (withConsideration && shares != null) {
        schedule.setDerivedQuantity(NonNegativeQuantitySchedule.builder()
            .setValue(price.multiply(BigDecimal.valueOf(shares)).setScale(PRICE_SCALE))
            .setUnit(usd()));
      }
      // Explicit meta-field wrappers: the *Value convenience setters of [metadata location]
      // attributes add an empty location key, which fails CDM's KEY validation.
      pq.addPrice(FieldWithMetaPriceSchedule.builder().setValue(schedule).build());
    }
    if (shares != null) {
      pq.setQuantity(FieldWithMetaNonNegativeQuantitySchedule.builder()
          .setValue(NonNegativeQuantitySchedule.builder()
              .setValue(BigDecimal.valueOf(shares))
              .setUnit(UnitType.builder().setFinancialUnit(FinancialUnitEnum.SHARE)))
          .build());
    }
    return pq.build();
  }

  /** A price normalised to {@value #PRICE_SCALE} decimals (half-up). */
  public static BigDecimal price(BigDecimal value) {
    return value == null ? null : value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
  }

  static UnitType usd() {
    return UnitType.builder().setCurrencyValue(CURRENCY).build();
  }
}
