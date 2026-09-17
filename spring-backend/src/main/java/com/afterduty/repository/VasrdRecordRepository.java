package com.afterduty.repository;

import com.afterduty.model.VasrdRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VasrdRecordRepository extends JpaRepository<VasrdRecord, Long> {

    /** All rating tiers of a diagnostic code, in schedule order (the vasrd_lookup path). */
    List<VasrdRecord> findByDcCodeOrderByDisplayOrder(String dcCode);

    /** Re-ingest of a section: clear its structured rows before re-inserting. */
    void deleteByCfrSection(String cfrSection);

    /** One row per diagnostic code — the schedule stores one per rating tier. */
    interface CodeTitle {
        String getDcCode();
        String getTitle();
    }

    /**
     * Distinct diagnostic codes within a body system, for the gap prompt's
     * sibling block. Collapses the per-tier rows a code owns.
     */
    @Query("SELECT DISTINCT r.dcCode AS dcCode, r.title AS title FROM VasrdRecord r "
            + "WHERE LOWER(r.bodySystem) = LOWER(:system) ORDER BY r.dcCode")
    List<CodeTitle> findDistinctCodesInBodySystem(@Param("system") String system);
}
