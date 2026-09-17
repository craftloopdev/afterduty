package com.afterduty.service.extraction;

/**
 * Document-type-specific extraction prompts for Gemini.
 * Each prompt is tailored to maximize extraction quality for that document type.
 */
public final class DocumentTypePrompts {

    private DocumentTypePrompts() {}

    private static final String SELF_CHECK = """

            SELF-CHECK before returning:
            - Did you extract ALL medications with dose, frequency, and dates?
            - Did you extract functional limitations (how conditions affect daily life)?
            - Did you skip normal lab values?
            - For corticosteroids, did you create a SEPARATE atom for each prescription from each source document?
            If any check fails, re-scan the document and add missing atoms.
            """;

    private static final String HEALTH_SUMMARY_PROMPT = """
            You are extracting medical evidence from a VA health summary document.

            EXTRACTION PRIORITIES (in order):
            1. MEDICATIONS: For EACH medication, extract: drug name, exact dosage, frequency (daily/BID/TID/PRN), route (oral/topical/inhaler/injection), prescriber, start date, end date or "ongoing", purpose/condition treated, active vs discontinued. Create SEPARATE atoms for each medication.

            2. DIAGNOSES & CONDITIONS: For each: condition name, ICD-10 code if present, date diagnosed, diagnosing provider, severity (mild/moderate/severe), status (active/resolved/chronic), functional limitations caused by this condition.

            3. SYMPTOMS: For each: description, frequency (daily/weekly/monthly/constant), severity (1-10 scale), triggers, impact on daily activities.

            4. FUNCTIONAL LIMITATIONS: What the veteran CANNOT do or struggles with because of their conditions. Impact on work, social life, daily activities. Need for assistive devices.

            5. ABNORMAL LABS ONLY: Skip all normal lab values. For abnormal results: test name, value, reference range, date, clinical significance.

            6. VITAL SIGNS: Only extract if abnormal or relevant to a claimed condition.

            For CORTICOSTEROIDS (prednisone, methylprednisolone, dexamethasone): extract each prescription as a SEPARATE atom with the evidence source ID. Each unique prescription = one course. This count determines respiratory ratings.

            Return a JSON array of atoms. Each: {"type": "...", "value": "...", "source": "...", "confidence": 0-1}
            """ + SELF_CHECK;

    private static final String IMO_LETTER_PROMPT = """
            You are extracting evidence from a medical opinion letter (IMO/nexus letter) for a VA disability claim.

            This document contains expert medical opinions. Extract with maximum detail:

            1. PROVIDER CREDENTIALS: Name, specialty, license, board certifications
            2. CONDITIONS ADDRESSED: Each condition the letter discusses
            3. NEXUS OPINIONS: The exact opinion language - "at least as likely as not", "more likely than not", "directly caused by", "aggravated by". Capture the EXACT wording.
            4. REASONING CHAIN: How the provider connects service to condition. Each step in the logic.
            5. EVIDENCE CITED: What medical records, studies, or literature the provider references
            6. FUNCTIONAL LIMITATIONS: How the condition affects the veteran's daily life, work capacity, social functioning
            7. SEVERITY ASSESSMENT: Provider's assessment of condition severity
            8. TEMPORAL CONNECTION: Timeline linking service events to current condition

            For nexus statements, use type="nexus_statement" with the EXACT opinion language as the value.
            For functional limitations, use type="functional_limitation".

            Return a JSON array of atoms. Each: {"type": "...", "value": "...", "source": "...", "confidence": 0-1}
            """ + SELF_CHECK;

    private static final String DD214_PROMPT = """
            You are extracting military service information from a DD-214 (Certificate of Release or Discharge from Active Duty).

            Extract EVERY field with maximum precision:
            1. SERVICE MEMBER: Full name, SSN (last 4 only), DOB, branch of service
            2. SERVICE DATES: Date entered active duty, separation date, total active service time
            3. RANK: Pay grade and rank at separation
            4. MOS/RATING: Military Occupational Specialty, rating, or AFSC with description
            5. DEPLOYMENTS: Every deployment with location, dates, combat zone status
            6. AWARDS & DECORATIONS: Every award, medal, ribbon, badge listed
            7. DUTY STATIONS: Every station with dates
            8. DISCHARGE: Type of discharge, character of service, RE code, separation code
            9. COMBAT SERVICE: Any combat indicators, imminent danger pay, hostile fire pay
            10. EDUCATION: Military education completed

            For each atom, use type="service_record" and include the exact field name and value.

            Return a JSON array of atoms. Each: {"type": "service_record", "value": "...", "source": "...", "confidence": 0-1}
            """ + SELF_CHECK;

    private static final String DBQ_PROMPT = """
            You are extracting evidence from a VA Disability Benefits Questionnaire (DBQ).

            DBQs are structured medical evaluations. Extract:
            1. DIAGNOSIS: Confirmed diagnosis with ICD code
            2. SEVERITY: Provider's severity assessment
            3. FUNCTIONAL IMPACT: Impact on occupational functioning
            4. OBJECTIVE FINDINGS: Physical exam findings, measurements
            5. RANGE OF MOTION: All ROM measurements in degrees (flexion, extension, abduction, etc.)
            6. DIAGNOSTIC TESTS: Test results referenced
            7. FLARE-UPS: Frequency, duration, severity of flare-ups
            8. ASSISTIVE DEVICES: Any devices used (cane, brace, CPAP, inhaler, etc.)
            9. PROVIDER: Examining provider name and credentials

            For ROM measurements, use type="test_result" with exact degrees.
            For functional impact, use type="functional_limitation".

            Return a JSON array of atoms. Each: {"type": "...", "value": "...", "source": "...", "confidence": 0-1}
            """ + SELF_CHECK;

    private static final String PERSONAL_STATEMENT_PROMPT = """
            You are extracting evidence from a veteran's personal statement for a VA disability claim.

            Personal statements describe how conditions affect the veteran's life. Extract:
            1. SYMPTOMS DESCRIBED: Every symptom with frequency and severity
            2. FUNCTIONAL LIMITATIONS: What the veteran cannot do - work restrictions, daily activity limitations, social impact, need for help from others
            3. TIMELINE: When symptoms started, how they've progressed
            4. SERVICE CONNECTION: Events during service that the veteran links to current conditions
            5. IMPACT STATEMENTS: "I can no longer...", "I have difficulty...", "My condition prevents..."
            6. BUDDY STATEMENTS: If the statement references what others have observed

            Use type="functional_limitation" for impact on daily life.
            Use type="symptom" for described symptoms.
            Use type="statement" for the veteran's own words about service connection.

            Return a JSON array of atoms. Each: {"type": "...", "value": "...", "source": "...", "confidence": 0-1}
            """ + SELF_CHECK;

    /**
     * Returns the document-type-specific extraction prompt.
     *
     * @param documentType one of: health_summary, imo_letter, dd214, dbq, personal_statement, default
     * @return the specialized system prompt
     */
    public static String getPromptForDocumentType(String documentType) {
        return switch (documentType) {
            case "health_summary" -> HEALTH_SUMMARY_PROMPT;
            case "imo_letter" -> IMO_LETTER_PROMPT;
            case "dd214" -> DD214_PROMPT;
            case "dbq" -> DBQ_PROMPT;
            case "personal_statement" -> PERSONAL_STATEMENT_PROMPT;
            default -> null; // caller falls back to EXTRACTION_SYSTEM_PROMPT
        };
    }
}
