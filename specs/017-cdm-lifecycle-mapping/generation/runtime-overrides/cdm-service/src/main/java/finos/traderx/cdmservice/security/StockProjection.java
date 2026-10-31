package finos.traderx.cdmservice.security;

/**
 * The simple stock shape reference-data has always served ({@code ticker}, {@code companyName}),
 * projected from a CDM {@code Security}, plus its {@code cdmRef}.
 */
public record StockProjection(String ticker, String companyName, String cdmRef) {
}
