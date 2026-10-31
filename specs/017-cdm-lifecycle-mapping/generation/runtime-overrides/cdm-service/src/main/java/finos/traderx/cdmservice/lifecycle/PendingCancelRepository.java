package finos.traderx.cdmservice.lifecycle;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingCancelRepository extends JpaRepository<PendingCancel, String> {

  /** Every cancel parked before {@code receivedBefore}. */
  List<PendingCancel> findByReceivedAtBefore(Instant receivedBefore);
}
