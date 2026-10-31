package finos.traderx.cdmservice.lifecycle;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LifecycleFailureRepository extends JpaRepository<LifecycleFailure, Long> {

  /** The failures recorded for a ref, oldest first (for the CDM view). */
  List<LifecycleFailure> findByCdmRefOrderByIdAsc(String cdmRef);
}
