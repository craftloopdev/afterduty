import { toDoc } from "@/lib/adapters/evidence";
import type { DocVM } from "@/lib/models/vm";

// Statuses mirror the real backend vocabulary (PipelineService):
// pending | processing | processed | error | deferred_usage_limit.
export const docsFixture: DocVM[] = [
  toDoc({ id: 1, sourceType: "upload", filename: "dd214.pdf", aiClassification: "Service", processingStatus: "processed" }),
  toDoc({ id: 2, sourceType: "upload", filename: "VA-Blue-Button-LABS.pdf", aiClassification: "Medical", processingStatus: "processed" }),
  toDoc({
    id: 3,
    sourceType: "chat",
    filename: "Personal_Statement_GRIFF.pdf",
    aiClassification: "Statement",
    processingStatus: "error",
    processingMessage: "Extraction failed: the file couldn't be read.",
  }),
  toDoc({ id: 4, sourceType: "upload", filename: "OIF-orders.pdf", aiClassification: "Service", processingStatus: "processing" }),
  toDoc({
    id: 5,
    sourceType: "upload",
    filename: "buddy-letter-jones.pdf",
    processingStatus: "deferred_usage_limit",
    processingMessage: "Paused — plan limit reached. Resumes 2026-07-01.",
  }),
];
