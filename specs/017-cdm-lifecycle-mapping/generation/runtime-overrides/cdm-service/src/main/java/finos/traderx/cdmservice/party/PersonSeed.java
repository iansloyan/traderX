package finos.traderx.cdmservice.party;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The person directory people-service served from {@code MockDirectory/people.json} up to state
 * 009, copied to {@code seed/people.json}. It seeds the CDM person master once.
 */
public final class PersonSeed {

  static final String RESOURCE = "/seed/people.json";

  private PersonSeed() {
  }

  /** One people.json entry (people-service's PascalCase field names). */
  public record SeedPerson(
      @JsonProperty("LogonId") String logonId,
      @JsonProperty("FullName") String fullName,
      @JsonProperty("Email") String email,
      @JsonProperty("EmployeeId") String employeeId,
      @JsonProperty("Department") String department,
      @JsonProperty("PhotoUrl") String photoUrl) {
  }

  public static List<SeedPerson> load() {
    try (InputStream in = PersonSeed.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing classpath resource " + RESOURCE);
      }
      return List.copyOf(new ObjectMapper().readValue(in, new TypeReference<List<SeedPerson>>() {
      }));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }
}
