package finos.traderx.cdmservice.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * The TraderX group subsidiaries that face clients ({@code seed/entities.json}): each is a legal
 * entity and a CDM {@code Party} ({@code cdm:party:<key>}), and each account is serviced by one
 * of them (CDM {@code Account.servicingParty}). The file also assigns the seeded 009 accounts;
 * any other account faces {@link Seed#defaultEntity()}.
 */
public final class EntitySeed {

  static final String RESOURCE = "/seed/entities.json";

  private EntitySeed() {
  }

  /** One subsidiary. */
  public record Entity(String key, String name) {
  }

  /** The whole seed file. */
  public record Seed(String comment, String defaultEntity, List<Entity> entities,
      Map<String, String> accounts) {

    public Seed {
      entities = List.copyOf(entities);
      accounts = Map.copyOf(accounts);
      if (entities.stream().noneMatch(e -> e.key().equals(defaultEntity))) {
        throw new IllegalStateException("defaultEntity " + defaultEntity + " is not an entity");
      }
      for (String key : accounts.values()) {
        if (entities.stream().noneMatch(e -> e.key().equals(key))) {
          throw new IllegalStateException("account assigned to unknown entity " + key);
        }
      }
    }

    /** The subsidiary that faces {@code accountId} when the account is first seeded. */
    public String entityFor(int accountId) {
      return accounts.getOrDefault(Integer.toString(accountId), defaultEntity);
    }

    public boolean isEntity(String key) {
      return entities.stream().anyMatch(e -> e.key().equals(key));
    }
  }

  public static Seed load() {
    try (InputStream in = EntitySeed.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Missing classpath resource " + RESOURCE);
      }
      return new ObjectMapper().readValue(in, Seed.class);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
  }
}
