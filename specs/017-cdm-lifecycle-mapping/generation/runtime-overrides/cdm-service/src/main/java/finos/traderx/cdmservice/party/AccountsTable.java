package finos.traderx.cdmservice.party;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The 009 {@code accounts} / {@code accountusers} rows, kept as <b>foreign-key anchors</b> only
 * (Ruling R19): {@code trades.accountid} and {@code positions.accountid} reference
 * {@code accounts(id)}, and {@code accountusers.accountid} references it too. The CDM party master
 * writes the row <i>before</i> the CDM Party, so every CDM account always has its anchor row and
 * a failed row write leaves no CDM Party. Nothing serves data from these tables; the party master
 * reads them only to seed parties missing from the CDM store.
 *
 * <p>Column limits from {@code initialSchema.sql}: {@code accounts.displayname VARCHAR(50)},
 * {@code accountusers.username VARCHAR(15)}.
 */
public class AccountsTable {

  public static final int DISPLAY_NAME_MAX = 50;
  public static final int USERNAME_MAX = 15;

  private final JdbcTemplate jdbc;

  public AccountsTable(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  /** The next value of {@code accounts_seq}, as account-service drew new ids before 017. */
  public int nextId() {
    Integer id = jdbc.queryForObject("select nextval('accounts_seq')", Integer.class);
    if (id == null) {
      throw new IllegalStateException("accounts_seq returned no value");
    }
    return id;
  }

  public boolean exists(int id) {
    Integer count = jdbc.queryForObject("select count(*) from accounts where id = ?",
        Integer.class, id);
    return count != null && count > 0;
  }

  /** Inserts the row; false (nothing written) if a row with this id already exists. */
  public boolean insertIfAbsent(int id, String displayName) {
    if (exists(id)) {
      return false;
    }
    jdbc.update("insert into accounts (id, displayname) values (?, ?)", id, displayName);
    return true;
  }

  /** Sets the display name, inserting the row if absent; true if it was inserted. */
  public boolean upsert(int id, String displayName) {
    int updated = jdbc.update("update accounts set displayname = ? where id = ?", displayName, id);
    if (updated == 0) {
      jdbc.update("insert into accounts (id, displayname) values (?, ?)", id, displayName);
      return true;
    }
    return false;
  }

  /** Removes a row this master inserted, when the CDM write that should follow it failed. */
  public void delete(int id) {
    jdbc.update("delete from accounts where id = ?", id);
  }

  /** Inserts the account-user row if absent; true if it was inserted. */
  public boolean addUserIfAbsent(int accountId, String username) {
    Integer count = jdbc.queryForObject(
        "select count(*) from accountusers where accountid = ? and username = ?",
        Integer.class, accountId, username);
    if (count != null && count > 0) {
      return false;
    }
    jdbc.update("insert into accountusers (accountid, username) values (?, ?)", accountId,
        username);
    return true;
  }

  public void deleteUser(int accountId, String username) {
    jdbc.update("delete from accountusers where accountid = ? and username = ?", accountId,
        username);
  }
}
