#!/usr/bin/env python3
"""Audit existing Wikipedia descriptions against the Mérimée catalogue.

The initial enrichment deliberately favoured coverage. This companion script
checks the links already selected before they are treated as trusted sources.
It combines three independent signals:

* an exact Mérimée reference (P380) resolved through Wikidata;
* a conflict between the kind of monument in the catalogue and the Wikipedia
  page title (for example, ``fontaine`` versus ``pompe``);
* a targeted French Wikipedia search looking for an explicit monument alias in
  the candidate page's introduction, with a matching kind of monument.

The default mode never changes the catalogue. It writes a JSON report and a
CSV review queue. ``--apply-high-confidence`` only changes a record when the
replacement is corroborated by its Mérimée reference or an explicit alias,
the kind of monument, and a nearby geographic coordinate.

Examples:

    python tools/audit_wikipedia_sources.py
    python tools/audit_wikipedia_sources.py --only-reference PA00088688 --apply-high-confidence
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError

from enrich_missing_descriptions import (
    DEFAULT_CACHE_PATH,
    DEFAULT_JSON_PATH,
    ResponseCache,
    WikipediaClient,
    atomic_write_json,
    clean,
    haversine_metres,
    normalize,
    page_coordinates,
    title_from_wikipedia_url,
    title_similarity,
    usable_extract,
)
from enrich_monument_metadata import MetadataHttp


DEFAULT_OUTPUT_DIR = DEFAULT_CACHE_PATH.parent
DEFAULT_REPORT_PATH = DEFAULT_OUTPUT_DIR / "wikipedia_source_audit.json"
DEFAULT_REVIEW_CSV_PATH = DEFAULT_OUTPUT_DIR / "wikipedia_source_review.csv"

# Kept intentionally small and concrete: these are words that distinguish two
# different kinds of monuments. A mismatch is an audit signal, never by itself
# a reason to change a source.
MONUMENT_TYPES = {
    "aqueduc", "basilique", "boucherie", "canal", "chapelle", "chateau",
    "cimetiere", "couvent", "cremerie", "eglise", "fontaine", "gare",
    "halle", "hopital", "horlogerie", "hotel", "immeuble", "lavoir",
    "maison", "marche", "moulin", "musee", "palais", "passage", "pavillon",
    "pompe", "pont", "porte", "presbytere", "regard", "synagogue",
    "temple", "theatre", "tour",
}
GENERIC_TITLES = {
    "architecture", "boucherie", "charcuterie", "fontaine", "horlogerie",
    "hotel", "hotel de ville", "immeuble", "maison", "peinture", "pompe", "sculpture",
}
WKT_POINT_PATTERN = re.compile(r"Point\\(([-0-9.]+)\\s+([-0-9.]+)\\)")


def monument_types(value: str) -> set[str]:
    return set(normalize(value).split()) & MONUMENT_TYPES


def contains_alias(value: str, name: str) -> bool:
    alias = normalize(name)
    return bool(alias and alias in normalize(value))


def page_result(page: dict[str, Any]) -> dict[str, Any]:
    return WikipediaClient.page_result(page)


def audit_exact_pages(client: WikipediaClient, titles: Iterable[str]) -> dict[str, dict[str, Any]]:
    """Read introductions and coordinates with a cache dedicated to the audit."""
    requested = list(dict.fromkeys(title for title in titles if title))
    results: dict[str, dict[str, Any]] = {}
    missing = [title for title in requested if client.cache.get("audit_page", normalize(title)) is None]
    for start in range(0, len(missing), 20):
        batch = missing[start : start + 20]
        payload = client.request(
            {
                "action": "query",
                "prop": "extracts|coordinates|info",
                "titles": "|".join(batch),
                "redirects": "1",
                "inprop": "url",
                "exintro": "1",
                "explaintext": "1",
                "exsentences": "3",
                "exlimit": "max",
                "format": "json",
                "formatversion": "2",
            }
        )
        pages = WikipediaClient.pages_from_payload(payload)
        by_title = {normalize(clean(page.get("title"))): page for page in pages}
        for requested_title in batch:
            page = by_title.get(normalize(requested_title))
            if page is None:
                result: dict[str, Any] = {"found": False}
            else:
                result = page_result(page)
                coordinate = page_coordinates(page)
                if coordinate is not None:
                    result["latitude"], result["longitude"] = coordinate
            client.cache.set("audit_page", normalize(requested_title), result)
    for title in requested:
        cached = client.cache.get("audit_page", normalize(title))
        if cached:
            results[title] = cached
    return results


def article_distance(monument: dict[str, Any], article: dict[str, Any]) -> float | None:
    try:
        return haversine_metres(
            float(monument["latitude"]),
            float(monument["longitude"]),
            float(article["latitude"]),
            float(article["longitude"]),
        )
    except (KeyError, TypeError, ValueError):
        return None


def has_specific_alias(name: str) -> bool:
    meaningful = [token for token in normalize(name).split() if len(token) >= 3 and not token.isdigit()]
    return len(meaningful) >= 2


def is_strong_exact_replacement(monument: dict[str, Any], article: dict[str, Any]) -> bool:
    """Require evidence beyond a Wikidata P380 value before changing data."""
    name = clean(monument.get("name"))
    title = clean(article.get("title"))
    description = clean(article.get("description"))
    if not clean(article.get("url")) or not description or not has_specific_alias(name):
        return False
    if not contains_alias(f"{title} {description}", name):
        return False
    expected_types = monument_types(name)
    candidate_types = monument_types(f"{title} {description}")
    if expected_types and candidate_types and not (expected_types & candidate_types):
        return False
    distance = article.get("distanceMetres")
    return isinstance(distance, (int, float)) and distance <= 2_000


def is_strong_search_replacement(monument: dict[str, Any], article: dict[str, Any]) -> bool:
    """Apply a search result only if its alias, type and location all agree."""
    name = clean(monument.get("name"))
    if not has_specific_alias(name) or not contains_alias(f"{article.get('title', '')} {article.get('description', '')}", name):
        return False
    expected_types = monument_types(name)
    candidate_types = monument_types(f"{article.get('title', '')} {article.get('description', '')}")
    if expected_types and candidate_types and not (expected_types & candidate_types):
        return False
    distance = article.get("distanceMetres")
    return isinstance(distance, (int, float)) and distance <= 2_000


def wikidata_coordinates(metadata: MetadataHttp, qids: Iterable[str]) -> dict[str, tuple[float, float]]:
    """Read P625 coordinates for a small set of Wikidata items, with caching."""
    result: dict[str, tuple[float, float]] = {}
    missing: list[str] = []
    for qid in dict.fromkeys(qid.upper() for qid in qids if qid):
        cached = metadata.cache.get("wikidata_coordinate", qid)
        if cached is None:
            missing.append(qid)
        elif cached.get("found") and isinstance(cached.get("latitude"), (int, float)):
            result[qid] = (float(cached["latitude"]), float(cached["longitude"]))
    for start in range(0, len(missing), 80):
        batch = missing[start : start + 80]
        values = " ".join(f"wd:{qid}" for qid in batch)
        query = f"""
            SELECT ?item ?coordinate WHERE {{
                VALUES ?item {{ {values} }}
                OPTIONAL {{ ?item <http://www.wikidata.org/prop/direct/P625> ?coordinate . }}
            }}
        """
        payload = metadata.request(
            "https://query.wikidata.org/sparql",
            {"query": query, "format": "json"},
            post=True,
            accept="application/sparql-results+json",
        )
        found: dict[str, tuple[float, float]] = {}
        for row in payload.get("results", {}).get("bindings", []):
            item = clean(row.get("item", {}).get("value"))
            coordinate = clean(row.get("coordinate", {}).get("value"))
            match = WKT_POINT_PATTERN.search(coordinate)
            if not item or not match:
                continue
            try:
                longitude, latitude = float(match.group(1)), float(match.group(2))
            except ValueError:
                continue
            found[item.rsplit("/", 1)[-1].upper()] = (latitude, longitude)
        for qid in batch:
            coordinate = found.get(qid)
            if coordinate is None:
                metadata.cache.set("wikidata_coordinate", qid, {"found": False})
            else:
                metadata.cache.set(
                    "wikidata_coordinate",
                    qid,
                    {"found": True, "latitude": coordinate[0], "longitude": coordinate[1]},
                )
                result[qid] = coordinate
    return result


def candidate_from_search(monument: dict[str, Any], pages: Iterable[dict[str, Any]]) -> dict[str, Any] | None:
    """Return a highly corroborated alternative page, or None.

    A page must name the monument explicitly in its title or introductory text
    and must have a compatible type when the catalogue gives one. Coordinates
    are an additional confidence signal, not mandatory (many articles lack
    them).
    """
    name = clean(monument.get("name"))
    expected_types = monument_types(name)
    candidates: list[tuple[float, dict[str, Any]]] = []
    for page in pages:
        result = page_result(page)
        title = clean(result.get("title"))
        description = clean(result.get("description"))
        if not title or not description:
            continue
        alias_in_title = contains_alias(title, name)
        alias_in_intro = contains_alias(description, name)
        if not alias_in_title and not alias_in_intro:
            continue
        candidate_types = monument_types(f"{title} {description}")
        if expected_types and not (expected_types & candidate_types):
            continue
        similarity, overlap = title_similarity(name, title)
        score = 1.0 + (0.45 if alias_in_title else 0.30) + 0.20 * similarity + 0.15 * overlap
        coordinate = page_coordinates(page)
        if coordinate is not None:
            try:
                distance = haversine_metres(
                    float(monument["latitude"]),
                    float(monument["longitude"]),
                    coordinate[0],
                    coordinate[1],
                )
            except (KeyError, TypeError, ValueError):
                distance = None
            if distance is not None:
                result["distanceMetres"] = round(distance)
                if distance <= 1_500:
                    score += 0.25 * (1.0 - distance / 1_500)
        result["score"] = round(score, 3)
        result["aliasInTitle"] = alias_in_title
        result["aliasInIntro"] = alias_in_intro
        candidates.append((score, result))
    if not candidates:
        return None
    candidates.sort(key=lambda item: (-item[0], clean(item[1].get("title"))))
    best_score, best = candidates[0]
    if len(candidates) > 1 and best_score - candidates[1][0] < 0.08:
        return None
    return best


def review_row(
    monument: dict[str, Any],
    *,
    signal: str,
    severity: str,
    current_title: str,
    expected: dict[str, Any] | None = None,
    suggestion: dict[str, Any] | None = None,
) -> dict[str, Any]:
    replacement = expected if clean((expected or {}).get("title")) or clean((expected or {}).get("url")) else (suggestion or {})
    return {
        "id": clean(monument.get("id")),
        "merimeeReference": clean(monument.get("merimeeReference")).upper(),
        "name": clean(monument.get("name")),
        "severity": severity,
        "signal": signal,
        "currentTitle": current_title,
        "currentUrl": clean(monument.get("wikipediaUrl")),
        "suggestedTitle": clean(replacement.get("title")),
        "suggestedUrl": clean(replacement.get("url") or replacement.get("article_url")),
        "suggestedDistanceMetres": replacement.get("distanceMetres"),
        "action": "automatic" if severity == "high" else "review",
    }


def write_review_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "id", "merimeeReference", "name", "severity", "signal", "currentTitle",
        "currentUrl", "suggestedTitle", "suggestedUrl", "suggestedDistanceMetres", "action",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def existing_review_actions(path: Path) -> dict[str, str]:
    """Keep explicit reviewer choices when a later audit rewrites the queue.

    The CSV is intentionally the human-review surface.  Re-running an audit
    must never turn an explicit ``keep current`` or ``set to suggested``
    decision back into the generic ``review`` value.
    """
    if not path.is_file():
        return {}
    try:
        with path.open("r", encoding="utf-8-sig", newline="") as handle:
            return {
                clean(row.get("id")): clean(row.get("action"))
                for row in csv.DictReader(handle)
                if clean(row.get("id")) and clean(row.get("action"))
            }
    except (OSError, csv.Error):
        return {}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON_PATH)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE_PATH)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT_PATH)
    parser.add_argument("--review-csv", type=Path, default=DEFAULT_REVIEW_CSV_PATH)
    parser.add_argument("--request-interval", type=float, default=0.35)
    parser.add_argument("--only-reference", action="append", default=[], help="référence Mérimée à auditer (répétable)")
    parser.add_argument("--max-searches", type=int, default=0, help="limiter les recherches de remplacement (0 = toutes)")
    parser.add_argument("--apply-high-confidence", action="store_true", help="appliquer uniquement les remplacements fortement corroborés")
    args = parser.parse_args()

    json_path = args.json.resolve()
    monuments = json.loads(json_path.read_text(encoding="utf-8"))
    if not isinstance(monuments, list):
        raise ValueError(f"L'asset doit contenir une liste JSON : {json_path}")
    selected_references = {clean(value).upper() for value in args.only_reference if clean(value)}
    targets = [
        monument for monument in monuments
        if normalize(clean(monument.get("descriptionSource"))) == "wikipedia"
        and clean(monument.get("wikipediaUrl"))
        and (not selected_references or clean(monument.get("merimeeReference")).upper() in selected_references)
    ]

    cache = ResponseCache(args.cache.resolve())
    wikipedia = WikipediaClient(cache, args.request_interval)
    metadata = MetadataHttp(cache, args.request_interval)
    saved_actions = existing_review_actions(args.review_csv.resolve())
    report: dict[str, Any] = {
        "asset": str(json_path),
        "wikipedia_sources_checked": len(targets),
        "exact_wikidata_matches": 0,
        "confirmed": 0,
        "flagged": 0,
        "repaired": 0,
        "searches": 0,
        "requests": 0,
        "errors": [],
        "review": [],
        "apply_high_confidence": args.apply_high_confidence,
    }

    references = [clean(monument.get("merimeeReference")).upper() for monument in targets if clean(monument.get("merimeeReference"))]
    current_titles = [title_from_wikipedia_url(monument.get("wikipediaUrl")) for monument in targets]
    current_titles = [title for title in current_titles if title]
    try:
        wikidata_matches = metadata.wikidata_by_references(references)
        report["exact_wikidata_matches"] = len(wikidata_matches)
        expected_titles = [match.get("title", "") for match in wikidata_matches.values() if match.get("title")]
        page_results = audit_exact_pages(wikipedia, current_titles + expected_titles)
    except (HTTPError, URLError, TimeoutError, RuntimeError) as error:
        wikidata_matches = {}
        page_results = {}
        report["errors"].append(str(error))

    expected_qids = []
    for monument in targets:
        reference = clean(monument.get("merimeeReference")).upper()
        current_title = title_from_wikipedia_url(monument.get("wikipediaUrl"))
        current_result = page_results.get(current_title, {})
        resolved_current_title = clean(current_result.get("title")) or current_title
        exact_match = wikidata_matches.get(reference, {})
        exact_title = clean(exact_match.get("title"))
        if exact_title and normalize(exact_title) != normalize(resolved_current_title):
            expected_qids.append(clean(exact_match.get("qid")))
    try:
        expected_coordinates = wikidata_coordinates(metadata, expected_qids)
    except (HTTPError, URLError, TimeoutError, RuntimeError) as error:
        expected_coordinates = {}
        report["errors"].append(f"Coordonnées Wikidata : {error}")

    searches = 0
    for index, monument in enumerate(targets, start=1):
        reference = clean(monument.get("merimeeReference")).upper()
        current_title = title_from_wikipedia_url(monument.get("wikipediaUrl"))
        current_result = page_results.get(current_title, {})
        resolved_current_title = clean(current_result.get("title")) or current_title
        current_types = monument_types(resolved_current_title)
        expected_types = monument_types(clean(monument.get("name")))
        type_conflict = bool(expected_types and current_types and not (expected_types & current_types))
        is_generic = normalize(resolved_current_title) in GENERIC_TITLES
        exact_match = wikidata_matches.get(reference, {})
        exact_title = clean(exact_match.get("title"))
        exact_result = dict(page_results.get(exact_title, {})) if exact_title else {}
        expected_coordinate = expected_coordinates.get(clean(exact_match.get("qid")).upper())
        if expected_coordinate is not None:
            exact_result["latitude"], exact_result["longitude"] = expected_coordinate
            distance = article_distance(monument, exact_result)
            if distance is not None:
                exact_result["distanceMetres"] = round(distance)

        if exact_title and normalize(exact_title) != normalize(resolved_current_title):
            exact_is_strong = is_strong_exact_replacement(monument, exact_result)
            row = review_row(
                monument,
                signal="mismatch_ref_merimee_wikidata",
                severity="high" if exact_is_strong else "medium",
                current_title=resolved_current_title,
                expected=exact_result or exact_match,
            )
            row["action"] = saved_actions.get(row["id"], row["action"])
            report["review"].append(row)
            report["flagged"] += 1
            if args.apply_high_confidence and exact_is_strong:
                monument["description"] = exact_result["description"]
                monument["descriptionSource"] = "Wikipédia"
                monument["wikipediaUrl"] = exact_result.get("url") or exact_match.get("article_url")
                report["repaired"] += 1
            continue

        if exact_title or (not type_conflict and not is_generic):
            report["confirmed"] += 1
            continue

        if args.max_searches and searches >= args.max_searches:
            row = review_row(
                monument,
                signal="semantic_check_not_completed",
                severity="medium",
                current_title=resolved_current_title,
            )
            row["action"] = saved_actions.get(row["id"], row["action"])
            report["review"].append(row)
            report["flagged"] += 1
            continue

        suggestion: dict[str, Any] | None = None
        try:
            pages = wikipedia.search_pages(f'"{clean(monument.get("name"))}" Paris')
            searches += 1
            suggestion = candidate_from_search(monument, pages)
        except (HTTPError, URLError, TimeoutError, RuntimeError) as error:
            report["errors"].append(f"{reference or monument.get('id', '')}: {error}")

        if suggestion:
            suggestion_is_strong = is_strong_search_replacement(monument, suggestion)
            row = review_row(
                monument,
                signal="semantic_type_conflict" if type_conflict else "generic_wikipedia_title",
                severity="high" if suggestion_is_strong else "medium",
                current_title=resolved_current_title,
                suggestion=suggestion,
            )
            row["action"] = saved_actions.get(row["id"], row["action"])
            report["review"].append(row)
            report["flagged"] += 1
            if args.apply_high_confidence and suggestion_is_strong:
                monument["description"] = suggestion["description"]
                monument["descriptionSource"] = "Wikipédia"
                monument["wikipediaUrl"] = suggestion["url"]
                report["repaired"] += 1
        else:
            row = review_row(
                monument,
                signal="semantic_type_conflict" if type_conflict else "generic_wikipedia_title",
                severity="medium",
                current_title=resolved_current_title,
            )
            row["action"] = saved_actions.get(row["id"], row["action"])
            report["review"].append(row)
            report["flagged"] += 1
        if index % 25 == 0 or index == len(targets):
            print(f"Audit Wikipédia : {index}/{len(targets)}", file=sys.stderr)

    report["errors"] = report["errors"][:100]
    report["searches"] = searches
    report["requests"] = wikipedia.request_count + metadata.request_count
    report_path = args.report.resolve()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_review_csv(args.review_csv.resolve(), report["review"])
    cache.close()
    if args.apply_high_confidence and report["repaired"]:
        atomic_write_json(json_path, monuments)
    print(json.dumps({key: value for key, value in report.items() if key != "review"}, ensure_ascii=False, indent=2))
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("Interrompu.", file=sys.stderr)
        raise SystemExit(130)
