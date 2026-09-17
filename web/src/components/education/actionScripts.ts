// Per-gap-type action scripts (P1-18, $0 model spend). STATIC, veteran-plain
// "exactly what to do" guidance keyed by the pipeline's gap-type tokens (the
// prompt enum in EvidenceGapAnalyzer). The steps UI consumes this map to turn
// an abstract gap ("nexus_letter") into concrete instructions: what to do, who
// to ask, which form, and what it typically costs. Nothing here is
// LLM-derived — costs/times are conservative public ranges, not promises.
//
// `gapActionScript()` also accepts the HUMANIZED display strings the backend's
// `/claim/gaps` endpoint emits ("Nexus letter", "C&P exam request",
// "Independent medical opinion") and the legacy synthesis tokens
// ("medical_record", "c_and_p_exam", "Nexus", "In-Service"), so callers can
// pass `StepVM.type` as-is.

/** The gap-type enum the live 3-stage gap pipeline emits. */
export type GapTypeToken =
  | "nexus_letter"
  | "imo_independent_medical_opinion"
  | "c_and_p_exam_request"
  | "buddy_statement"
  | "treatment_record"
  | "medication_log"
  | "specialist_opinion"
  | "imaging_study"
  | "lab_test"
  | "pharmacy_record"
  | "service_record"
  | "personal_statement"
  | "presumptive_documentation";

export interface GapActionScript {
  token: GapTypeToken;
  /** Human title matching the backend's humanized display string. */
  title: string;
  /** What this evidence is, in plain words. */
  what: string;
  /** Exactly what to do, in order. */
  steps: string[];
  /** Who to ask. */
  whoToAsk: string;
  /** The specific form, when one exists. */
  form: string | null;
  /** Typical out-of-pocket cost, stated honestly. */
  typicalCost: string;
  /** Typical time to obtain. */
  typicalTime: string;
}

export const GAP_ACTION_SCRIPTS: Record<GapTypeToken, GapActionScript> = {
  nexus_letter: {
    token: "nexus_letter",
    title: "Nexus letter",
    what:
      "A short letter from a doctor saying your condition is at least as likely as not " +
      "connected to your service.",
    steps: [
      "Ask the doctor who already treats this condition — they know your history best.",
      "Bring the service records that mention the injury, event, or symptoms.",
      "Ask them to use the phrase “at least as likely as not” and to explain their reasoning.",
      "Upload the signed letter here when you have it.",
    ],
    whoToAsk:
      "Your treating doctor or specialist first. If they won't write one, private " +
      "nexus-letter services exist — for a fee.",
    form: null,
    typicalCost: "Often free from your own doctor; private services commonly charge $800–$2,000",
    typicalTime: "2–4 weeks",
  },
  imo_independent_medical_opinion: {
    token: "imo_independent_medical_opinion",
    title: "Independent medical opinion",
    what:
      "A deeper written review by a doctor who hasn't treated you, weighing your whole " +
      "file — used when a treating doctor's letter isn't enough.",
    steps: [
      "Start with your treating doctor — a nexus letter from them is often enough and costs less.",
      "If you truly need an independent review, look for a physician who does VA disability opinions.",
      "Send them your complete records so the opinion cites specifics.",
      "Upload the signed opinion here when you have it.",
    ],
    whoToAsk: "A physician who writes VA independent medical opinions (many work remotely).",
    form: null,
    typicalCost: "Commonly $1,500–$3,000 — ask your treating doctor first",
    typicalTime: "4–8 weeks",
  },
  c_and_p_exam_request: {
    token: "c_and_p_exam_request",
    title: "C&P exam request",
    what:
      "A Compensation and Pension (C&P) exam is VA's own medical check for your claim. " +
      "VA schedules it and pays for it.",
    steps: [
      "You don't book this yourself — VA orders it after you file your claim.",
      "If you filed and haven't heard anything, call VA at 800-827-1000 to check status.",
      "Read the “Getting ready for your C&P exam” guide before you go.",
      "At the exam, be honest about your worst days — don't minimize.",
    ],
    whoToAsk: "VA schedules it. Your job is to show up prepared.",
    form: null,
    typicalCost: "Free — VA pays",
    typicalTime: "Usually 30–60 days after filing",
  },
  buddy_statement: {
    token: "buddy_statement",
    title: "Buddy statement",
    what:
      "A written statement from someone who saw what happened in service, or who sees " +
      "how your condition affects you now.",
    steps: [
      "Pick someone who actually saw it — a battle buddy, spouse, family member, or coworker.",
      "Ask them to write plainly what they saw, when, and how often.",
      "Have them put it on VA Form 21-10210 (Lay/Witness Statement) and sign it.",
      "Upload the signed statement here.",
    ],
    whoToAsk: "A service buddy, family member, friend, or coworker who witnessed it.",
    form: "VA Form 21-10210",
    typicalCost: "Free",
    typicalTime: "1–2 weeks",
  },
  treatment_record: {
    token: "treatment_record",
    title: "Treatment record",
    what: "Medical records showing you've been seen and treated for this condition.",
    steps: [
      "Ask each clinic or hospital that treated this condition for your records.",
      "Use your patient portal (MyChart and similar) to download visit notes as PDFs.",
      "For VA care, download records with My HealtheVet's Blue Button.",
      "Upload every page that names the condition.",
    ],
    whoToAsk: "Your clinic's medical-records office, or your patient portal.",
    form: null,
    typicalCost: "Free through portals; paper copies can carry a small fee",
    typicalTime: "Same day to 2 weeks",
  },
  medication_log: {
    token: "medication_log",
    title: "Medication log",
    what:
      "A record of the medicines you take for this condition — it shows the condition " +
      "is ongoing and treated.",
    steps: [
      "Print your prescription history from your pharmacy or patient portal.",
      "Ask the pharmacist for a printout going back as far as they have.",
      "Upload it here.",
    ],
    whoToAsk: "Your pharmacy counter or patient portal.",
    form: null,
    typicalCost: "Free",
    typicalTime: "Same day",
  },
  specialist_opinion: {
    token: "specialist_opinion",
    title: "Specialist opinion",
    what:
      "A written note from a specialist (like a cardiologist, audiologist, or " +
      "psychiatrist) about your diagnosis and how serious it is.",
    steps: [
      "Ask your primary doctor for a referral to the right specialist.",
      "At the visit, describe your worst days honestly — don't tough it out.",
      "Ask the specialist for a written summary of the diagnosis and severity.",
      "Upload the summary here.",
    ],
    whoToAsk: "The specialist treating your condition, or your primary doctor for a referral.",
    form: null,
    typicalCost: "Your normal visit cost or copay",
    typicalTime: "2–6 weeks, depending on scheduling",
  },
  imaging_study: {
    token: "imaging_study",
    title: "Imaging study",
    what: "An X-ray, MRI, or CT report that documents the condition.",
    steps: [
      "Ask your doctor whether imaging would document this condition.",
      "If you've already had a scan, request the written radiology report from your portal or the imaging center.",
      "Upload the written report — you don't need the image files themselves.",
    ],
    whoToAsk: "Your doctor, or the imaging center's records office.",
    form: null,
    typicalCost: "Report copies are free; a new scan costs your normal visit or copay",
    typicalTime: "Existing report: same day to 1 week. New scan: 2–4 weeks",
  },
  lab_test: {
    token: "lab_test",
    title: "Lab test",
    what: "Blood work or other lab results that support the diagnosis.",
    steps: [
      "Ask your doctor which lab result would document this condition.",
      "Download past results from your patient portal, or request them from the lab.",
      "Upload the result pages here.",
    ],
    whoToAsk: "Your doctor or your patient portal.",
    form: null,
    typicalCost: "Copies are free; new tests cost your normal visit or copay",
    typicalTime: "Same day to 1 week",
  },
  pharmacy_record: {
    token: "pharmacy_record",
    title: "Pharmacy record",
    what: "An official pharmacy printout of the prescriptions you've filled.",
    steps: [
      "Ask your pharmacy for a full prescription-history printout.",
      "Most chains can print it at the counter the same day.",
      "Upload it here.",
    ],
    whoToAsk: "Your pharmacy counter.",
    form: null,
    typicalCost: "Free",
    typicalTime: "Same day",
  },
  service_record: {
    token: "service_record",
    title: "Service record",
    what:
      "Service records — like your DD-214 or service treatment records — that show " +
      "what happened during your service.",
    steps: [
      "Find your DD-214 — check wherever you keep important papers, or ask family.",
      "If it's lost, request records free online through milConnect, or mail form SF-180 to the National Archives.",
      "Ask for your service treatment records (STRs) in the same request.",
      "Upload the pages that mention the injury, event, or deployment.",
    ],
    whoToAsk: "The National Personnel Records Center (via milConnect or SF-180).",
    form: "SF-180",
    typicalCost: "Free",
    typicalTime: "A few weeks; older records can take longer",
  },
  personal_statement: {
    token: "personal_statement",
    title: "Personal statement",
    what: "Your own written account of what happened and how it affects you today.",
    steps: [
      "Write it plainly: what happened, when, and what daily life looks like now.",
      "Describe your worst days honestly — don't minimize.",
      "Put it on VA Form 21-4138 (Statement in Support of Claim), or add it as a statement here.",
    ],
    whoToAsk: "You — no one else needs to sign it.",
    form: "VA Form 21-4138",
    typicalCost: "Free",
    typicalTime: "Same day",
  },
  presumptive_documentation: {
    token: "presumptive_documentation",
    title: "Presumptive documentation",
    what:
      "Proof you served where and when a presumptive rule applies — often just the " +
      "right page of your DD-214.",
    steps: [
      "Find the DD-214 page showing your service dates and locations.",
      "For burn pit / PACT Act presumptives, deployment orders also work.",
      "Upload it — when a presumption covers you, you usually do NOT need a nexus letter.",
    ],
    whoToAsk: "Your own records first; the National Archives (SF-180) if they're lost.",
    form: null,
    typicalCost: "Free",
    typicalTime: "Same day if you have your DD-214",
  },
};

// Legacy / humanized spellings → canonical token. Keys are in normalized form
// (see `normalizeGapType`). Covers the older synthesis-prompt enum
// ("medical_record", "c_and_p_exam") and the pre-pipeline pass-through labels
// ("Nexus", "In-Service"), plus the endpoint's humanized special case
// ("Independent medical opinion").
const ALIASES: Record<string, GapTypeToken> = {
  medical_record: "treatment_record",
  c_and_p_exam: "c_and_p_exam_request",
  independent_medical_opinion: "imo_independent_medical_opinion",
  nexus: "nexus_letter",
  in_service: "service_record",
};

/** Lowercase, "&" → "and", non-alphanumerics → "_" ("C&P exam request" →
 *  "c_and_p_exam_request", "Nexus letter" → "nexus_letter"). */
function normalizeGapType(type: string): string {
  return type
    .toLowerCase()
    .trim()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
}

/**
 * Look up the action script for a gap type. Accepts the raw pipeline token
 * ("nexus_letter"), the humanized display string the /claim/gaps endpoint
 * emits ("Nexus letter", "C&P exam request"), or a legacy label. Returns null
 * for unknown types — callers must render nothing rather than guess.
 */
export function gapActionScript(type: string | null | undefined): GapActionScript | null {
  if (!type) return null;
  const key = normalizeGapType(type);
  const token = (ALIASES[key] ?? key) as GapTypeToken;
  return GAP_ACTION_SCRIPTS[token] ?? null;
}
