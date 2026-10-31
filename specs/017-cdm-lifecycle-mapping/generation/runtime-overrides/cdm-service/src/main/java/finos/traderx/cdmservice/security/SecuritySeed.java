package finos.traderx.cdmservice.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The seed stock list, built exactly as reference-data built it from
 * {@code data/s-and-p-500-companies.csv} before 017 (its {@code load-csv-data.ts}): CSV order,
 * upper-cased tickers, {@code FB} renamed {@code META}, first occurrence wins, preferred company
 * names for a few tickers, then the supplemental sample stocks that are not in the CSV.
 */
public final class SecuritySeed {

  /** Classpath location of the seed (a copy of reference-data's CSV). */
  public static final String RESOURCE = "/seed/s-and-p-500-companies.csv";

  public record SeedStock(String ticker, String companyName) {
  }

  private static final List<SeedStock> SUPPLEMENTAL = List.of(
      new SeedStock("MS", "Morgan Stanley"),
      new SeedStock("UBS", "UBS Group AG"),
      new SeedStock("C", "Citigroup Inc."),
      new SeedStock("GS", "Goldman Sachs Group, Inc."),
      new SeedStock("DB", "Deutsche Bank AG"),
      new SeedStock("JPM", "JPMorgan Chase & Co."),
      new SeedStock("COF", "Capital One Financial Corporation"),
      new SeedStock("DFS", "Discover Financial Services"),
      new SeedStock("FNMA", "Fannie Mae"),
      new SeedStock("FIS", "Fidelity National Information Services, Inc."),
      new SeedStock("FNF", "Fidelity National Financial, Inc."));

  private static final Map<String, String> PREFERRED_COMPANY_NAME = Map.ofEntries(
      Map.entry("MS", "Morgan Stanley"),
      Map.entry("UBS", "UBS Group AG"),
      Map.entry("C", "Citigroup Inc."),
      Map.entry("GS", "Goldman Sachs Group, Inc."),
      Map.entry("DB", "Deutsche Bank AG"),
      Map.entry("JPM", "JPMorgan Chase & Co."),
      Map.entry("COF", "Capital One Financial Corporation"),
      Map.entry("DFS", "Discover Financial Services"),
      Map.entry("FNMA", "Fannie Mae"),
      Map.entry("FIS", "Fidelity National Information Services, Inc."),
      Map.entry("FNF", "Fidelity National Financial, Inc."),
      Map.entry("META", "Meta Platforms, Inc."));

  private SecuritySeed() {
  }

  public static List<SeedStock> load() {
    try (InputStream in = SecuritySeed.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing seed resource " + RESOURCE);
      }
      return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read seed " + RESOURCE, e);
    }
  }

  private static List<SeedStock> parse(BufferedReader reader) throws IOException {
    Map<String, SeedStock> stocks = new LinkedHashMap<>();
    String line = reader.readLine(); // header
    while ((line = reader.readLine()) != null) {
      List<String> row = splitCsvLine(line);
      String raw = row.isEmpty() ? "" : row.get(0).toUpperCase(Locale.ROOT);
      String ticker = raw.equals("FB") ? "META" : raw;
      if (ticker.isEmpty() || stocks.containsKey(ticker)) {
        continue;
      }
      String name = PREFERRED_COMPANY_NAME.getOrDefault(ticker, row.size() > 1 ? row.get(1) : "");
      stocks.put(ticker, new SeedStock(ticker, name));
    }
    for (SeedStock stock : SUPPLEMENTAL) {
      stocks.putIfAbsent(stock.ticker(), stock);
    }
    return List.copyOf(stocks.values());
  }

  /** Splits one RFC 4180 line (quoted fields, doubled quotes) and trims each field. */
  static List<String> splitCsvLine(String line) {
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          field.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
        } else {
          field.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        fields.add(field.toString().trim());
        field.setLength(0);
      } else {
        field.append(c);
      }
    }
    fields.add(field.toString().trim());
    return fields;
  }
}
