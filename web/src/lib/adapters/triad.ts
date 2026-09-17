import type { TriadLevel } from "@/lib/theme/tokens";

/*
 * Normalize the backend's triad strength vocabulary into the redesign's 3 levels.
 * The backend emits "strong" | "moderate" | "weak" | "missing" (and historically
 * uppercase from ConditionIdentificationAgent). Mirrors the proven mapping in
 * flutter_frontend/lib/data/vcp_adapters.dart. Fail-loud default = "missing".
 */
export function toTriadLevel(status: string | null | undefined): TriadLevel {
  const s = (status ?? "").toLowerCase().trim();
  if (s === "strong") return "strong";
  if (s === "moderate" || s === "weak" || s === "partial") return "partial";
  // "missing", "unknown", "", or anything unexpected → missing.
  return "missing";
}
