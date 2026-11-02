package finos.traderx.cdmservice.lifecycle;

import cdm.base.staticdata.asset.common.Security;
import cdm.base.staticdata.identifier.AssignedIdentifier;
import cdm.base.staticdata.identifier.Identifier;
import cdm.base.staticdata.party.Counterparty;
import cdm.base.staticdata.party.CounterpartyRoleEnum;
import cdm.base.staticdata.party.EntityIdentifier;
import cdm.base.staticdata.party.EntityIdentifierTypeEnum;
import cdm.base.staticdata.party.LegalEntity;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyRole;
import cdm.base.staticdata.party.PartyRoleEnum;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import cdm.base.datetime.TimeZone;
import cdm.event.common.ExecutionDetails;
import cdm.event.common.ExecutionInstruction;
import cdm.event.common.ExecutionTypeEnum;
import cdm.event.common.TradeIdentifier;
import cdm.event.workflow.EventTimestamp;
import cdm.event.workflow.EventTimestampQualificationEnum;
import cdm.observable.asset.PriceQuantity;
import cdm.product.template.NonTransferableProduct;
import com.regnosys.rosetta.common.hashing.GlobalKeyProcessStep;
import com.regnosys.rosetta.common.hashing.NonNullHashCollector;
import com.rosetta.model.lib.RosettaModelObject;
import com.rosetta.model.lib.RosettaModelObjectBuilder;
import com.rosetta.model.lib.records.Date;
import com.rosetta.model.metafields.FieldWithMetaDate;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import finos.traderx.cdmservice.party.PartyMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Building blocks shared by {@link WorkflowMapper} and {@link ExecutionMapper}. */
public final class CdmLifecycle {

  /** Issuer of every TraderX identifier on CDM lifecycle objects. */
  static final String ISSUER = "TraderX";
  static final String ORDER_ID_SCHEME = "https://traderx.finos.org/order-id";
  static final String TRADE_ID_SCHEME = "https://traderx.finos.org/trade-id";
  static final String REJECTION_ID_SCHEME = "https://traderx.finos.org/rejection-id";
  /** Time zone of every TraderX time (TraderX records instants; CDM dates are taken in UTC). */
  static final String UTC = "UTC";

  private static final GlobalKeyProcessStep GLOBAL_KEYS =
      new GlobalKeyProcessStep(NonNullHashCollector::new);

  private CdmLifecycle() {
  }

  /**
   * {@code object} with CDM global keys ({@code meta.globalKey}) set throughout, computed by the
   * Rune runtime's {@link GlobalKeyProcessStep} from the content. Keys are what CDM references
   * ({@code previousWorkflowStep}, {@code as-key}) point at, and are deterministic.
   */
  @SuppressWarnings("unchecked")
  static <T extends RosettaModelObject> T keyed(T object) {
    RosettaModelObjectBuilder builder = object.toBuilder();
    GLOBAL_KEYS.runProcessStep(builder.getType(), builder);
    return (T) builder.build();
  }

  /** A TraderX identifier ({@code issuer} TraderX, the value with its id scheme). */
  static Identifier identifier(String value, String scheme, int version) {
    return Identifier.builder()
        .setIssuerValue(ISSUER)
        .addAssignedIdentifier(AssignedIdentifier.builder()
            .setIdentifier(schemed(value, scheme))
            .setVersion(version))
        .build();
  }

  static TradeIdentifier tradeIdentifier(String value, String scheme) {
    return TradeIdentifier.builder()
        .setIssuerValue(ISSUER)
        .addAssignedIdentifier(AssignedIdentifier.builder().setIdentifier(schemed(value, scheme)))
        .build();
  }

  /** The value of the first identifier with {@code scheme}, or {@code null}. */
  static String identifierValue(List<? extends Identifier> identifiers, String scheme) {
    if (identifiers == null) {
      return null;
    }
    return identifiers.stream()
        .filter(id -> id.getAssignedIdentifier() != null)
        .flatMap(id -> id.getAssignedIdentifier().stream())
        .map(AssignedIdentifier::getIdentifier)
        .filter(id -> id != null && id.getMeta() != null && scheme.equals(id.getMeta().getScheme()))
        .map(FieldWithMetaString::getValue)
        .findFirst()
        .orElse(null);
  }

  static EventTimestamp timestamp(Instant at, EventTimestampQualificationEnum qualification) {
    return EventTimestamp.builder()
        .setDateTime(ZonedDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC))
        .setQualification(qualification)
        .build();
  }

  /** The UTC date of {@code at}, as a CDM date. */
  static Date date(Instant at) {
    return Date.of(LocalDate.ofInstant(at, ZoneOffset.UTC));
  }

  static TimeZone time(Instant at) {
    return TimeZone.builder()
        .setTime(at.atOffset(ZoneOffset.UTC).toLocalTime().truncatedTo(ChronoUnit.MILLIS))
        .setLocationValue(UTC)
        .build();
  }

  /** The dealer (the facing TraderX subsidiary) is {@code Party1} of every order and trade. */
  public static final CounterpartyRoleEnum DEALER = CounterpartyRoleEnum.PARTY_1;
  /** The client (the TraderX account) is {@code Party2} of every order and trade. */
  public static final CounterpartyRoleEnum CLIENT = CounterpartyRoleEnum.PARTY_2;

  /** The external key of the counterparty with {@code role}, or null. */
  public static String counterpartyKey(List<? extends Counterparty> counterparties,
      CounterpartyRoleEnum role) {
    return counterparties == null ? null : counterparties.stream()
        .filter(c -> c.getRole() == role)
        .map(Counterparty::getPartyReference)
        .filter(java.util.Objects::nonNull)
        .map(ReferenceWithMetaParty::getExternalReference)
        .findFirst().orElse(null);
  }

  /** The external key of the first party with {@code role} in {@code roles}, or null. */
  public static String roleKey(List<? extends PartyRole> roles, PartyRoleEnum role) {
    return roles == null ? null : roles.stream()
        .filter(r -> r.getRole() == role)
        .map(PartyRole::getPartyReference)
        .filter(java.util.Objects::nonNull)
        .map(ReferenceWithMetaParty::getExternalReference)
        .findFirst().orElse(null);
  }

  /** A reference to {@code party} by its external key (account id, subsidiary or venue key). */
  static ReferenceWithMetaParty partyReference(Party party) {
    return ReferenceWithMetaParty.builder().setExternalReference(externalKey(party)).build();
  }

  static String externalKey(Party party) {
    if (party.getMeta() == null || party.getMeta().getExternalKey() == null) {
      throw new IllegalArgumentException("party has no external key: " + party);
    }
    return party.getMeta().getExternalKey();
  }

  /**
   * The party as a party to a trade: identifiers, name and external key only. The account
   * users ({@code person}/{@code personRole}) are not copied: they are entitlements on the
   * account, kept by the party master, and would make every trade change when a user is added.
   */
  static Party tradeParty(Party party) {
    return Party.builder()
        .setPartyId(party.getPartyId())
        .setName(party.getName())
        .setMeta(MetaFields.builder().setExternalKey(externalKey(party)))
        .build();
  }

  /** The venue as CDM's {@code ExecutionDetails.executionVenue} (a {@link LegalEntity}). */
  static LegalEntity venueEntity() {
    return LegalEntity.builder()
        .setNameValue(PartyMapper.VENUE_NAME)
        .addEntityIdentifier(EntityIdentifier.builder()
            .setIdentifier(schemed(PartyMapper.VENUE_KEY, PartyMapper.VENUE_ID_SCHEME))
            .setIdentifierType(EntityIdentifierTypeEnum.OTHER))
        .build();
  }

  /**
   * The execution instruction of an order, a fill or a direct trade: the cash-equity product,
   * one price/quantity, the dealer (the facing TraderX subsidiary, {@code Party1}) against the
   * client (the account, {@code Party2}) as principals, buyer and seller from the client's
   * side, the client in role {@code Client} and the dealer in roles {@code ExecutingEntity} and
   * {@code BookingParty}; when a venue is given, the venue as a third party in role
   * {@code ExecutionFacility} and as {@code executionVenue} (never a counterparty); the
   * execution type, the trade date and time and the TraderX identifiers.
   *
   * @param venue         the matcher for an order and its fills; null for a direct trade
   * @param executionType {@code OnVenue} for an order and its fills (sent to and executed by
   *                      the TraderX matching engine); {@code OffFacility} for a direct trade
   *                      (booked bilaterally from the trade ticket, no venue)
   */
  static ExecutionInstruction executionInstruction(NonTransferableProduct product, Side side,
      Party client, Party dealer, Party venue, PriceQuantity priceQuantity, Instant at,
      List<TradeIdentifier> tradeIdentifiers, ExecutionTypeEnum executionType) {
    Party clientParty = tradeParty(client);
    Party dealerParty = tradeParty(dealer);
    ExecutionDetails.ExecutionDetailsBuilder details = ExecutionDetails.builder()
        .setExecutionType(executionType);
    if (venue != null) {
      details.setExecutionVenue(venueEntity());
    }
    ExecutionInstruction.ExecutionInstructionBuilder instruction = ExecutionInstruction.builder()
        .setProduct(product)
        .addPriceQuantity(priceQuantity)
        .addCounterparty(Counterparty.builder()
            .setRole(DEALER)
            .setPartyReference(partyReference(dealerParty)))
        .addCounterparty(Counterparty.builder()
            .setRole(CLIENT)
            .setPartyReference(partyReference(clientParty)))
        .addParties(dealerParty)
        .addParties(clientParty)
        .setExecutionDetails(details)
        .setTradeDate(FieldWithMetaDate.builder()
            .setValue(date(at))
            .setMeta(MetaFields.builder().setExternalKey(CdmProducts.TRADE_DATE_KEY)))
        .setTradeTimeValue(time(at))
        .addTradeIdentifier(tradeIdentifiers);
    if (side != null) {
      instruction
          .addPartyRoles(role(side == Side.BUY ? clientParty : dealerParty, PartyRoleEnum.BUYER))
          .addPartyRoles(role(side == Side.BUY ? dealerParty : clientParty, PartyRoleEnum.SELLER));
    }
    instruction
        .addPartyRoles(role(clientParty, PartyRoleEnum.CLIENT))
        .addPartyRoles(role(dealerParty, PartyRoleEnum.EXECUTING_ENTITY))
        .addPartyRoles(role(dealerParty, PartyRoleEnum.BOOKING_PARTY));
    if (venue != null) {
      Party venueParty = tradeParty(venue);
      instruction.addParties(venueParty)
          .addPartyRoles(role(venueParty, PartyRoleEnum.EXECUTION_FACILITY));
    }
    return instruction.build();
  }

  static Security requireSecurity(LifecycleReferenceData data, String ticker) {
    return data.security(ticker).orElseThrow(() ->
        new IllegalArgumentException("unknown security: " + ticker));
  }

  static Party requireAccount(LifecycleReferenceData data, int accountId) {
    return data.account(accountId).orElseThrow(() ->
        new IllegalArgumentException("unknown account: " + accountId));
  }

  static Side requireSide(String side) {
    Side parsed = Side.parse(side);
    if (parsed == null) {
      throw new IllegalArgumentException("side is required");
    }
    return parsed;
  }

  private static PartyRole role(Party party, PartyRoleEnum role) {
    return PartyRole.builder().setPartyReference(partyReference(party)).setRole(role).build();
  }

  static FieldWithMetaString schemed(String value, String scheme) {
    return FieldWithMetaString.builder()
        .setValue(value)
        .setMeta(MetaFields.builder().setScheme(scheme))
        .build();
  }
}
