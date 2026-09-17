package com.afterduty.service.synthesis;

/**
 * S13 + S3: Condition-specific prompt templates with VASRD criteria injection.
 * Replaces generic one-size-fits-all prompts with specialized templates per
 * VASRD condition category, improving rating accuracy and gap analysis specificity.
 */
public final class ConditionPromptTemplates {

    private ConditionPromptTemplates() {
        // utility class
    }

    // ── VASRD Range Detection ──────────────────────────────────────────

    /**
     * Determine the VASRD condition category from a diagnostic code.
     *
     * @param vasrdCode e.g. "6602", "9411", "5260"
     * @return one of: "respiratory", "mental_health", "musculoskeletal",
     *         "digestive", "neurological", or "default"
     */
    public static String getVasrdRange(String vasrdCode) {
        if (vasrdCode == null || vasrdCode.isBlank()) return "default";

        int code;
        try {
            code = Integer.parseInt(vasrdCode.trim());
        } catch (NumberFormatException e) {
            return "default";
        }

        if (code >= 6600 && code <= 6899) return "respiratory";
        if (code >= 9200 && code <= 9440) return "mental_health";
        if (code >= 5000 && code <= 5299) return "musculoskeletal";
        if (code >= 7300 && code <= 7399) return "digestive";
        if (code >= 8000 && code <= 8999) return "neurological";
        return "default";
    }

    // ── Next Rating Threshold ──────────────────────────────────────────

    /**
     * Return the next valid VA rating level above the current one.
     * Valid levels: 0, 10, 20, 30, 40, 50, 60, 70, 80, 100.
     *
     * @param currentRating the veteran's current rating percentage
     * @return the next higher threshold, or 100 if already at max
     */
    public static int getNextRatingThreshold(int currentRating) {
        int[] thresholds = {0, 10, 20, 30, 40, 50, 60, 70, 80, 100};
        for (int t : thresholds) {
            if (t > currentRating) return t;
        }
        return 100;
    }

    // ── Rating Prompts ─────────────────────────────────────────────────

    /**
     * Return a condition-type-specific system prompt for rating assignment.
     * The prompt includes mandatory questions tailored to the condition category
     * and a placeholder for VASRD criteria injection.
     *
     * @param vasrdCode the VASRD diagnostic code
     * @return the system prompt string
     */
    public static String getRatingPrompt(String vasrdCode) {
        String range = getVasrdRange(vasrdCode);
        String code = vasrdCode != null ? vasrdCode.trim() : "unknown";

        return switch (range) {
            case "respiratory" -> RESPIRATORY_RATING_PROMPT.replace("{vasrdCode}", code);
            case "mental_health" -> MENTAL_HEALTH_RATING_PROMPT;
            case "musculoskeletal" -> MUSCULOSKELETAL_RATING_PROMPT.replace("{vasrdCode}", code);
            case "digestive" -> DIGESTIVE_RATING_PROMPT.replace("{vasrdCode}", code);
            case "neurological" -> NEUROLOGICAL_RATING_PROMPT.replace("{vasrdCode}", code);
            default -> DEFAULT_RATING_PROMPT.replace("{vasrdCode}", code);
        };
    }

    // ── Gap Prompts ────────────────────────────────────────────────────

    /**
     * Return a condition-type-specific gap analysis prompt that references
     * the next higher rating threshold.
     *
     * @param vasrdCode     the VASRD diagnostic code
     * @param currentRating the veteran's current estimated rating
     * @return the system prompt string for gap analysis
     */
    public static String getGapPrompt(String vasrdCode, int currentRating) {
        String range = getVasrdRange(vasrdCode);
        String code = vasrdCode != null ? vasrdCode.trim() : "unknown";
        int nextThreshold = getNextRatingThreshold(currentRating);

        String basePrompt = switch (range) {
            case "respiratory" -> RESPIRATORY_GAP_PROMPT;
            case "mental_health" -> MENTAL_HEALTH_GAP_PROMPT;
            case "musculoskeletal" -> MUSCULOSKELETAL_GAP_PROMPT;
            case "digestive" -> DIGESTIVE_GAP_PROMPT;
            case "neurological" -> NEUROLOGICAL_GAP_PROMPT;
            default -> DEFAULT_GAP_PROMPT;
        };

        return basePrompt
                .replace("{vasrdCode}", code)
                .replace("{currentRating}", String.valueOf(currentRating))
                .replace("{nextThreshold}", String.valueOf(nextThreshold));
    }

    // ════════════════════════════════════════════════════════════════════
    // RATING PROMPT TEMPLATES
    // ════════════════════════════════════════════════════════════════════

    private static final String RESPIRATORY_RATING_PROMPT = """
            You are rating a respiratory condition under 38 CFR \u00a7 4.97.

            MANDATORY QUESTIONS TO ANSWER:
            1. How many courses of systemic corticosteroids (prednisone, methylprednisolone, \
            dexamethasone) are documented? Count each unique prescription/evidence source as one course.
            2. Is a daily bronchodilator prescribed? (albuterol, levalbuterol)
            3. Are there FEV-1 pulmonary function test results? If so, what percentage of predicted?
            4. Is the veteran on continuous oxygen therapy?
            5. Any documented asthma attacks requiring emergency care?

            RATING THRESHOLDS FOR DC {vasrdCode}:
            {inject actual criteria from VASRD database}

            After answering each question, assign the rating. \
            Return JSON: {"estimated_rating": N, "rating_rationale": "...", "confidence": 0-1}
            Return ONLY the JSON object, no other text.
            """;

    private static final String MENTAL_HEALTH_RATING_PROMPT = """
            You are rating a mental health condition under 38 CFR \u00a7 4.130 General Rating Formula.

            MANDATORY QUESTIONS TO ANSWER:
            1. What is the overall level of occupational and social impairment?
            2. Are there documented GAF, PHQ-9, or PCL-5 scores? List them with dates.
            3. Is the veteran employed? If not, is it due to this condition?
            4. Are there documented panic attacks? How frequent?
            5. Is there documented suicidal ideation?
            6. Are there documented memory/concentration impairments?
            7. Is there documented neglect of personal hygiene?

            RATING THRESHOLDS:
            0% = diagnosed but symptoms not severe enough to interfere with occupational/social functioning
            10% = occupational/social impairment due to mild/transient symptoms
            30% = occasional decrease in work efficiency from depressed mood, anxiety, chronic sleep impairment
            50% = reduced reliability and productivity from flattened affect, panic attacks >1x/week, \
            difficulty understanding complex commands
            70% = deficiencies in most areas: work, school, family, judgment, thinking, mood
            100% = total occupational and social impairment: gross impairment in thought/communication, \
            persistent danger, inability to perform ADLs

            Return JSON: {"estimated_rating": N, "rating_rationale": "...", "confidence": 0-1}
            Return ONLY the JSON object, no other text.
            """;

    private static final String MUSCULOSKELETAL_RATING_PROMPT = """
            You are rating a musculoskeletal condition under 38 CFR \u00a7 4.71a.

            MANDATORY QUESTIONS:
            1. What is the measured range of motion? (flexion degrees, extension degrees)
            2. Are there documented flare-ups? How often and how severe?
            3. Is there painful motion? At what degree does pain begin?
            4. Are assistive devices used (cane, brace, wheelchair)?
            5. Is there ankylosis (frozen joint)?

            Consider DeLuca factors: pain, weakness, fatigability, incoordination during flare-ups.

            RATING THRESHOLDS FOR DC {vasrdCode}:
            {inject actual criteria}

            Return JSON: {"estimated_rating": N, "rating_rationale": "...", "confidence": 0-1}
            Return ONLY the JSON object, no other text.
            """;

    private static final String DIGESTIVE_RATING_PROMPT = """
            You are rating a digestive condition under 38 CFR \u00a7 4.114.

            MANDATORY QUESTIONS:
            1. Is there documented weight loss? How much?
            2. Are there episodes of hematemesis or melena?
            3. Is there documented anemia (hemoglobin/hematocrit values)?
            4. What is the frequency of symptoms (daily, weekly, monthly)?
            5. Are there documented substernal/epigastric pain episodes?

            RATING THRESHOLDS FOR DC {vasrdCode}:
            {inject actual criteria}

            Return JSON: {"estimated_rating": N, "rating_rationale": "...", "confidence": 0-1}
            Return ONLY the JSON object, no other text.
            """;

    private static final String NEUROLOGICAL_RATING_PROMPT = """
            You are rating a neurological condition under 38 CFR \u00a7 4.124a.

            MANDATORY QUESTIONS:
            1. For headaches: Are attacks prostrating? How frequent?
            2. For TBI: What are the cognitive, behavioral, and physical residuals?
            3. Is there documented loss of consciousness?
            4. Are there documented seizures?

            RATING THRESHOLDS FOR DC {vasrdCode}:
            {inject actual criteria}

            Return JSON: {"estimated_rating": N, "rating_rationale": "...", "confidence": 0-1}
            Return ONLY the JSON object, no other text.
            """;

    private static final String DEFAULT_RATING_PROMPT = """
            You are rating a VA disability condition.

            Assign a rating from: 0, 10, 20, 30, 40, 50, 60, 70, 80, 100

            RATING CRITERIA FOR DC {vasrdCode}:
            {inject actual criteria if available}

            Return JSON: {"estimated_rating": N, "rating_rationale": "...", "confidence": 0-1}
            Return ONLY the JSON object, no other text.
            """;

    // ════════════════════════════════════════════════════════════════════
    // GAP ANALYSIS PROMPT TEMPLATES
    // ════════════════════════════════════════════════════════════════════

    private static final String RESPIRATORY_GAP_PROMPT = """
            You are a VA disability claims evidence specialist analyzing a respiratory condition \
            under 38 CFR \u00a7 4.97, DC {vasrdCode}.

            Current rating: {currentRating}%. To reach {nextThreshold}%, the following evidence \
            thresholds must be met for respiratory conditions:
            - 10%: FEV-1 71-80% predicted, or FEV-1/FVC 71-80%
            - 30%: FEV-1 56-70% predicted, or daily inhalational/oral bronchodilator therapy
            - 60%: FEV-1 40-55% predicted, or at least 3 courses of systemic corticosteroids/year
            - 100%: FEV-1 <40% predicted, or more than one attack/week with episodes of respiratory failure, \
            or requires daily use of systemic corticosteroids or immuno-suppressive medications

            Identify which evidence for the {nextThreshold}% threshold is MISSING from the veteran's records.

            For each gap, provide:
            - type: (nexus_letter, buddy_statement, medical_record, c_and_p_exam, specialist_opinion, \
            treatment_record, medication_log)
            - description: SPECIFIC description referencing the VASRD criteria for {nextThreshold}%
            - priority: high/medium/low
            - impact: how this evidence would change the rating
            - vasrd_reference: which VASRD criteria this evidence addresses
            - action_steps: concrete steps the veteran should take to obtain this evidence

            Return ONLY a JSON array of gap objects, no other text.
            """;

    private static final String MENTAL_HEALTH_GAP_PROMPT = """
            You are a VA disability claims evidence specialist analyzing a mental health condition \
            under 38 CFR \u00a7 4.130.

            Current rating: {currentRating}%. To reach {nextThreshold}%, the following criteria apply:
            - 10%: occupational/social impairment due to mild or transient symptoms which decrease work \
            efficiency only during periods of significant stress
            - 30%: occasional decrease in work efficiency and intermittent inability to perform tasks \
            due to depressed mood, anxiety, suspiciousness, chronic sleep impairment, mild memory loss
            - 50%: reduced reliability and productivity due to flattened affect, circumstantial speech, \
            panic attacks more than once a week, difficulty understanding complex commands, impaired judgment
            - 70%: deficiencies in most areas (work, school, family relations, judgment, thinking, mood) \
            due to suicidal ideation, obsessional rituals, near-continuous panic, inability to maintain \
            effective relationships
            - 100%: total occupational and social impairment due to gross impairment in thought processes, \
            persistent delusions/hallucinations, persistent danger of hurting self or others, inability to \
            perform activities of daily living

            Identify which evidence for the {nextThreshold}% threshold is MISSING from the veteran's records.

            For each gap, provide:
            - type: (nexus_letter, buddy_statement, medical_record, c_and_p_exam, specialist_opinion, \
            treatment_record, medication_log)
            - description: SPECIFIC description referencing the General Rating Formula criteria for {nextThreshold}%
            - priority: high/medium/low
            - impact: how this evidence would change the rating
            - vasrd_reference: which criteria from 38 CFR \u00a7 4.130 this evidence addresses
            - action_steps: concrete steps the veteran should take to obtain this evidence

            Return ONLY a JSON array of gap objects, no other text.
            """;

    private static final String MUSCULOSKELETAL_GAP_PROMPT = """
            You are a VA disability claims evidence specialist analyzing a musculoskeletal condition \
            under 38 CFR \u00a7 4.71a, DC {vasrdCode}.

            Current rating: {currentRating}%. To reach {nextThreshold}%, the veteran needs to demonstrate \
            greater limitation of motion, additional functional loss during flare-ups (DeLuca factors), \
            or ankylosis at the next severity level.

            Key evidence for musculoskeletal upgrades:
            - Range of motion measurements showing limitation at the next threshold
            - Documentation of painful motion and at what degree pain begins
            - Flare-up frequency and severity with functional impact
            - DeLuca factors: pain, weakness, fatigability, incoordination causing additional ROM loss
            - Use of assistive devices (cane, brace, wheelchair)
            - Impact on daily activities and employment

            Identify which evidence for the {nextThreshold}% threshold is MISSING from the veteran's records.

            For each gap, provide:
            - type: (nexus_letter, buddy_statement, medical_record, c_and_p_exam, specialist_opinion, \
            treatment_record, medication_log)
            - description: SPECIFIC description referencing DC {vasrdCode} criteria for {nextThreshold}%
            - priority: high/medium/low
            - impact: how this evidence would change the rating
            - vasrd_reference: which VASRD criteria this evidence addresses
            - action_steps: concrete steps the veteran should take to obtain this evidence

            Return ONLY a JSON array of gap objects, no other text.
            """;

    private static final String DIGESTIVE_GAP_PROMPT = """
            You are a VA disability claims evidence specialist analyzing a digestive condition \
            under 38 CFR \u00a7 4.114, DC {vasrdCode}.

            Current rating: {currentRating}%. To reach {nextThreshold}%, the following evidence \
            thresholds apply for digestive conditions:
            - 10%: two or more of the symptoms required for 30% rating, of less severity
            - 30%: persistently recurrent epigastric distress with dysphagia, pyrosis, and \
            regurgitation, accompanied by substernal or arm or shoulder pain
            - 60%: symptoms of pain, vomiting, material weight loss, hematemesis or melena with \
            moderate anemia; or other symptom combinations productive of severe impairment of health

            Identify which evidence for the {nextThreshold}% threshold is MISSING from the veteran's records.

            For each gap, provide:
            - type: (nexus_letter, buddy_statement, medical_record, c_and_p_exam, specialist_opinion, \
            treatment_record, medication_log)
            - description: SPECIFIC description referencing DC {vasrdCode} criteria for {nextThreshold}%
            - priority: high/medium/low
            - impact: how this evidence would change the rating
            - vasrd_reference: which VASRD criteria this evidence addresses
            - action_steps: concrete steps the veteran should take to obtain this evidence

            Return ONLY a JSON array of gap objects, no other text.
            """;

    private static final String NEUROLOGICAL_GAP_PROMPT = """
            You are a VA disability claims evidence specialist analyzing a neurological condition \
            under 38 CFR \u00a7 4.124a, DC {vasrdCode}.

            Current rating: {currentRating}%. To reach {nextThreshold}%, the veteran needs to demonstrate \
            greater neurological impairment at the next severity level.

            Key evidence for neurological upgrades:
            - For headaches (8100): frequency and prostrating nature of attacks, economic impact
              30%=characteristic prostrating attacks averaging once/month, \
              50%=very frequent completely prostrating and prolonged attacks productive of severe economic inadaptability
            - For TBI: cognitive, behavioral, and physical residual severity levels
            - For seizures: frequency and severity documentation
            - Loss of consciousness episodes with duration
            - Nerve conduction studies and EMG results

            Identify which evidence for the {nextThreshold}% threshold is MISSING from the veteran's records.

            For each gap, provide:
            - type: (nexus_letter, buddy_statement, medical_record, c_and_p_exam, specialist_opinion, \
            treatment_record, medication_log)
            - description: SPECIFIC description referencing DC {vasrdCode} criteria for {nextThreshold}%
            - priority: high/medium/low
            - impact: how this evidence would change the rating
            - vasrd_reference: which VASRD criteria this evidence addresses
            - action_steps: concrete steps the veteran should take to obtain this evidence

            Return ONLY a JSON array of gap objects, no other text.
            """;

    private static final String DEFAULT_GAP_PROMPT = """
            You are a VA disability claims evidence specialist analyzing a condition rated under \
            DC {vasrdCode}.

            Current rating: {currentRating}%. To reach {nextThreshold}%, the veteran needs evidence \
            meeting the specific VASRD criteria for that threshold.

            Identify which evidence for the {nextThreshold}% threshold is MISSING from the veteran's records.

            For each gap, provide:
            - type: (nexus_letter, buddy_statement, medical_record, c_and_p_exam, specialist_opinion, \
            treatment_record, medication_log)
            - description: SPECIFIC description referencing the VASRD criteria for {nextThreshold}%
            - priority: high/medium/low
            - impact: how this evidence would change the rating
            - vasrd_reference: which VASRD criteria this evidence addresses
            - action_steps: concrete steps the veteran should take to obtain this evidence

            Return ONLY a JSON array of gap objects, no other text.
            """;
}
