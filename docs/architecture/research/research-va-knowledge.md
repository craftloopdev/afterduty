# Research: VA Ratings Criteria & Adjudication Knowledge Bases

Date: 2026-06-10. Researched for VA Claim Path's (a) synthesis grounding and (b) chat knowledge base.
Method: 8 web searches + 6 page fetches + direct HTTP verification of every API endpoint cited below (marked **[verified live]** where I called the endpoint myself today).

---

## Findings

### 1. 38 CFR Part 4 — Schedule for Rating Disabilities (VASRD)

**Structure.** Title 38, Chapter I, Part 4. Subpart A = general rating policy (§§ 4.1–4.31, incl. § 4.16 TDIU, § 4.25 combined-ratings table, § 4.26 bilateral factor). Subpart B = rating schedule by body system (§ 4.71a musculoskeletal, § 4.97 respiratory, § 4.130 mental disorders, etc.), where each section embeds rating tables keyed by 4-digit **diagnostic codes**. The full Part 4 is 202 section-level (`DIV8`) nodes, ~1.06 MB of XML **[verified live]**.

**Machine-readable access — eCFR API (free, no key, no auth):**
- `GET https://www.ecfr.gov/api/versioner/v1/titles.json` — freshness metadata per title. **[verified live]** Title 38: `up_to_date_as_of: 2026-06-09`, `latest_amended_on: 2026-02-27` (queried 2026-06-10 → ~1-day refresh lag; eCFR is updated daily).
- `GET https://www.ecfr.gov/api/versioner/v1/structure/{date}/title-38.json` — full TOC hierarchy (parts → subparts → sections) as JSON. **[verified live]**
- `GET https://www.ecfr.gov/api/versioner/v1/full/{date}/title-38.xml?part=4` — complete point-in-time XML of Part 4. **[verified live]** (1,058,467 bytes for 2026-06-09.)
- `GET https://www.ecfr.gov/api/versioner/v1/versions/title-38.json?part=4&issue_date[gte]=YYYY-MM-DD` — **per-section amendment history**. **[verified live]** 5 Part 4 versions since 2025-01-01: § 4.10 amended 2026-02-17 and again 2026-02-27; § 4.71a (musculoskeletal) amended 2025-01-14.
- API docs: https://www.ecfr.gov/developers/documentation/api/v1 (page is bot-protected — redirects automated fetchers to unblock.federalregister.gov; the API itself answers plain `curl` fine).

**Bulk alternative — GPO govinfo bulk data:** `https://www.govinfo.gov/bulkdata/ECFR/title-38/ECFR-title38.xml` — single 9.3 MB full-title XML + graphics zip, last modified 2026-03-02 **[verified live]**. Good for bootstrap; coarser cadence than the eCFR API.

**Update cadence & change-watch.** eCFR refreshes daily. To watch for *upcoming* changes, the **Federal Register API** (free, no key) filters rules by CFR part: `GET https://www.federalregister.gov/api/v1/documents.json?conditions[cfr][title]=38&conditions[cfr][part]=4&conditions[type][]=RULE` — 68 rules historically affect Part 4 **[verified live]**. Cautionary live example: VA published interim final rule "Evaluative Rating: Impact of Medication" 2026-02-17 (FR doc 2026-02797 era) and **rescinded it 2026-02-27** (FR doc 2026-03940), restoring prior text — which is exactly the § 4.10 double-amendment visible in the eCFR versions endpoint. A knowledge base that ingested the Feb 17 text and never refreshed would be wrong today.

**Licensing.** U.S. government edicts/works — public domain (17 U.S.C. § 105). Caveat: eCFR is an *unofficial* editorial compilation (the annual printed CFR is the official edition); standard practice is an "unofficial compilation, current as of {date}" disclaimer.

Sources: https://www.ecfr.gov/current/title-38/chapter-I/part-4 · https://www.ecfr.gov/developers/documentation/api/v1 · https://www.govinfo.gov/bulkdata/ECFR/title-38 · https://www.federalregister.gov/documents/2026/02/27/2026-03940/rescission-of-interim-final-rule-evaluative-rating-impact-of-medication

### 2. 38 CFR Part 3 — Adjudication (service-connection rules)

Same eCFR API; Part 3 sits beside Part 4 in title 38 **[verified live, section identifiers from structure JSON]**:
- § 3.303 Principles relating to service connection (direct)
- § 3.304 Direct service connection; wartime and peacetime
- § 3.306 Aggravation of preservice disability
- § 3.307 Presumptive service connection (chronic/tropical/POW, herbicide agents, **Camp Lejeune water**)
- § 3.309 Diseases subject to presumptive service connection (the canonical lists, incl. (e) herbicide/Agent Orange)
- § 3.310 Secondary service connection (proximately due to, or aggravated by, a service-connected condition)
- § 3.311 Claims based on exposure to ionizing radiation
- § 3.316 Mustard gas and Lewisite
- § 3.317 Persian Gulf veterans (undiagnosed illness / MUCMI)
- § 3.318 ALS presumption
- § 3.320 Fine particulate matter (PACT Act burn-pit presumptives)
- Also relevant: § 3.156 new evidence, § 3.159 VA duty to assist.

These ~12 sections are the legal backbone of any gap-analysis/synthesis logic (direct vs. secondary vs. aggravation vs. presumptive theories of entitlement).

### 3. Presumptive condition lists (PACT Act, Agent Orange, Gulf War, radiation)

- **Authoritative machine-readable source = the regs themselves** (§§ 3.307/3.309/3.311/3.316/3.317/3.318/3.320 via the same eCFR API). Lists change by rulemaking, so the Federal Register API watch covers them too.
- **PACT Act** (Sergeant First Class Heath Robinson Honoring our PACT Act, Pub. L. 117-168, signed 2022-08-10) added 20+ burn-pit/toxic-exposure presumptive conditions; secondary sources describe ~23 categories / 300+ specific conditions. VA's implementing final rule: Federal Register doc **2024-21852** (published 2024-10-01), "VA Adjudication Regulations for Disability or Death Benefit Claims Based on Toxic Exposure" (amends §§ 3.309, 3.320). https://www.federalregister.gov/documents/2024/10/01/2024-21852/
- **Human-readable canonical page:** https://www.va.gov/resources/the-pact-act-and-your-va-benefits/ (good for plain-language summaries; not machine-stable).
- Caveat: statutory presumptions (38 U.S.C. §§ 1119–1120) can lead the regulations — a regs-only pipeline can lag new statutory presumptives by months. Pair the reg text with the VA.gov page.
- Licensing: public domain (statute + regs + VA.gov content are U.S. government works).

### 4. DBQ forms (Disability Benefits Questionnaires)

- Official public list: https://www.benefits.va.gov/compensation/dbq_publicdbqs.asp — **~70+ public DBQs as PDFs**, organized into **19 body-system categories** (cardio, musculoskeletal, mental, neuro, respiratory, etc.), file pattern `/compensation/docs/{Condition_Name}.pdf` **[verified via page fetch]**.
- **11 DBQs are internal-only** (not public): e.g., Cold Injury Residuals, Former POW Protocol, General Medical – Compensation/Pension.
- Each DBQ enumerates exactly the findings, measurements, and history a rater needs to apply the corresponding Part 4 diagnostic-code criteria — i.e., a ready-made **evidence checklist per condition**.
- Update note: the Elizabeth Dole 21st Century Veterans Healthcare and Benefits Improvement Act (2025) requires MDE contractors to deliver completed DBQs in PDF via a standardized data-exchange framework — forms get revised periodically, so re-crawl the list page.
- Licensing: public domain (VA forms).

### 5. M21-1 Adjudication Procedures Manual

- Publicly mirrored since 2015-04-15 on VA's **KnowVA** knowledge base ("Live Manual"): https://www.knowva.ebenefits.va.gov/.../M21-1-Adjudication-Procedures-Manual — content is "a mirror image" of what VA raters see internally (Wikipedia + Federal Register 2016-06257 document the WARMS→KnowVA move).
- **Format:** HTML knowledge-base articles, one per Part/Subpart/Chapter/Section, organized into 14 parts (FY21 reorganization made it "more consumable"); each article URL embeds a numeric content ID. **No API, no bulk export.**
- **Keep-current:** VBA updates continuously; KnowVA publishes **"M21-1 Changes By Date"** articles and offers **e-mail update subscriptions** — both usable as change feeds for re-scraping only what changed.
- Ingestion = polite HTML scraping from the Table of Contents article; chunk by section ID (e.g., "M21-1, Part X, Subpart i, Chapter 3, Section B").
- Licensing: U.S. government work — public domain.

Sources: https://en.wikipedia.org/wiki/M21-1_Adjudication_Procedures_Manual · https://www.federalregister.gov/documents/2016/03/21/2016-06257/web-automated-reference-material-system · KnowVA TOC and Changes-By-Date pages · https://news.va.gov/24373/24373/

### 6. BVA & CAVC decision databases

**BVA (Board of Veterans' Appeals) — non-precedential but rich:**
- Full text **1992–present** hosted as plain-text files: `https://www.va.gov/vetapp{YY}/files{N}/{decisionid}.txt`. **[verified live:** `vetapp25/files1/25000001.txt` → HTTP 200, `text/plain`, 13.4 KB**]**
- Master index: `https://www.va.gov/sitemap_bva.xml` **[verified live]** — yearly sitemaps vetapp92…vetapp26; vetapp26 lastmod 2026-05-18, vetapp25 lastmod 2026-03-05. (Direct fetch of a per-year `vetappNN/sitemap.xml` returned the VA 404 page — see Open Questions.)
- Full-text search: `https://search.usa.gov/search/docs?affiliate=bvadecisions` **[verified live, HTTP 200]**.
- Open-data record: data.va.gov dataset `3ydu-9hm5` — **License: CC0 1.0** (public-domain dedication), Update Frequency `R/P1M` (monthly), publisher Board of Veterans' Appeals **[verified live via Socrata metadata API]**. https://www.data.va.gov/dataset/Board-of-Veterans-Appeals-Decisions/3ydu-9hm5
- Pre-packaged corpora: academic "BVA corpus" (1M+ decisions 1999–2017 with VACOLS metadata incl. diagnostic codes and outcomes; see arXiv 2106.10776), HuggingFace `pile-of-law/pile-of-law` (`bva_opinions` subset), and a newer HF dataset structuring 2019–present decisions into issue/condition/outcome/citation fields.
- Decisions are redacted (no veteran names) and non-precedential — usable for "similar cases" patterns, not legal authority.

**CAVC (U.S. Court of Appeals for Veterans Claims) — precedential panel opinions:**
- Official search: http://search.uscourts.cavc.gov/ (PDF/WordPerfect downloads); recent/precedential list at https://m.uscourts.cavc.gov/RecentDecisions.php — site states data updated **nightly**.
- **CourtListener** (Free Law Project) REST API v4: citation-lookup API ("parse and look up every citation in a block of text," explicitly marketed for preventing AI hallucination); free authenticated tier after the 2026-05-07 policy change = **5 requests/min, 50/hr, 125/day** (previously 5,000/hr; grandfathered users keep old rates); memberships/EDU raise limits. CAVC coverage is *likely* but I could not verify it from this environment (API call returned empty — see Open Questions). https://free.law/2026/05/07/api-included-in-memberships/ · https://www.courtlistener.com/help/api/rest/
- Licensing: court opinions = edicts of government, public domain.

### 7. VA Lighthouse APIs (developer.va.gov) — adjacent, not a knowledge base

- **Benefits Reference Data API** — lookup data "filtered and formatted to be accepted within VA benefits claims" (disabilities/contention lists, intake sites, etc.). Requires an API key (free sandbox signup; production requires VA approval). **[verified live:** unauthenticated calls to sandbox & prod return `"No API key found in request"`**]** https://developer.va.gov/explore/api/benefits-reference-data
- Benefits Claims API (526EZ submission, claim status) exists for a future "file from the app" roadmap — out of scope for the knowledge base but worth tracking.

### 8. How competitors use these sources

| Tool | Sources used | Notes |
|---|---|---|
| **V2V Intelligence** (vaclaims.net) | 1,850,000+ BVA decisions (1992–present), CAVC docket w/ real-time updates, 38 CFR + diagnostic codes, M21-1 | AI analysis of BVA denials, evidence-gap analysis, "every conclusion cited to source"; veterans + VSOs/agents/attorneys; **from $9.99/mo** [verified on site] |
| **VeteranAI "Ask Six"** (veteranai.co) | "Trained on 38 CFR, M-21, and VA regulations — not the entire internet" | Chat on secondaries, C&P prep, denial analysis; available on **free plan**; paid adds unlimited chat + nexus-letter generator, personal-statement generator, C-File analyzer (price not disclosed on page) |
| **NexusVetClaims** | 38 CFR sections "that raters apply" | 20+ free tools, 13 AI-powered |
| **VA Claims Navigator** (GPT wrapper) | M21-1, Clinicians Guide to C&P Exams, CFRs, M28R, 38 CFR Part 21 | Knowledge-base-grounded chat |
| **VA Claims Insider / Hill & Ponton / Veterans Guide** | M21-1, DBQ lists, presumptive lists | SEO content/education funnels into coaching/legal services |

Pattern: **grounding in 38 CFR + M21-1 with visible citations is table stakes** in this market; the BVA corpus powers the differentiated features (similar-case search, denial analysis) — and it's CC0.

---

## Pricing / Limits table

| Source | Cost | Auth | Limits / cadence |
|---|---|---|---|
| eCFR API (versioner: titles/structure/full/versions) | Free | None | No published rate limit (docs page bot-gated; API answers curl); refreshed daily, title 38 current to T-1 |
| GPO govinfo bulk ECFR XML | Free | None | Full title 38 = 9.3 MB XML; updated on amendment (last 2026-03-02) |
| Federal Register API | Free | None | Filter rules by CFR title/part; 68 historical Part 4 rules |
| M21-1 on KnowVA | Free | None | HTML only, no API/bulk; continuous updates; Changes-By-Date + email subscription as change feed |
| Public DBQs (benefits.va.gov) | Free | None | ~70+ PDFs, 19 categories; 11 DBQs internal-only |
| BVA decisions (va.gov/vetappNN) | Free | None | Plain .txt, 1992–present; dataset listed CC0 1.0, monthly update freq |
| BVA search (search.usa.gov, affiliate=bvadecisions) | Free | None | Full-text search UI/endpoint |
| CAVC opinions (uscourts.cavc.gov) | Free | None | Updated nightly; PDF/WordPerfect |
| CourtListener API | Free acct; paid memberships for higher limits | Token | Free tier (post 2026-05-07): 5 req/min, 50/hr, 125/day; EDU free; membership prices not confirmed |
| VA Lighthouse Benefits Reference Data | Free sandbox | API key | Production requires VA approval |
| Competitor: V2V Intelligence | from $9.99/mo | — | 1.85M+ BVA + CAVC + CFR + M21-1, cited AI answers |
| Competitor: VeteranAI Ask Six | Free tier; paid undisclosed | — | Free chat limited; paid = unlimited + extra generators |
| Licensing (all VA/GPO/court content) | Public domain (17 U.S.C. § 105) / CC0 | — | eCFR text is "unofficial compilation" — disclaim accordingly |

---

## Implications for VA Claim Path

1. **The entire knowledge base is $0 and license-clean.** Every load-bearing source (38 CFR 3 & 4, M21-1, DBQs, presumptive lists, BVA, CAVC) is public domain or CC0. The moat is ingestion quality + citation UX, not data access.
2. **Ground synthesis in eCFR via the versioner API, with point-in-time stamps.** Nightly job: check `titles.json` → if title 38 `up_to_date_as_of` advanced, hit `/versions?part=3,4` to find changed sections → re-ingest only those → store the eCFR date on every chunk so chat citations can say "38 CFR § 4.130 (current as of 2026-06-09)". The Feb 2026 publish-then-rescind episode (§ 4.10) proves a stale snapshot becomes *wrong*, not just outdated.
3. **Parse Part 4 into structured rating-criteria records, not just RAG chunks.** The XML rating tables (diagnostic code → rating % → criteria text) support deterministic features (show exact criteria for a veteran's DC; combined-ratings math per § 4.25/4.26) that LLM retrieval alone can't do reliably.
4. **Use DBQs as the evidence-checklist engine.** Map condition → public DBQ → extract its question/measurement structure as the gap-analysis rubric ("the rater's form asks for X; your file doesn't show X"). Directly powers the paid gap-analysis feature and is defensible because it mirrors VA's own instruments.
5. **BVA corpus = the differentiator competitors charge for.** V2V charges $9.99+/mo largely for cited BVA/CAVC search. The raw corpus is CC0 plain text with a sitemap index — a "similar past decisions" retrieval feature is buildable in-house. Mandatory framing: non-precedential, educational examples only (fits the existing not-legal-advice posture).
6. **Encode presumptives as structured eligibility data, hand-curated from §§ 3.307–3.320 with reg citations,** and watch the Federal Register API for amendments; pair with the VA.gov PACT page for plain language. Theory-of-entitlement logic (direct / secondary § 3.310 / aggravation § 3.306 / presumptive) should be explicit in the synthesis prompt scaffold.
7. **M21-1 is scrape-only — budget for it.** Chunk per section, re-scrape from Changes-By-Date, subscribe to the email feed as a tripwire. It is the highest-leverage source for "how raters actually decide" answers in chat.
8. **Citation-first chat UX is table stakes** (every serious competitor advertises it). Each chat answer should carry pinned citations to § / M21-1 section / decision ID, with our stored point-in-time date.

## Open Questions

1. **CourtListener CAVC coverage + bulk data:** API returned empty from this environment; coverage of court id `cavc` and current membership pricing/bulk-data terms need confirmation from a browser session.
2. **eCFR API formal rate limits / ToS for automated bulk:** docs page is bot-gated (unblock.federalregister.gov); confirm acceptable polling rates before productionizing a nightly diff job.
3. **Per-year BVA sitemap URLs:** `sitemap_bva.xml` lists `vetapp{YY}/sitemap.xml` children but a direct fetch 404'd — resolve actual per-year index path (or enumerate decision IDs from the search endpoint / data.gov dataset instead).
4. **KnowVA scraping friction:** robots.txt posture, session handling, and whether VBA offers any export on request; also confirm Changes-By-Date page granularity (per-section vs. per-release).
5. **Lighthouse Benefits Reference Data `disabilities` endpoint:** does it expose VASRD diagnostic codes / VBMS classification list usable as our canonical condition taxonomy? Needs a (free) sandbox API key to inspect.
6. **PACT Act statutory-vs-regulatory lag:** confirm whether any 38 U.S.C. § 1119/1120 presumptives are effective but not yet reflected in §§ 3.309/3.320 text as of 2026-06.
7. **License of the structured 2019–present BVA HF dataset** (and the 1999–2017 academic corpus): raw decisions are public domain, but the *curated/structured* datasets may carry their own terms for commercial use.
8. **Competitor paid pricing:** VeteranAI and NexusVetClaims paid tiers undisclosed on fetched pages; V2V tier structure above $9.99/mo unconfirmed.
