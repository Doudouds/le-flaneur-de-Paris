#!/usr/bin/env python3
"""Apply explicit human choices from the Wikipedia-source review CSV.

The audit script only produces suggestions.  This command is the deliberately
separate write step: it accepts only the two review choices exposed in the
``action`` column, reuses the cached Wikipedia introduction for the chosen
page, and records every applied, retained and skipped decision in JSON.

Examples:

    python tools/apply_wikipedia_review_actions.py
    python tools/apply_wikipedia_review_actions.py --dry-run
    python tools/apply_wikipedia_review_actions.py --skip-reference PA00088104
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any

from audit_wikipedia_sources import audit_exact_pages
from enrich_missing_descriptions import (
    DEFAULT_CACHE_PATH,
    DEFAULT_JSON_PATH,
    ResponseCache,
    WikipediaClient,
    atomic_write_json,
    clean,
    normalize,
    title_from_wikipedia_url,
)


DEFAULT_REVIEW_CSV_PATH = DEFAULT_CACHE_PATH.parent / "wikipedia_source_review.csv"
DEFAULT_REPORT_PATH = DEFAULT_CACHE_PATH.parent / "wikipedia_review_application_report.json"

KEEP_CURRENT_ACTION = "keep current title and current url"
USE_SUGGESTED_ACTION = "set to suggested title and suggested url"


def review_decision(value: str) -> str | None:
    normalized = normalize(value)
    if normalized == KEEP_CURRENT_ACTION:
        return "keep"
    if normalized == USE_SUGGESTED_ACTION:
        return "suggested"
    return None


def read_review(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return [{key: clean(value) for key, value in row.items()} for row in csv.DictReader(handle)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON_PATH)
    parser.add_argument("--review-csv", type=Path, default=DEFAULT_REVIEW_CSV_PATH)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE_PATH)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT_PATH)
    parser.add_argument("--request-interval", type=float, default=0.35)
    parser.add_argument(
        "--skip-reference",
        action="append",
        default=[],
        help="référence Mérimée à laisser intacte, avec motif consigné dans le rapport (répétable)",
    )
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    json_path = args.json.resolve()
    review_path = args.review_csv.resolve()
    if not review_path.is_file():
        raise FileNotFoundError(f"File de revue introuvable : {review_path}")
    monuments = json.loads(json_path.read_text(encoding="utf-8"))
    if not isinstance(monuments, list):
        raise ValueError(f"L'asset doit contenir une liste JSON : {json_path}")
    by_id = {clean(monument.get("id")): monument for monument in monuments}
    skipped_references = {clean(value).upper() for value in args.skip_reference if clean(value)}
    report: dict[str, Any] = {
        "asset": str(json_path),
        "review_csv": str(review_path),
        "dry_run": args.dry_run,
        "applied": [],
        "kept": [],
        "skipped": [],
        "errors": [],
    }

    rows = read_review(review_path)
    suggestions: list[dict[str, str]] = []
    for row in rows:
        decision = review_decision(row.get("action", ""))
        if decision is None:
            continue
        reference = row.get("merimeeReference", "").upper()
        monument = by_id.get(row.get("id", ""))
        if monument is None:
            report["errors"].append({"id": row.get("id", ""), "reason": "monument absent de l'asset"})
            continue
        if clean(monument.get("merimeeReference")).upper() != reference:
            report["errors"].append({"id": row.get("id", ""), "reason": "référence Mérimée incohérente"})
            continue
        if reference in skipped_references:
            report["skipped"].append({
                "id": row["id"],
                "merimeeReference": reference,
                "reason": "référence explicitement exclue de cette application",
            })
            continue
        if decision == "keep":
            report["kept"].append({
                "id": row["id"],
                "merimeeReference": reference,
                "title": row.get("currentTitle", ""),
                "url": row.get("currentUrl", ""),
            })
            continue
        title = row.get("suggestedTitle", "") or title_from_wikipedia_url(row.get("suggestedUrl", ""))
        if not title:
            report["errors"].append({"id": row["id"], "reason": "article suggéré sans titre"})
            continue
        suggestions.append({**row, "suggestedTitle": title})

    cache = ResponseCache(args.cache.resolve())
    wikipedia = WikipediaClient(cache, args.request_interval)
    pages = audit_exact_pages(wikipedia, [row["suggestedTitle"] for row in suggestions])
    for row in suggestions:
        page = pages.get(row["suggestedTitle"], {})
        description = clean(page.get("description"))
        url = clean(page.get("url")) or row.get("suggestedUrl", "")
        if not description or not url:
            report["errors"].append({
                "id": row["id"],
                "merimeeReference": row.get("merimeeReference", ""),
                "reason": "introduction Wikipédia exploitable introuvable pour l'article suggéré",
            })
            continue
        monument = by_id[row["id"]]
        before = {
            "title": title_from_wikipedia_url(monument.get("wikipediaUrl")),
            "url": clean(monument.get("wikipediaUrl")),
        }
        monument["description"] = description
        monument["descriptionSource"] = "Wikipédia"
        monument["wikipediaUrl"] = url
        report["applied"].append({
            "id": row["id"],
            "merimeeReference": row.get("merimeeReference", ""),
            "before": before,
            "after": {"title": clean(page.get("title")) or row["suggestedTitle"], "url": url},
        })
    report["requests"] = wikipedia.request_count
    cache.close()

    report_path = args.report.resolve()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if report["applied"] and not args.dry_run:
        atomic_write_json(json_path, monuments)
    print(json.dumps({key: value for key, value in report.items() if key not in {"applied", "kept", "skipped"}}, ensure_ascii=False, indent=2))
    print(
        f"Décisions : {len(report['applied'])} appliquées, {len(report['kept'])} conservées, "
        f"{len(report['skipped'])} ignorées, {len(report['errors'])} erreurs."
    )
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
