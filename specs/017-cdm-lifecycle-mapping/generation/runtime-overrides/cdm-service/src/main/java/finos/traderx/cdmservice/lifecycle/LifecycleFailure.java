package finos.traderx.cdmservice.lifecycle;

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
 * Row of {@code cdm_lifecycle_failures}: a lifecycle event that could not be (fully) turned
 * into stored CDM objects, kept so the CDM view can show it next to the ref. The table is owned
 * by postgres-init/initialSchema.sql.
 */
@Entity
@Table(name = "cdm_lifecycle_failures")
public class LifecycleFailure {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false, updatable = false)
  private Long id;

  /** The ref the event belongs to ({@code cdm:order:<id>}, {@code cdm:trade:<id>}), if known. */
  @Column(name = "cdm_ref", length = 160, updatable = false)
  private String cdmRef;

  /** Where it failed, e.g. {@code decode}, {@code order}, {@code execution}, {@code store}. */
  @Column(name = "stage", length = 32, nullable = false, updatable = false)
  private String stage;

  @Column(name = "message", nullable = false, updatable = false)
  private String message;

  /** The event as received (or as decoded), for diagnosis and manual replay. */
  @Column(name = "payload", updatable = false)
  private String payload;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "failed_at", nullable = false, updatable = false)
  private Instant failedAt;

  protected LifecycleFailure() {
  }

  public LifecycleFailure(String cdmRef, String stage, String message, String payload,
      Instant failedAt) {
    this.cdmRef = cdmRef;
    this.stage = stage;
    this.message = message;
    this.payload = payload;
    this.failedAt = failedAt;
  }

  public Long getId() {
    return id;
  }

  public String getCdmRef() {
    return cdmRef;
  }

  public String getStage() {
    return stage;
  }

  public String getMessage() {
    return message;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getFailedAt() {
    return failedAt;
  }
}
