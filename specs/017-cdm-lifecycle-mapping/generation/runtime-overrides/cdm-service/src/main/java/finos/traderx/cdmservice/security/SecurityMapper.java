package finos.traderx.cdmservice.security;

import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.AssetIdentifier;
import cdm.base.staticdata.asset.common.AssetTypeEnum;
import cdm.base.staticdata.asset.common.EquityType;
import cdm.base.staticdata.asset.common.EquityTypeEnum;
import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.asset.common.SecurityTypeEnum;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import finos.traderx.cdmservice.ref.CdmRef;

/**
 * Maps a TraderX stock (ticker, company name) to a CDM {@link Security} and back.
 *
 * <p>The CDM Security is an ordinary-share {@code Equity} with these identifiers:
 * <ul>
 *   <li>{@link AssetIdTypeEnum#EXCHANGE_CODE}: the ticker ("follows the symbology set by the
 *       exchange which lists the asset"), the TraderX key;</li>
 *   <li>{@link AssetIdTypeEnum#ISIN} and {@link AssetIdTypeEnum#FIGI}: demo values from
 *       {@link DemoIdentifiers}, with scheme {@link #DEMO_IDENTIFIER_SCHEME};</li>
 *   <li>{@link AssetIdTypeEnum#NAME}: the company name (CDM's Security has no name attribute).</li>
 * </ul>
 */
public final class SecurityMapper {

  /** Scheme on the synthetic ISIN/FIGI identifiers, marking them as demo data. */
  public static final String DEMO_IDENTIFIER_SCHEME = "https://traderx.finos.org/demo-identifier";

  private SecurityMapper() {
  }

  public static CdmRef cdmRef(String ticker) {
    return new CdmRef(CdmRef.Kind.SECURITY, ticker);
  }

  public static Security toCdm(String ticker, String companyName) {
    if (ticker == null || ticker.isBlank()) {
      throw new IllegalArgumentException("ticker is required");
    }
    if (companyName == null || companyName.isBlank()) {
      throw new IllegalArgumentException("companyName is required for " + ticker);
    }
    return Security.builder()
        .setAssetType(AssetTypeEnum.SECURITY)
        .setSecurityType(SecurityTypeEnum.EQUITY)
        .setEquityType(EquityType.builder().setEquityType(EquityTypeEnum.ORDINARY))
        .addIdentifier(identifier(ticker, AssetIdTypeEnum.EXCHANGE_CODE, null))
        .addIdentifier(identifier(DemoIdentifiers.isin(ticker), AssetIdTypeEnum.ISIN,
            DEMO_IDENTIFIER_SCHEME))
        .addIdentifier(identifier(DemoIdentifiers.figi(ticker), AssetIdTypeEnum.FIGI,
            DEMO_IDENTIFIER_SCHEME))
        .addIdentifier(identifier(companyName, AssetIdTypeEnum.NAME, null))
        .build();
  }

  public static StockProjection toProjection(Security security) {
    String ticker = identifierValue(security, AssetIdTypeEnum.EXCHANGE_CODE);
    if (ticker == null) {
      throw new IllegalArgumentException("Security has no ExchangeCode (ticker) identifier");
    }
    return new StockProjection(ticker, identifierValue(security, AssetIdTypeEnum.NAME),
        cdmRef(ticker).toString());
  }

  /** The first identifier value of {@code type}, or {@code null}. */
  public static String identifierValue(Security security, AssetIdTypeEnum type) {
    if (security == null || security.getIdentifier() == null) {
      return null;
    }
    return security.getIdentifier().stream()
        .filter(id -> id.getIdentifierType() == type && id.getIdentifier() != null)
        .map(id -> id.getIdentifier().getValue())
        .findFirst()
        .orElse(null);
  }

  private static AssetIdentifier identifier(String value, AssetIdTypeEnum type, String scheme) {
    FieldWithMetaString.FieldWithMetaStringBuilder field = FieldWithMetaString.builder()
        .setValue(value);
    if (scheme != null) {
      field.setMeta(MetaFields.builder().setScheme(scheme));
    }
    return AssetIdentifier.builder().setIdentifier(field).setIdentifierType(type).build();
  }
}
