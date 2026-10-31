package finos.traderx.cdmservice.store;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CdmDocumentRepository extends JpaRepository<CdmDocument, Long> {

  /** All versions under a ref (all types), in write order. */
  List<CdmDocument> findByCdmRefOrderByIdAsc(String cdmRef);

  /** The most recently written version of a type under a ref. */
  Optional<CdmDocument> findFirstByCdmRefAndCdmTypeOrderByIdDesc(String cdmRef, String cdmType);

  /** Every distinct ref that has at least one version of a type. */
  @Query("select distinct d.cdmRef from CdmDocument d where d.cdmType = :cdmType")
  List<String> findDistinctCdmRefsByCdmType(@Param("cdmType") String cdmType);

  /** The latest version of a type under every ref that has one, in write order. */
  @Query("select d from CdmDocument d where d.id in (select max(e.id) from CdmDocument e"
      + " where e.cdmType = :cdmType group by e.cdmRef) order by d.id")
  List<CdmDocument> findLatestByCdmType(@Param("cdmType") String cdmType);

  /** Every version of a type whose global key is one of {@code globalKeys}, in write order. */
  List<CdmDocument> findByCdmTypeAndGlobalKeyInOrderByIdAsc(String cdmType,
      Collection<String> globalKeys);

  /**
   * Validation counts over every distinct stored object version ({@code cdm_ref},
   * {@code cdm_type}, {@code global_key}), each with its latest result (a re-validation
   * supersedes the earlier result for the same content): rows of {@code [cdmType,
   * validationStatus, count]}.
   */
  @Query("select d.cdmType, d.validationStatus, count(d) from CdmDocument d where d.id in"
      + " (select max(e.id) from CdmDocument e group by e.cdmRef, e.cdmType, e.globalKey)"
      + " group by d.cdmType, d.validationStatus")
  List<Object[]> countObjectsByTypeAndValidationStatus();

  /**
   * Validation counts over the latest version per ref and type: rows of {@code [cdmType,
   * validationStatus, count]}.
   */
  @Query("select d.cdmType, d.validationStatus, count(d) from CdmDocument d where d.id in"
      + " (select max(e.id) from CdmDocument e group by e.cdmRef, e.cdmType)"
      + " group by d.cdmType, d.validationStatus")
  List<Object[]> countLatestByTypeAndValidationStatus();

  /** Distinct object versions whose latest validation result is not {@code VALID}. */
  @Query("select d from CdmDocument d where d.id in"
      + " (select max(e.id) from CdmDocument e group by e.cdmRef, e.cdmType, e.globalKey)"
      + " and d.validationStatus <> 'VALID' order by d.id")
  List<CdmDocument> findObjectsNotValid(Pageable page);
}
