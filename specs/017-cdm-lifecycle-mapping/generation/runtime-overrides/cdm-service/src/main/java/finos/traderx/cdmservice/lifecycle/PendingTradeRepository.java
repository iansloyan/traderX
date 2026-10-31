package finos.traderx.cdmservice.lifecycle;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingTradeRepository extends JpaRepository<PendingTrade, String> {

  /** The trades parked for an order, in arrival order. */
  List<PendingTrade> findByOrderIdOrderByReceivedAtAscTradeIdAsc(String orderId);

  /** Every trade parked before {@code receivedBefore}, in arrival order. */
  List<PendingTrade> findByReceivedAtBeforeOrderByReceivedAtAscTradeIdAsc(Instant receivedBefore);
}
