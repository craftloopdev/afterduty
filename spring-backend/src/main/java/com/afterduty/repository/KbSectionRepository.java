package com.afterduty.repository;

import com.afterduty.model.KbSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface KbSectionRepository extends JpaRepository<KbSection, Long> {

    Optional<KbSection> findByPartAndSectionIdentifier(Integer part, String sectionIdentifier);

    /** Max watermark across all ingested sections — the nightly-diff short-circuit. */
    @Query("select max(s.lastIssueDate) from KbSection s")
    LocalDate findMaxLastIssueDate();
}
