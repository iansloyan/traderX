package finos.traderx.cdmservice.party;

import cdm.base.staticdata.party.Account;
import cdm.base.staticdata.party.AccountTypeEnum;
import cdm.base.staticdata.party.ContactInformation;
import cdm.base.staticdata.party.NaturalPerson;
import cdm.base.staticdata.party.NaturalPersonRole;
import cdm.base.staticdata.party.NaturalPersonRoleEnum;
import cdm.base.staticdata.party.Party;
import cdm.base.staticdata.party.PartyIdentifier;
import cdm.base.staticdata.party.PersonIdentifier;
import cdm.base.staticdata.party.PersonIdentifierTypeEnum;
import cdm.base.staticdata.party.metafields.FieldWithMetaPersonIdentifier;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaNaturalPerson;
import cdm.base.staticdata.party.metafields.ReferenceWithMetaParty;
import com.rosetta.model.metafields.FieldWithMetaString;
import com.rosetta.model.metafields.MetaFields;
import finos.traderx.cdmservice.ref.CdmRef;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps TraderX accounts, account users and persons to CDM {@link Party} and
 * {@link NaturalPerson}, and back to the shapes account-service serves.
 *
 * <ul>
 *   <li><b>Account</b> = {@link Party} with one {@link PartyIdentifier} (the account id, scheme
 *       {@link #ACCOUNT_ID_SCHEME}), {@code name} = display name and {@code meta.externalKey} =
 *       account id. Its {@code account} ({@link Account}) has the account id as
 *       {@code accountNumber}, the display name as {@code accountName}, type {@code Client}, and
 *       as {@code servicingParty} the TraderX subsidiary that faces the client (by external key),
 *       so the facing entity is recorded in CDM's own field.</li>
 *   <li><b>Subsidiary</b> = {@link Party} for each TraderX group entity that faces clients
 *       ({@link EntitySeed}), identified under {@link #ENTITY_ID_SCHEME}; the dealer side of every
 *       order and trade.</li>
 *   <li><b>Account user</b> = an identifier-only person ({@link #identifiedPerson}: logon id
 *       identifier and external key) in {@code Party.person} plus a {@code Party.personRole}
 *       ({@link NaturalPersonRole}) whose {@code personReference} is the person's external key
 *       (the logon id) and whose role is {@link NaturalPersonRoleEnum#TRADER}. The full person
 *       lives only in {@code cdm:person:<logonId>} (Ruling R19). Persons and roles are kept
 *       sorted by logon id, so the same set of users is always the same document.</li>
 *   <li><b>Person</b> = {@link NaturalPerson} with {@code meta.externalKey} = logon id and
 *       person identifiers: the logon id (scheme {@link #LOGON_ID_SCHEME}), the employee id
 *       ({@link PersonIdentifierTypeEnum#EMPL}) and the department (scheme
 *       {@link #DEPARTMENT_SCHEME}, a TraderX extension: CDM has no person department) and the
 *       photo URL (scheme {@link #PHOTO_URL_SCHEME}, a TraderX extension: CDM has no person
 *       photo).
 *       {@code fullName} is split at its first space into {@code firstName} and
 *       {@code surname}, and joined back with one space, so it round-trips exactly.</li>
 * </ul>
 */
public final class PartyMapper {

  public static final String ACCOUNT_ID_SCHEME = "https://traderx.finos.org/account-id";
  public static final String LOGON_ID_SCHEME = "https://traderx.finos.org/logon-id";
  public static final String DEPARTMENT_SCHEME = "https://traderx.finos.org/department";
  public static final String PHOTO_URL_SCHEME = "https://traderx.finos.org/photo-url";
  public static final String ENTITY_ID_SCHEME = "https://traderx.finos.org/entity-id";
  public static final String VENUE_ID_SCHEME = "https://traderx.finos.org/venue-id";
  public static final String VENUE_KEY = "traderx-matching-engine";
  public static final String VENUE_NAME = "TraderX Matching Engine";

  private PartyMapper() {
  }

  public static CdmRef partyRef(int accountId) {
    return new CdmRef(CdmRef.Kind.PARTY, Integer.toString(accountId));
  }

  public static CdmRef venueRef() {
    return new CdmRef(CdmRef.Kind.PARTY, VENUE_KEY);
  }

  public static CdmRef entityRef(String entityKey) {
    return new CdmRef(CdmRef.Kind.PARTY, entityKey);
  }

  public static CdmRef personRef(String logonId) {
    return new CdmRef(CdmRef.Kind.PERSON, logonId);
  }

  /** The account party with no related persons. */
  public static Party toCdmParty(int accountId, String displayName) {
    return toCdmParty(accountId, displayName, List.of());
  }

  /** The account party with {@code persons} related to it as traders and no servicing entity. */
  public static Party toCdmParty(int accountId, String displayName, List<NaturalPerson> persons) {
    return toCdmParty(accountId, displayName, persons, null);
  }

  /**
   * The account party with {@code persons} related to it as traders and, if
   * {@code servicingEntity} is given, its {@link Account} serviced by that subsidiary.
   */
  public static Party toCdmParty(int accountId, String displayName, List<NaturalPerson> persons,
      String servicingEntity) {
    String key = Integer.toString(accountId);
    Party.PartyBuilder party = Party.builder()
        .addPartyId(PartyIdentifier.builder()
            .setIdentifier(schemed(key, ACCOUNT_ID_SCHEME)))
        .setMeta(MetaFields.builder().setExternalKey(key));
    if (displayName != null) {
      party.setName(FieldWithMetaString.builder().setValue(displayName));
    }
    List<NaturalPerson> sorted = persons.stream()
        .sorted(Comparator.comparing(PartyMapper::logonId))
        .toList();
    for (NaturalPerson person : sorted) {
      party.addPerson(person);
      party.addPersonRole(NaturalPersonRole.builder()
          .setPersonReference(ReferenceWithMetaNaturalPerson.builder()
              .setExternalReference(logonId(person)))
          .addRoleValue(NaturalPersonRoleEnum.TRADER));
    }
    if (servicingEntity != null) {
      party.setAccount(account(key, displayName, servicingEntity));
    }
    return party.build();
  }

  /** {@code party} (an account party) serviced by {@code servicingEntity}. */
  public static Party withServicingEntity(Party party, String servicingEntity) {
    String name = party.getName() == null ? null : party.getName().getValue();
    return party.toBuilder()
        .setAccount(account(Integer.toString(accountId(party)), name, servicingEntity))
        .build();
  }

  /** The external key of the subsidiary servicing an account party, if it has one. */
  public static Optional<String> servicingEntity(Party party) {
    return Optional.ofNullable(party)
        .map(Party::getAccount)
        .map(Account::getServicingParty)
        .map(ReferenceWithMetaParty::getExternalReference);
  }

  private static Account account(String accountId, String displayName, String servicingEntity) {
    Account.AccountBuilder account = Account.builder()
        .setAccountNumber(schemed(accountId, ACCOUNT_ID_SCHEME))
        .setAccountTypeValue(AccountTypeEnum.CLIENT)
        .setServicingParty(ReferenceWithMetaParty.builder()
            .setExternalReference(servicingEntity));
    if (displayName != null) {
      account.setAccountNameValue(displayName);
    }
    return account.build();
  }

  /** A TraderX group subsidiary as a party (TraderX scheme only, no LEI). */
  public static Party entityParty(EntitySeed.Entity entity) {
    return Party.builder()
        .addPartyId(PartyIdentifier.builder()
            .setIdentifier(schemed(entity.key(), ENTITY_ID_SCHEME)))
        .setName(FieldWithMetaString.builder().setValue(entity.name()))
        .setMeta(MetaFields.builder().setExternalKey(entity.key()))
        .build();
  }

  /** True for a party that represents a TraderX group subsidiary. */
  public static boolean isEntity(Party party) {
    return partyIdentifier(party, ENTITY_ID_SCHEME).isPresent();
  }

  /** The execution venue (the TraderX order matcher) as a party. */
  public static Party venueParty() {
    return Party.builder()
        .addPartyId(PartyIdentifier.builder().setIdentifier(schemed(VENUE_KEY, VENUE_ID_SCHEME)))
        .setName(FieldWithMetaString.builder().setValue(VENUE_NAME))
        .setMeta(MetaFields.builder().setExternalKey(VENUE_KEY))
        .build();
  }

  /** A person without a photo URL. */
  public static NaturalPerson toCdmPerson(String logonId, String fullName, String email,
      String employeeId, String department) {
    return toCdmPerson(logonId, fullName, email, employeeId, department, null);
  }

  /** A person with every people-service directory field ({@link PersonProjection}). */
  public static NaturalPerson toCdmPerson(String logonId, String fullName, String email,
      String employeeId, String department, String photoUrl) {
    NaturalPerson.NaturalPersonBuilder person = identifiedPersonBuilder(logonId);
    if (employeeId != null) {
      person.addPersonId(FieldWithMetaPersonIdentifier.builder().setValue(PersonIdentifier.builder()
          .setIdentifier(FieldWithMetaString.builder().setValue(employeeId))
          .setIdentifierType(PersonIdentifierTypeEnum.EMPL)));
    }
    if (department != null) {
      person.addPersonId(personIdentifier(department, DEPARTMENT_SCHEME));
    }
    if (photoUrl != null) {
      person.addPersonId(personIdentifier(photoUrl, PHOTO_URL_SCHEME));
    }
    if (fullName != null) {
      int space = fullName.indexOf(' ');
      if (space < 0) {
        person.setFirstName(fullName);
      } else {
        person.setFirstName(fullName.substring(0, space));
        person.setSurname(fullName.substring(space + 1));
      }
    }
    if (email != null) {
      person.setContactInformation(ContactInformation.builder().addEmail(email));
    }
    return person.build();
  }

  /**
   * A person known only by logon id. Account parties embed these (the person master holds the
   * full record), and a username without a people-service record (which account-service's
   * {@code PUT /accountuser/} has always allowed) is stored in the person master as one.
   */
  public static NaturalPerson identifiedPerson(String logonId) {
    return identifiedPersonBuilder(logonId).build();
  }

  /** The people-service view of a person (inverse of {@link #toCdmPerson}). */
  public static PersonProjection toPersonProjection(NaturalPerson person) {
    String logonId = logonId(person);
    return new PersonProjection(logonId, fullName(person), email(person), employeeId(person),
        department(person), photoUrl(person), personRef(logonId).toString());
  }

  /**
   * True for a person with full directory details (name, email and employee id), as every
   * people.json person has; false for an identifier-only person (Ruling R20).
   */
  public static boolean isDirectoryPerson(NaturalPerson person) {
    return fullName(person) != null && email(person) != null && employeeId(person) != null;
  }

  /**
   * The account projection of an account party; {@code entityName} gives the name of its
   * servicing subsidiary by external key (empty if unknown).
   */
  public static AccountProjection toAccountProjection(Party party,
      java.util.function.Function<String, Optional<String>> entityName) {
    int id = accountId(party);
    String name = party.getName() == null ? null : party.getName().getValue();
    String servicing = servicingEntity(party).orElse(null);
    return new AccountProjection(id, name, partyRef(id).toString(),
        servicing == null ? null : entityRef(servicing).toString(),
        servicing == null ? null : entityName.apply(servicing).orElse(servicing));
  }

  /** One projection per related person, sorted by username. */
  public static List<AccountUserProjection> toAccountUserProjections(Party party) {
    int id = accountId(party);
    return relatedLogonIds(party).stream()
        .map(logonId -> new AccountUserProjection(id, logonId, personRef(logonId).toString()))
        .toList();
  }

  /** The logon ids of the persons with a role on {@code party}, sorted. */
  public static List<String> relatedLogonIds(Party party) {
    if (party.getPersonRole() == null) {
      return List.of();
    }
    return party.getPersonRole().stream()
        .map(NaturalPersonRole::getPersonReference)
        .filter(Objects::nonNull)
        .map(ReferenceWithMetaNaturalPerson::getExternalReference)
        .filter(Objects::nonNull)
        .sorted()
        .toList();
  }

  /** The TraderX account id of an account party. */
  public static int accountId(Party party) {
    return partyIdentifier(party, ACCOUNT_ID_SCHEME)
        .map(Integer::parseInt)
        .orElseThrow(() -> new IllegalArgumentException("Party has no " + ACCOUNT_ID_SCHEME
            + " identifier"));
  }

  /** True for a party that represents a TraderX account (not the venue). */
  public static boolean isAccount(Party party) {
    return partyIdentifier(party, ACCOUNT_ID_SCHEME).isPresent();
  }

  public static String logonId(NaturalPerson person) {
    return personIdentifierValue(person, LOGON_ID_SCHEME, null)
        .orElseThrow(() -> new IllegalArgumentException("NaturalPerson has no logon id"));
  }

  public static String fullName(NaturalPerson person) {
    if (person.getFirstName() == null) {
      return person.getSurname();
    }
    return person.getSurname() == null ? person.getFirstName()
        : person.getFirstName() + " " + person.getSurname();
  }

  public static String email(NaturalPerson person) {
    ContactInformation contact = person.getContactInformation();
    if (contact == null || contact.getEmail() == null || contact.getEmail().isEmpty()) {
      return null;
    }
    return contact.getEmail().get(0);
  }

  public static String employeeId(NaturalPerson person) {
    return personIdentifierValue(person, null, PersonIdentifierTypeEnum.EMPL).orElse(null);
  }

  public static String department(NaturalPerson person) {
    return personIdentifierValue(person, DEPARTMENT_SCHEME, null).orElse(null);
  }

  public static String photoUrl(NaturalPerson person) {
    return personIdentifierValue(person, PHOTO_URL_SCHEME, null).orElse(null);
  }

  private static NaturalPerson.NaturalPersonBuilder identifiedPersonBuilder(String logonId) {
    if (logonId == null || logonId.isBlank()) {
      throw new IllegalArgumentException("logonId is required");
    }
    return NaturalPerson.builder()
        .addPersonId(personIdentifier(logonId, LOGON_ID_SCHEME))
        .setMeta(MetaFields.builder().setExternalKey(logonId));
  }

  private static FieldWithMetaPersonIdentifier personIdentifier(String value, String scheme) {
    return FieldWithMetaPersonIdentifier.builder()
        .setValue(PersonIdentifier.builder().setIdentifier(schemed(value, scheme)))
        .build();
  }

  private static FieldWithMetaString schemed(String value, String scheme) {
    return FieldWithMetaString.builder()
        .setValue(value)
        .setMeta(MetaFields.builder().setScheme(scheme))
        .build();
  }

  private static Optional<String> partyIdentifier(Party party, String scheme) {
    if (party == null || party.getPartyId() == null) {
      return Optional.empty();
    }
    return party.getPartyId().stream()
        .map(PartyIdentifier::getIdentifier)
        .filter(id -> id != null && id.getMeta() != null
            && scheme.equals(id.getMeta().getScheme()))
        .map(FieldWithMetaString::getValue)
        .findFirst();
  }

  private static Optional<String> personIdentifierValue(NaturalPerson person, String scheme,
      PersonIdentifierTypeEnum type) {
    if (person == null || person.getPersonId() == null) {
      return Optional.empty();
    }
    return person.getPersonId().stream()
        .map(FieldWithMetaPersonIdentifier::getValue)
        .filter(Objects::nonNull)
        .filter(id -> id.getIdentifier() != null)
        .filter(id -> type == null || id.getIdentifierType() == type)
        .filter(id -> scheme == null || (id.getIdentifier().getMeta() != null
            && scheme.equals(id.getIdentifier().getMeta().getScheme())))
        .map(id -> id.getIdentifier().getValue())
        .findFirst();
  }
}
