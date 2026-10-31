package finos.traderx.cdmservice.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code cdm_documents}. The table is owned by postgres-init/initialSchema.sql; this
 * mapping is only validated against it, never used to create it.
 */
@Entity
@Table(name = "cdm_documents")
public class CdmDocument {

  /** Surrogate key (BIGSERIAL); also the write order of versions. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false, updatable = false)
  private Long id;

  /** SHA-256 of the canonical JSON; not unique (content may recur, or repeat across refs). */
  @Column(name = "global_key", length = 128, nullable = false, updatable = false)
  private String globalKey;

  @Column(name = "cdm_ref", length = 160, nullable = false, updatable = false)
  private String cdmRef;

  @Column(name = "cdm_type", length = 128, nullable = false, updatable = false)
  private String cdmType;

  /** Postgres TEXT (reported as VARCHAR by both pgjdbc and H2 in PostgreSQL mode). */
  @Column(name = "payload", nullable = false, updatable = false)
  private String payload;

  @Column(name = "previous_global_key", length = 128, updatable = false)
  private String previousGlobalKey;

  @Column(name = "validation_status", length = 16, nullable = false, updatable = false)
  private String validationStatus;

  /** JSON array of strings, or {@code null} when there are no messages. */
  @Column(name = "validation_messages", updatable = false)
  private String validationMessages;

  /** Ref of the object that produced this one (a trade state's accepted step), or null. */
  @Column(name = "produced_by_ref", length = 160, updatable = false)
  private String producedByRef;

  /** Store global key of the object that produced this one, or null. */
  @Column(name = "produced_by_global_key", length = 128, updatable = false)
  private String producedByGlobalKey;

  /** Stored as a zone-less TIMESTAMP in UTC (hibernate.jdbc.time_zone=UTC). */
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected CdmDocument() {
  }

  public CdmDocument(String globalKey, String cdmRef, String cdmType, String payload,
      String previousGlobalKey, String validationStatus, String validationMessages,
      Instant createdAt) {
    this(globalKey, cdmRef, cdmType, payload, previousGlobalKey, validationStatus,
        validationMessages, createdAt, null, null);
  }

  public CdmDocument(String globalKey, String cdmRef, String cdmType, String payload,
      String previousGlobalKey, String validationStatus, String validationMessages,
      Instant createdAt, String producedByRef, String producedByGlobalKey) {
    this.globalKey = globalKey;
    this.cdmRef = cdmRef;
    this.cdmType = cdmType;
    this.payload = payload;
    this.previousGlobalKey = previousGlobalKey;
    this.validationStatus = validationStatus;
    this.validationMessages = validationMessages;
    this.createdAt = createdAt;
    this.producedByRef = producedByRef;
    this.producedByGlobalKey = producedByGlobalKey;
  }

  public Long getId() {
    return id;
  }

  public String getGlobalKey() {
    return globalKey;
  }

  public String getCdmRef() {
    return cdmRef;
  }

  public String getCdmType() {
    return cdmType;
  }

  public String getPayload() {
    return payload;
  }

  public String getPreviousGlobalKey() {
    return previousGlobalKey;
  }

  public String getValidationStatus() {
    return validationStatus;
  }

  public String getValidationMessages() {
    return validationMessages;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getProducedByRef() {
    return producedByRef;
  }

  public String getProducedByGlobalKey() {
    return producedByGlobalKey;
  }
}
