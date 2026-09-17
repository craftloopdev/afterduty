#!/usr/bin/env python3
"""Generate the bundled VASRD index from 38 CFR Part 4 (eCFR, public domain).

Replaces ``parse_masterlist.py``, which scraped veteransbenefitskb.com. Every
row here derives from the eCFR versioner API — the same endpoint
``EcfrClientImpl`` calls — so the bundled fallback and the ingested
``vasrd_records`` table describe the same schedule.

Output: ``spring-backend/src/main/resources/vasrd_index.json``

    [{"code": "6260",
      "title": "Tinnitus, recurrent",
      "body_system": "Schedule of ratings—ear.",
      "cfr_section": "4.87"}, ...]

``body_system`` is the section heading with its "§ 4.87" prefix stripped —
byte-identical to ``VasrdIngestService.sectionHeading()``, so a code resolved
from this file and one resolved from the DB agree.

Usage:
    python3 scripts/generate_vasrd_index.py [--date YYYY-MM-DD] [--xml FILE]
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sys
import urllib.request
from pathlib import Path

ECFR = "https://www.ecfr.gov/api/versioner/v1/full/{date}/title-38.xml?part=4"

OUT = (Path(__file__).resolve().parent.parent
       / "spring-backend/src/main/resources/vasrd_index.json")

# A DIV8 section: <DIV8 N="4.71a" TYPE="SECTION"> ... </DIV8>
SECTION = re.compile(r'<DIV8\b[^>]*\bN="([^"]+)"[^>]*>', re.I)
HEAD = re.compile(r"<HEAD>(.*?)</HEAD>", re.S | re.I)
# A code+title pair leads its element. Part 4 carries them two ways: rating
# table cells (<TD>), and the flush-paragraph code lists §4.130 uses for the
# mental disorders that share one General Rating Formula (<FP-2>).
CELL = re.compile(r'<(TD|FP-2)\b[^>]*>((?:(?!<(?:TD|FP-2)[ >])[\s\S])*?)</\1>', re.I)
# Title runs to the end of the cell — §4.73's muscle-group entries carry a full
# paragraph, so an upper length bound here would silently drop them. Long titles
# are trimmed in `condition_title` instead of rejected.
DC = re.compile(r"^\s*(\d{4})\s+(\S.*?)\s*:?\s*$", re.S)
TITLE_MAX = 140
# VasrdIngestService.sectionHeading(): strip a leading "§ 4.71a".
HEAD_PREFIX = re.compile(r"^\s*§?\s*[\d.a-zA-Z-]+\s*")


# A few diagnostic codes are assigned by a table lookup rather than stated as a
# "NNNN Title" row, so no parse of the schedule can recover them. Titles below
# are the CFR's own wording, taken from the cited section. 6100 matters most:
# it is the code for every hearing-loss claim.
SUPPLEMENT = [
    {"code": "6100", "title": "Hearing impairment",
     "body_system": "Evaluation of hearing impairment.", "cfr_section": "4.85"},
    {"code": "6067", "title": "Blindness in one eye, only light perception; other eye 5/200",
     "body_system": "Schedule of ratings—eye.", "cfr_section": "4.79"},
    {"code": "6092", "title": "Diplopia, limited muscle function",
     "body_system": "Schedule of ratings—eye.", "cfr_section": "4.79"},
]


def strip_tags(fragment: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", fragment))).strip()


def condition_title(raw: str) -> str:
    """Trim a schedule entry to a name usable as prompt context.

    Most entries are already short ("Tinnitus, recurrent"). §4.73's muscle
    groups run to a paragraph — keep the leading clause, which carries the
    group number and function.
    """
    if len(raw) <= TITLE_MAX:
        return raw
    # A first sentence only wins if it is descriptive on its own — §4.73 opens
    # with a bare "Group II.", which names nothing without the clause after it.
    head = re.split(r"(?<=\.)\s", raw, maxsplit=1)[0]
    if 30 <= len(head) <= TITLE_MAX:
        return head
    return raw[:TITLE_MAX].rsplit(" ", 1)[0].rstrip(",;:") + "…"


def fetch(date: str) -> str:
    url = ECFR.format(date=date)
    # The endpoint refuses uncompressed responses.
    req = urllib.request.Request(url, headers={"Accept-Encoding": "gzip, deflate"})
    print(f"fetching {url}", file=sys.stderr)
    with urllib.request.urlopen(req, timeout=180) as r:
        raw = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            import gzip
            raw = gzip.decompress(raw)
        elif r.headers.get("Content-Encoding") == "deflate":
            import zlib
            raw = zlib.decompress(raw)
    return raw.decode("utf-8", errors="replace")


def build(xml: str) -> list[dict]:
    if "<GPOTABLE" in xml:
        print("note: response carries GPOTABLE tags; this parser reads TD/TR",
              file=sys.stderr)

    bounds = [(m.group(1), m.start()) for m in SECTION.finditer(xml)]
    if not bounds:
        raise SystemExit("no <DIV8> sections found — is this Part 4 XML?")

    index: dict[str, dict] = {}
    for i, (section_id, start) in enumerate(bounds):
        end = bounds[i + 1][1] if i + 1 < len(bounds) else len(xml)
        body = xml[start:end]

        head = HEAD.search(body)
        heading = HEAD_PREFIX.sub("", strip_tags(head.group(1))) if head else section_id

        for cell in CELL.finditer(body):
            hit = DC.match(strip_tags(cell.group(2)))
            if not hit:
                continue
            code, title = hit.group(1), condition_title(hit.group(2).strip())
            if title.startswith("[") and title.rstrip(".").endswith("]"):
                continue  # "[Removed]" / "[Reserved]" placeholders
            # First occurrence wins: the schedule states a code before it is
            # cross-referenced in notes.
            index.setdefault(code, {
                "code": code,
                "title": title,
                "body_system": heading,
                "cfr_section": section_id,
            })

    for row in SUPPLEMENT:
        index.setdefault(row["code"], dict(row))

    return [index[c] for c in sorted(index)]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", default="2026-09-01", help="eCFR point-in-time date")
    ap.add_argument("--xml", help="read a local Part 4 XML instead of fetching")
    args = ap.parse_args()

    xml = Path(args.xml).read_text(encoding="utf-8") if args.xml else fetch(args.date)
    rows = build(xml)
    if len(rows) < 600:
        raise SystemExit(f"only {len(rows)} codes parsed — refusing to write a "
                         f"truncated index (expected ~720)")

    doc = {
        "as_of": args.date,
        "source": "38 CFR Part 4 via the eCFR versioner API (public domain)",
        "generated_by": "scripts/generate_vasrd_index.py",
        "codes": rows,
    }
    OUT.write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n",
                   encoding="utf-8")
    systems = {r["body_system"] for r in rows}
    print(f"wrote {OUT.relative_to(Path(__file__).resolve().parent.parent)}: "
          f"{len(rows)} codes across {len(systems)} sections, as of {args.date} "
          f"({OUT.stat().st_size / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
