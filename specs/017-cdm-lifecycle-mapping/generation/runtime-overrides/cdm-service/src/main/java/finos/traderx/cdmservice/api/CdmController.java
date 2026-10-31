package finos.traderx.cdmservice.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.cdmservice.party.PartyMapper;
import finos.traderx.cdmservice.party.PartyMaster;
import finos.traderx.cdmservice.security.SecurityMaster;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Full CDM objects as CDM (Rosetta) JSON. They are written with {@code cdmObjectMapper}
 * explicitly: Spring MVC's own mapper is Jackson 3 and does not know the CDM serialisation.
 */
@RestController
@RequestMapping("/cdm")
public class CdmController {

  private final SecurityMaster securityMaster;
  private final PartyMaster partyMaster;
  private final ObjectMapper cdmObjectMapper;

  public CdmController(SecurityMaster securityMaster, PartyMaster partyMaster,
      @Qualifier("cdmObjectMapper") ObjectMapper cdmObjectMapper) {
    this.securityMaster = securityMaster;
    this.partyMaster = partyMaster;
    this.cdmObjectMapper = cdmObjectMapper;
  }

  @GetMapping("/securities/{ticker}")
  public ResponseEntity<String> security(@PathVariable String ticker) {
    return securityMaster.findByTicker(ticker)
        .map(this::cdmJson)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  /**
   * The CDM Party of an account (by account id), with its related persons in {@code person}
   * and their roles in {@code personRole}; or the venue party for
   * {@code traderx-matching-engine}. The stored Party embeds identifier-only persons; the full
   * persons are resolved from the person master ({@code cdm:person:<logonId>}) at read time.
   */
  @GetMapping("/parties/{key}")
  public ResponseEntity<String> party(@PathVariable String key) {
    if (PartyMapper.VENUE_KEY.equals(key)) {
      return cdmJson(partyMaster.venue());
    }
    int accountId;
    try {
      accountId = Integer.parseInt(key);
    } catch (NumberFormatException e) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    return partyMaster.resolvedParty(accountId)
        .map(this::cdmJson)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
  }

  private ResponseEntity<String> cdmJson(Object cdmObject) {
    try {
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(cdmObjectMapper.writeValueAsString(cdmObject));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialise CDM object", e);
    }
  }
}
