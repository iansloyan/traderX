package finos.traderx.cdmservice.api;

import finos.traderx.cdmservice.party.AccountProjection;
import finos.traderx.cdmservice.party.AccountUserProjection;
import finos.traderx.cdmservice.party.PartyMaster;
import finos.traderx.cdmservice.party.PersonProjection;
import finos.traderx.cdmservice.security.SecurityMapper;
import finos.traderx.cdmservice.security.SecurityMaster;
import finos.traderx.cdmservice.security.StockProjection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple projections of CDM masters, in the shapes the existing TraderX services serve, plus
 * {@code cdmRef}. These are plain records serialised by Spring MVC.
 *
 * <p>Until a master is completely seeded, its endpoints answer 503 rather than an empty or
 * partial answer. A failed write to the CDM store is also 503: the TraderX caller sees "CDM
 * unavailable", never a store exception.
 */
@RestController
@RequestMapping("/projections")
public class ProjectionController {

  private static final Logger log = LoggerFactory.getLogger(ProjectionController.class);

  private final SecurityMaster securityMaster;
  private final PartyMaster partyMaster;

  public ProjectionController(SecurityMaster securityMaster, PartyMaster partyMaster) {
    this.securityMaster = securityMaster;
    this.partyMaster = partyMaster;
  }

  /** Body of account writes: {@code id} is optional on POST (absent or <= 0 means "assign"). */
  public record AccountWrite(Integer id, String displayName) {
  }

  /** Body of an account-user write. */
  public record AccountUserWrite(Integer accountId, String username) {
  }

  @GetMapping("/stocks")
  public ResponseEntity<List<StockProjection>> stocks() {
    if (!securityMaster.isReady()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    return ResponseEntity.ok(securityMaster.all().stream().map(SecurityMapper::toProjection).toList());
  }

  @GetMapping("/stocks/{ticker}")
  public ResponseEntity<StockProjection> stock(@PathVariable String ticker) {
    if (!securityMaster.isReady()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    return securityMaster.findByTicker(ticker)
        .map(SecurityMapper::toProjection)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  @GetMapping("/accounts")
  public List<AccountProjection> accounts() {
    return partyMaster.accounts();
  }

  @GetMapping("/accounts/{id}")
  public ResponseEntity<AccountProjection> account(@PathVariable int id) {
    return partyMaster.findAccount(id)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  /** Creates an account (new id from {@code accounts_seq}), or upserts the given id. */
  @PostMapping("/accounts")
  public AccountProjection createAccount(@RequestBody AccountWrite body) {
    if (body.id() == null || body.id() <= 0) {
      return partyMaster.createAccount(body.displayName());
    }
    return partyMaster.updateAccount(body.id(), body.displayName());
  }

  /** Upserts account {@code id}. */
  @PutMapping("/accounts/{id}")
  public AccountProjection updateAccount(@PathVariable int id, @RequestBody AccountWrite body) {
    return partyMaster.updateAccount(id, body.displayName());
  }

  @GetMapping("/accountusers")
  public List<AccountUserProjection> accountUsers(
      @RequestParam(name = "accountId", required = false) Integer accountId) {
    return partyMaster.accountUsers(accountId);
  }

  /** Relates a user to an account (404 if the account does not exist). */
  @PostMapping("/accountusers")
  public AccountUserProjection addAccountUser(@RequestBody AccountUserWrite body) {
    if (body.accountId() == null) {
      throw new PartyMaster.UnknownAccountException(0);
    }
    if (body.username() == null || body.username().isBlank()) {
      throw new IllegalArgumentException("username is required");
    }
    return partyMaster.addUser(body.accountId(), body.username());
  }

  /**
   * The person directory (people-service's projection): all directory persons by logon id, or
   * those whose logon id or full name contains {@code search}, or the one with
   * {@code employeeId} (a list of zero or one). Identifier-only persons are never listed.
   */
  @GetMapping("/people")
  public List<PersonProjection> people(
      @RequestParam(name = "search", required = false) String search,
      @RequestParam(name = "employeeId", required = false) String employeeId) {
    if (employeeId != null) {
      return partyMaster.findPersonByEmployeeId(employeeId).stream().toList();
    }
    if (search != null) {
      return partyMaster.searchPeople(search);
    }
    return partyMaster.people();
  }

  /** One directory person (404 if unknown or identifier-only). */
  @GetMapping("/people/{logonId}")
  public ResponseEntity<PersonProjection> person(@PathVariable String logonId) {
    return partyMaster.findDirectoryPerson(logonId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  @ExceptionHandler(PartyMaster.NotReadyException.class)
  public ResponseEntity<String> notReady(PartyMaster.NotReadyException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getMessage());
  }

  @ExceptionHandler(PartyMaster.UnknownAccountException.class)
  public ResponseEntity<String> unknownAccount(PartyMaster.UnknownAccountException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
  }

  @ExceptionHandler(PartyMaster.AccountIdConflictException.class)
  public ResponseEntity<String> idConflict(PartyMaster.AccountIdConflictException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
  }

  /** Invalid input (too long, missing, malformed JSON, wrong type): the caller's fault, 400. */
  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
      TypeMismatchException.class})
  public ResponseEntity<String> badRequest(Exception e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
  }

  /**
   * Any other failure (typically the CDM store or database) is "CDM unavailable", except Spring
   * MVC's own request errors, which keep their status (e.g. 400, 405, 415).
   */
  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<String> unavailable(RuntimeException e) {
    if (e instanceof ErrorResponse mvcError) {
      return ResponseEntity.status(mvcError.getStatusCode()).body(e.getMessage());
    }
    log.error("CDM projection request failed", e);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("CDM service unavailable");
  }
}
