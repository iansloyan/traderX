package finos.traderx.cdmservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cdm.base.staticdata.asset.common.AssetIdTypeEnum;
import cdm.base.staticdata.asset.common.AssetIdentifier;
import cdm.base.staticdata.asset.common.AssetTypeEnum;
import cdm.base.staticdata.asset.common.EquityTypeEnum;
import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.asset.common.SecurityTypeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.cdmservice.config.CdmRuntimeConfig;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SecurityMapperTest {

  private final ObjectMapper cdmObjectMapper = new CdmRuntimeConfig().cdmObjectMapper();

  @Test
  void projectionRoundTripIsLossless() {
    StockProjection projection = SecurityMapper.toProjection(
        SecurityMapper.toCdm("IBM", "International Business Machines"));

    assertThat(projection).isEqualTo(
        new StockProjection("IBM", "International Business Machines", "cdm:security:IBM"));
  }

  @Test
  void cdmSecurityCarriesTickerIsinFigiAndNameIdentifiers() {
    Security security = SecurityMapper.toCdm("IBM", "International Business Machines");

    Map<AssetIdTypeEnum, String> byType = security.getIdentifier().stream()
        .collect(Collectors.toMap(AssetIdentifier::getIdentifierType,
            id -> id.getIdentifier().getValue()));
    // Ticker, ISIN and FIGI are the three market identifiers; CDM's Security has no name
    // attribute, so the company name is carried as the CDM "Name" identifier.
    assertThat(byType).containsOnly(
        Map.entry(AssetIdTypeEnum.EXCHANGE_CODE, "IBM"),
        Map.entry(AssetIdTypeEnum.ISIN, DemoIdentifiers.isin("IBM")),
        Map.entry(AssetIdTypeEnum.FIGI, DemoIdentifiers.figi("IBM")),
        Map.entry(AssetIdTypeEnum.NAME, "International Business Machines"));
    assertThat(security.getSecurityType()).isEqualTo(SecurityTypeEnum.EQUITY);
    assertThat(security.getAssetType()).isEqualTo(AssetTypeEnum.SECURITY);
    assertThat(security.getEquityType().getEquityType()).isEqualTo(EquityTypeEnum.ORDINARY);
  }

  @Test
  void demoIdentifiersAreLabelledWithTheDemoScheme() {
    Security security = SecurityMapper.toCdm("IBM", "International Business Machines");

    assertThat(security.getIdentifier())
        .filteredOn(id -> id.getIdentifierType() == AssetIdTypeEnum.ISIN
            || id.getIdentifierType() == AssetIdTypeEnum.FIGI)
        .hasSize(2)
        .allSatisfy(id -> assertThat(id.getIdentifier().getMeta().getScheme())
            .isEqualTo(SecurityMapper.DEMO_IDENTIFIER_SCHEME));
  }

  @Test
  void survivesCdmJsonRoundTrip() throws Exception {
    Security security = SecurityMapper.toCdm("BRK.B", "Berkshire Hathaway");

    String json = cdmObjectMapper.writeValueAsString(security);
    Security read = cdmObjectMapper.readValue(json, Security.class);

    assertThat(read).isEqualTo(security);
    assertThat(SecurityMapper.toProjection(read))
        .isEqualTo(new StockProjection("BRK.B", "Berkshire Hathaway", "cdm:security:BRK.B"));
  }

  @Test
  void toProjectionRequiresATickerIdentifier() {
    Security noTicker = Security.builder()
        .setSecurityType(SecurityTypeEnum.EQUITY)
        .setAssetType(AssetTypeEnum.SECURITY)
        .addIdentifier(AssetIdentifier.builder()
            .setIdentifierValue("XX0000000000").setIdentifierType(AssetIdTypeEnum.ISIN))
        .build();

    assertThatThrownBy(() -> SecurityMapper.toProjection(noTicker))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
