# After Duty — User Experience Expectations

This document describes what users should expect when they upload a document,
so they aren't frustrated by the wait.

## The Upload Flow

### Stage 1: Upload (1-5 seconds)
**What happens:** The file is sent to the backend and stored in Cloud Storage.

**What the user should see:**
- Progress bar on the upload card (0% → 100%)
- "Uploading..." message
- The file appears in the evidence list immediately after upload completes with status "processing"

**What tells the user it's still working:**
- The evidence card shows a **spinning progress indicator**
- The status badge reads **"Processing..."** or **"Reading your document..."**

### Stage 2: Extraction (5 seconds — 3 minutes)
**What happens:** The Gemini AI reads the document and extracts medical facts
(medications, diagnoses, symptoms, service events) as "atoms".

**Timing by document size:**
| Size | Expected time |
|------|---------------|
| <10 KB (short note) | 5-15 seconds |
| 10-100 KB (medical record) | 15-45 seconds |
| 100 KB - 1 MB (health summary) | 30-90 seconds |
| 1-10 MB (Blue Button export) | 60-180 seconds |

**What the user should see:**
- Live status updates on the evidence card:
  - "AI is reading through your document..."
  - "Extracting medical facts..."
  - "Found X medications, Y diagnoses..."
- A **progress animation** (not a frozen screen)
- Estimated time remaining if possible

**What tells the user it's still working:**
- The status message changes every 10-20 seconds
- The spinner animates
- A message like "This can take 1-3 minutes for large documents"

**When extraction completes:**
- Status changes to "Ready" or "Processed"
- Atom count is displayed: "Extracted 45 medical facts"
- A "View Details" button becomes available

### Stage 3: Analysis (30 seconds — 5 minutes)
**What happens:** Once all documents are extracted, the AI analyzes all atoms
together to identify conditions, assess triad (diagnosis/in-service/nexus),
and estimate ratings.

**This only runs when the user clicks "Analyze Claim" or after the last
document is processed (auto-trigger).**

**What the user should see:**
- Banner at top of conditions page: "Analyzing your evidence..."
- Status updates:
  - "Identifying potential conditions..."
  - "Matching evidence to VASRD criteria..."
  - "Calculating estimated ratings..."
- A **loading state** on the conditions page

**When analysis completes:**
- Banner disappears
- Conditions list populates with:
  - Condition name
  - VASRD code
  - Estimated rating (e.g., "70%")
  - Triad status indicators (green/yellow/red for each)
  - Confidence percentage

### Stage 4: Gap Analysis (runs automatically after synthesis, 30s-2min)
**What happens:** For each identified condition, the AI determines what
evidence is missing to strengthen the claim or reach a higher rating.

**What the user should see:**
- On each condition card: "X evidence gaps identified"
- A "View Gaps" button
- What-if scenarios: "Obtaining an IMO from a psychiatrist could raise
  this from 50% to 70% (+$X/month)"

## Anti-Frustration Principles

1. **Never leave the user with a blank screen.** Show progress or status.
2. **Every wait longer than 3 seconds gets a spinner.**
3. **Every wait longer than 10 seconds gets a status message that updates.**
4. **Every wait longer than 60 seconds gets an estimated time remaining.**
5. **Never say "Processing..." for more than 30 seconds without updating the
   message.**
6. **Errors must explain what to do next**, not just "An error occurred".
7. **The user should always know where they are in the process** (step 2 of 4).

## What goes wrong and how to handle it

| Error | User message |
|-------|--------------|
| File type not supported | "We support PDF, TXT, and images. Please upload one of these formats." |
| File too large (>50 MB) | "This file is too large. Please split it into smaller files." |
| Extraction returned 0 atoms | "We couldn't extract any medical information. Is this the right document?" |
| Analysis timeout | "Analysis is taking longer than expected. We'll notify you when it's done." |
| Network failure during upload | "Upload interrupted. Retry?" (with retry button) |
| Duplicate file | "You already uploaded this file. Do you want to replace it?" |

## Automated Test

Run `python3 tests/test_upload_flow.py` to validate the full flow:
1. Uploads the test document
2. Polls for extraction
3. Triggers analysis
4. Polls for completion
5. Verifies conditions were extracted
6. Reports timings for each stage
