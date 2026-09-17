package com.afterduty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PresumptiveRulesServiceTest {

    private PresumptiveRulesService service;

    @BeforeEach
    void setUp() {
        service = new PresumptiveRulesService();
    }

    @Test
    void iraqDeploymentMatchesPactAct() {
        Map<String, Object> profile = Map.of(
                "deployments", List.of(Map.of("location", "Iraq", "start", "2004-01", "end", "2005-01")),
                "exposure_risks", List.of(),
                "service_start", "2002-01-01",
                "service_end", "2006-01-01"
        );
        var matches = service.checkPresumptiveConnections(profile);
        assertFalse(matches.isEmpty());
        assertTrue(matches.stream().anyMatch(m -> m.get("category").contains("PACT Act")));
    }

    @Test
    void vietnamDeploymentMatchesAgentOrange() {
        Map<String, Object> profile = Map.of(
                "deployments", List.of(Map.of("location", "Vietnam", "start", "1968-01", "end", "1969-01")),
                "exposure_risks", List.of(),
                "service_start", "1967-01-01",
                "service_end", "1970-01-01"
        );
        var matches = service.checkPresumptiveConnections(profile);
        assertFalse(matches.isEmpty());
        assertTrue(matches.stream().anyMatch(m -> m.get("category").contains("Agent Orange")));
        assertTrue(matches.stream().anyMatch(m -> m.get("condition").equals("Diabetes Mellitus Type 2")));
    }

    @Test
    void gulfWarDeploymentMatchesGulfWarPresumptives() {
        Map<String, Object> profile = Map.of(
                "deployments", List.of(Map.of("location", "Kuwait")),
                "exposure_risks", List.of(),
                "service_start", "1991-01-01",
                "service_end", "1992-01-01"
        );
        var matches = service.checkPresumptiveConnections(profile);
        assertFalse(matches.isEmpty());
        assertTrue(matches.stream().anyMatch(m -> m.get("category").contains("Gulf War")));
        assertTrue(matches.stream().anyMatch(m -> m.get("condition").equals("Fibromyalgia")));
    }

    @Test
    void burnPitExposureRiskMatches() {
        Map<String, Object> profile = Map.of(
                "deployments", List.of(),
                "exposure_risks", List.of("burn pit"),
                "service_start", "2005-01-01",
                "service_end", "2010-01-01"
        );
        var matches = service.checkPresumptiveConnections(profile);
        assertFalse(matches.isEmpty());
        assertTrue(matches.stream().anyMatch(m -> m.get("category").contains("Burn Pit")));
    }

    @Test
    void noMatchForUnrelatedService() {
        Map<String, Object> profile = Map.of(
                "deployments", List.of(Map.of("location", "Germany")),
                "exposure_risks", List.of(),
                "service_start", "2010-01-01",
                "service_end", "2014-01-01"
        );
        var matches = service.checkPresumptiveConnections(profile);
        assertTrue(matches.isEmpty());
    }

    @Test
    void dateFilterExcludesWrongEra() {
        // Vietnam-era location but service dates outside Agent Orange window
        Map<String, Object> profile = Map.of(
                "deployments", List.of(Map.of("location", "Vietnam")),
                "exposure_risks", List.of(),
                "service_start", "2000-01-01",
                "service_end", "2004-01-01"
        );
        var matches = service.checkPresumptiveConnections(profile);
        // Should NOT match Agent Orange (1962-1975 window)
        assertFalse(matches.stream().anyMatch(m -> m.get("category").contains("Agent Orange")));
    }

    @Test
    void emptyProfileReturnsNoMatches() {
        Map<String, Object> profile = Map.of(
                "deployments", List.of(),
                "exposure_risks", List.of(),
                "service_start", "",
                "service_end", ""
        );
        var matches = service.checkPresumptiveConnections(profile);
        assertTrue(matches.isEmpty());
    }

    @Test
    void matchIncludesVasrdCode() {
        Map<String, Object> profile = Map.of(
                "deployments", List.of(Map.of("location", "Iraq")),
                "exposure_risks", List.of(),
                "service_start", "2003-01-01",
                "service_end", "2007-01-01"
        );
        var matches = service.checkPresumptiveConnections(profile);
        assertTrue(matches.stream().allMatch(m -> m.containsKey("vasrd_code") && !m.get("vasrd_code").isEmpty()));
    }

    // -------------------------------------------------------------------------
    // Increment B — deriveServiceProfileFromText (atom-text derivation)
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void deriveServiceProfileFindsIraqAndBurnPitFromAtomText() {
        Map<String, Object> derived = service.deriveServiceProfileFromText(List.of(
                "Deployment orders: OIF, deployed to Iraq 2004-2005",
                "Post-deployment health assessment notes chronic burn pit smoke exposure at FOB",
                "Follow-up pulmonary visit for wheezing"
        ));

        List<Map<String, Object>> deployments = (List<Map<String, Object>>) derived.get("deployments");
        List<String> exposures = (List<String>) derived.get("exposure_risks");

        // Canonical casing from the rules list preserved ("Iraq", not "iraq").
        assertTrue(deployments.stream().anyMatch(d -> "Iraq".equals(d.get("location"))));
        assertTrue(exposures.contains("burn pit"));
        // Provisional inference — dates deliberately empty so the date gate stays permissive.
        assertEquals("", derived.get("service_start"));
        assertEquals("", derived.get("service_end"));
    }

    @Test
    void derivedIraqBurnPitTextMatchesAsthma6602UnderPactBurnPit() {
        Map<String, Object> derived = service.deriveServiceProfileFromText(List.of(
                "Veteran served in Iraq and reports significant burn pit exposure."
        ));
        var matches = service.checkPresumptiveConnections(derived);

        assertFalse(matches.isEmpty());
        assertTrue(matches.stream().anyMatch(m ->
                "6602".equals(m.get("vasrd_code")) && m.get("basis").contains("Burn Pit")),
                "expected asthma 6602 under a PACT burn-pit basis, got: " + matches);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deriveServiceProfileFromUnrelatedTextYieldsNoDeploymentsOrExposuresAndNoMatch() {
        Map<String, Object> derived = service.deriveServiceProfileFromText(List.of(
                "Annual dental cleaning, no issues noted.",
                "Routine PT test passed at Fort Benning."
        ));

        assertTrue(((List<Map<String, Object>>) derived.get("deployments")).isEmpty());
        assertTrue(((List<String>) derived.get("exposure_risks")).isEmpty());
        assertTrue(service.checkPresumptiveConnections(derived).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deriveServiceProfileIsNullSafeAndDeduplicates() {
        Map<String, Object> derived = service.deriveServiceProfileFromText(java.util.Arrays.asList(
                null,
                "",
                "Iraq deployment record",
                "Second Iraq mention should not duplicate"
        ));
        List<Map<String, Object>> deployments = (List<Map<String, Object>>) derived.get("deployments");
        assertEquals(1, deployments.stream().filter(d -> "Iraq".equals(d.get("location"))).count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void locationMatchIsWordBoundaryNotSubstring() {
        // "Oman" must NOT match inside "Romania", nor "Syria" inside "Assyria" —
        // a false-positive presumptive flag is damaging even when provisional.
        Map<String, Object> derived = service.deriveServiceProfileFromText(List.of(
                "Studied ancient Assyria; visited a museum in Romania on leave."
        ));
        assertTrue(((List<Map<String, Object>>) derived.get("deployments")).isEmpty(),
                "substring-only tokens (Oman in Romania, Syria in Assyria) must not match");

        // But a real word-bounded mention still matches.
        Map<String, Object> real = service.deriveServiceProfileFromText(List.of("Deployed to Oman in 2004."));
        assertEquals(1, ((List<Map<String, Object>>) real.get("deployments")).stream()
                .filter(d -> "Oman".equals(d.get("location"))).count());
    }
}
