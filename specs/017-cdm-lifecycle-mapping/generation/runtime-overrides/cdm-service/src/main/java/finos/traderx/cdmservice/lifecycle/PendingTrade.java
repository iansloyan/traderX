package finos.traderx.cdmservice.lifecycle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code cdm_pending_trades}: a booked trade that arrived before its order's approved
 * proposal was stored. {@link LifecycleAdapter} replays these in arrival order when the order's
 * {@code NEW} event stores the approval. The table is owned by
 * postgres-init/initialSchema.sql.
 */
@Entity
@Table(name = "cdm_pending_trades")
public class PendingTrade {

  @Id
  @Column(name = "trade_id", length = 100, nullable = false, updatable = false)
  private String tradeId;

  @Column(name = "order_id", length = 100, nullable = false, updatable = false)
  private String orderId;

  /** The booked trade as received, in the 009 {@code Trade} JSON shape. */
  @Column(name = "payload", nullable = false, updatable = false)
  private String payload;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "received_at", nullable = false, updatable = false)
  private Instant receivedAt;

  /** When the sweep recorded this entry as stale (a failure row), or null; recorded once. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "reported_at")
  private Instant reportedAt;

  protected PendingTrade() {
  }

  public PendingTrade(String tradeId, String orderId, String payload, Instant receivedAt) {
    this.tradeId = tradeId;
    this.orderId = orderId;
    this.payload = payload;
    this.receivedAt = receivedAt;
  }

  public String getTradeId() {
    return tradeId;
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
