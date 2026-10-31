package finos.traderx.cdmservice.lifecycle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code cdm_pending_cancels}: an order's {@code CANCELED} event that arrived before
 * every fill the matcher made was booked (the matcher's {@code /orders} and the trade
 * processor's {@code /trades/booked} are separate streams). {@link LifecycleAdapter} stores the
 * cancel step once the order's executions add up to {@code quantity - remainingQuantity}, so
 * the cancel stays the last step of the chain. The table is owned by
 * postgres-init/initialSchema.sql.
 */
@Entity
@Table(name = "cdm_pending_cancels")
public class PendingCancel {

  @Id
  @Column(name = "order_id", length = 100, nullable = false, updatable = false)
  private String orderId;

  /** The order event as received, in the 009 {@code OrderResponse} JSON shape. */
  @Column(name = "payload", nullable = false, updatable = false)
  private String payload;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "received_at", nullable = false, updatable = false)
  private Instant receivedAt;

  /** When the sweep recorded this entry as stale (a failure row), or null; recorded once. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "reported_at")
  private Instant reportedAt;

  protected PendingCancel() {
  }

  public PendingCancel(String orderId, String payload, Instant receivedAt) {
    this.orderId = orderId;
    this.payload = payload;
    this.receivedAt = receivedAt;
  }

  public String getOrderId() {
    return orderId;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public Instant getReportedAt() {
    return reportedAt;
  }

  public void setReportedAt(Instant reportedAt) {
    this.reportedAt = reportedAt;
  }
}
