package com.afterduty.service.llm;

import com.afterduty.model.Atom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mission 6b — proves {@link AtomCorpusRenderer} produces a BYTE-STABLE cached-corpus
 * block: the same LIVE atom set renders to identical bytes regardless of input order,
 * superseded atoms are excluded, and there is nothing volatile in the output (no
 * timestamps/UUIDs/running counts). This is the property that lets every per-condition
 * fan-out call in a run share one cache_control prefix.
 */
@Tag("regression")
class AtomCorpusRendererTest {

    private Atom atom(Long id, Long evidenceId, String type, String value, String source,
                      Double confidence, String timestamp, Long supersededBy) {
        Atom a = new Atom();
        a.setId(id);
        a.setEvidenceId(evidenceId);
        a.setType(type);
        a.setValue(value);
        a.setSource(source);
        a.setConfidence(confidence);
        a.setTimestamp(timestamp);
        a.setSupersededBy(supersededBy);
        return a;
    }

    private List<Atom> sampleLive() {
        List<Atom> atoms = new ArrayList<>();
        atoms.add(atom(3L, 10L, "medication", "prednisone 20mg", "doc-a", 0.9, "2021-03-02", null));
        atoms.add(atom(1L, 10L, "diagnosis", "asthma", "doc-a", 0.95, "2020-01-01", null));
        atoms.add(atom(2L, 11L, "symptom", "wheezing", "doc-b", 0.8, null, null));
        return atoms;
    }

    @Test
    void render_isByteStable_acrossInputOrder() {
        List<Atom> a = sampleLive();
        List<Atom> b = new ArrayList<>(a);
        Collections.reverse(b);              // different fetch order, same set
        Collections.shuffle(b, new java.util.Random(42));

        String renderedA = AtomCorpusRenderer.render(a);
        String renderedB = AtomCorpusRenderer.render(b);

        assertEquals(renderedA, renderedB,
                "the corpus block must be byte-identical for any permutation of the same LIVE atom set");
    }

    @Test
    void render_sortsByEvidenceIdThenAtomId() {
        String rendered = AtomCorpusRenderer.render(sampleLive());
        int posDiagnosis = rendered.indexOf("asthma");        // id 1, evidence 10
        int posMedication = rendered.indexOf("prednisone");   // id 3, evidence 10
        int posSymptom = rendered.indexOf("wheezing");        // id 2, evidence 11
        // evidence 10 group (ids 1 then 3) precedes evidence 11 (id 2).
        assertTrue(posDiagnosis < posMedication, "within evidence 10, atom id 1 before id 3");
        assertTrue(posMedication < posSymptom, "evidence 10 atoms before evidence 11 atoms");
    }

    @Test
    void render_excludesSupersededAtoms() {
        List<Atom> atoms = sampleLive();
        atoms.add(atom(99L, 10L, "diagnosis", "STALE asthma copy", "doc-a", 0.5, null, 7L)); // superseded
        String rendered = AtomCorpusRenderer.render(atoms);
        assertFalse(rendered.contains("STALE asthma copy"),
                "superseded atoms must never appear in the cached corpus");
    }

    @Test
    void render_carriesNoRunningCount_soBlockIsStableAsCorpusGrows() {
        // The header must not embed a count-that-changes; the volatile per-condition
        // tail states the count instead. A count here would invalidate the prefix
        // whenever the corpus size changes between runs.
        String rendered = AtomCorpusRenderer.render(sampleLive());
        assertTrue(rendered.startsWith(AtomCorpusRenderer.CORPUS_HEADER));
        assertFalse(AtomCorpusRenderer.CORPUS_HEADER.matches(".*\\d.*"),
                "the corpus header must contain no digits (no embedded count)");
    }

    @Test
    void render_emptyLiveSet_isStableSentinel() {
        String empty = AtomCorpusRenderer.render(List.of());
        String allSuperseded = AtomCorpusRenderer.render(List.of(
                atom(1L, 1L, "diagnosis", "x", "s", 0.5, null, 9L)));
        assertEquals(empty, allSuperseded, "no-live-atoms renders the same stable sentinel either way");
        assertTrue(empty.contains("no evidence atoms"));
    }

    @Test
    void render_confidenceFormatIsLocaleStable() {
        // %.2f under Locale.ROOT always uses a '.' decimal separator, so a locale with
        // a ',' separator can't perturb the cached bytes.
        String rendered = AtomCorpusRenderer.render(List.of(
                atom(1L, 1L, "diagnosis", "asthma", "doc", 0.9, null, null)));
        assertTrue(rendered.contains("confidence: 0.90"), rendered);
        assertFalse(rendered.contains("0,90"));
    }
}
