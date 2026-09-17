package com.afterduty.service.kb;

import java.util.List;

/**
 * The 38 CFR Part 3 backbone sections ingested as KB chunks (Increment 7, spec §C.3).
 *
 * <p>The hand-structured eligibility rules stay in {@code PresumptiveRulesService} —
 * Inc 7 does NOT rewrite that engine. What is new here is ingesting the <b>regulatory
 * text</b> of these sections (as {@code kb_source=presumptives} chunks with cfr_section
 * + as_of_date) so chat can quote and cite the actual rule text with a freshness date.
 *
 * <p>M21-1 is explicitly deferred (§C.4) — the schema is ready for it but chat must not
 * claim M21-1 grounding.
 */
public final class PresumptiveIngest {

    private PresumptiveIngest() {}

    /** The Part-3 allowlist (service connection, presumptions, secondary, claims handling). */
    public static final List<String> SECTIONS = List.of(
            "3.303",   // principles relating to service connection
            "3.304",   // direct service connection; wartime and peacetime
            "3.306",   // aggravation of preservice disability
            "3.307",   // presumptive service connection — chronic, tropical, POW, herbicide
            "3.309",   // disease subject to presumptive service connection
            "3.310",   // disabilities that are proximately due to / aggravated by (secondary)
            "3.311",   // claims based on exposure to ionizing radiation
            "3.316",   // claims based on chronic effects of exposure to mustard gas / lewisite
            "3.317",   // compensation for certain disabilities (Gulf War / undiagnosed illness)
            "3.318",   // presumptive service connection for ALS
            "3.320",   // presumptive service connection for certain diseases (PACT Act backbone)
            "3.156",   // new and material evidence
            "3.159"    // department of veterans affairs assistance in developing claims
    );
}
