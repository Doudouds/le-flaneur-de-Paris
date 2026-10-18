#!/usr/bin/env python3
"""Audit monument image assignments against Wikimedia Commons metadata.

The default mode checks image records already embedded in the catalogue. With
``--all`` it also searches Commons for every Mérimée reference, then applies
the same conservative name/alias/location checks used by the Android resolver.
The audit is read-only: it writes a CSV/JSON report and never changes the
catalogue.

Examples:
    python tools/audit_wikimedia_images.py
    python tools/audit_wikimedia_images.py --all
    python tools/audit_wikimedia_images.py --all --output-dir outputs/image_audit
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import re
import sys
import time
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, unquote, urlencode, urlparse
from urllib.request import Request, urlopen

from image_match import score_candidate

COMMONS_API = "https://commons.wikimedia.org/w/api.php"
REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INPUT = REPOSITORY_ROOT / "app/src/main/assets/paris_monuments.json"
USER_AGENT = "LeFlaneurDeParis/0.1 (Wikimedia image audit)"
ALLOWED_MIME = {"image/jpeg", "image/png", "image/webp"}
MIN_REQUEST_INTERVAL = 1.1
MAX_RETRIES = 5
LAST_REQUEST_AT = 0.0


def request_json(params: dict[str, str], timeout: int = 45) -> dict[str, Any]:
    global LAST_REQUEST_AT
    request = Request(
        f"{COMMONS_API}?{urlencode({**params, 'maxlag': '5'})}",
        headers={"Accept": "application/json", "User-Agent": USER_AGENT},
    )
    for attempt in range(MAX_RETRIES + 1):
        wait = MIN_REQUEST_INTERVAL - (time.monotonic() - LAST_REQUEST_AT)
        if wait > 0:
            time.sleep(wait)
        LAST_REQUEST_AT = time.monotonic()
        try:
            with urlopen(request, timeout=timeout) as response:
                return json.load(response)
        except HTTPError as error:
            if error.code not in {429, 500, 502, 503, 504} or attempt >= MAX_RETRIES:
                raise
            retry_after = error.headers.get("Retry-After") if error.headers else None
            try:
                server_wait = float(retry_after) if retry_after else 0.0
            except ValueError:
                server_wait = 0.0
            delay = max(server_wait, min(120.0, 8.0 * (2 ** attempt)))
            print(f"Commons limité ({error.code}); nouvelle tentative dans {delay:.0f}s…", file=sys.stderr)
            time.sleep(delay)
        except (TimeoutError, URLError):
            if attempt >= MAX_RETRIES:
                raise
            delay = min(120.0, 8.0 * (2 ** attempt))
            print(f"Commons momentanément inaccessible; nouvelle tentative dans {delay:.0f}s…", file=sys.stderr)
            time.sleep(delay)
    raise RuntimeError("Commons request exhausted retries")


def clean_metadata(value: str | None) -> str:
    if not value:
        return ""
    value = re.sub(r"<[^>]+>", " ", html.unescape(value))
    return re.sub(r"\s+", " ", value).strip()


def metadata_text(metadata: dict[str, Any], key: str) -> str:
    value = metadata.get(key) or {}
    return clean_metadata(value.get("value") if isinstance(value, dict) else None)


def license_is_reusable(value: str) -> bool:
    normalized = value.lower()
    if any(token in normalized for token in ("nc", "nd", "non-free", "non free", "fair use", "copyright", "gfdl")):
        return False
    return (
        "public domain" in normalized
        or "public-domain" in normalized
        or "cc0" in normalized
        or re.search(r"\bcc[- ]?by(?:[- ]?sa)?\b", normalized) is not None
    )


def license_url(value: str) -> str | None:
    normalized = value.lower()
    if "cc0" in normalized:
        return "https://creativecommons.org/publicdomain/zero/1.0/"
    match = re.search(r"(\d+(?:\.\d+)?)", normalized)
    if not match:
        return None
    family = "by-sa" if "sa" in normalized else "by"
    return f"https://creativecommons.org/licenses/{family}/{match.group(1)}/"


def page_url(title: str) -> str:
    return "https://commons.wikimedia.org/wiki/" + quote(title.replace(" ", "_"), safe="/:()")


def parse_candidate(page: dict[str, Any]) -> dict[str, Any] | None:
    title = page.get("title") or ""
    info = (page.get("imageinfo") or [{}])[0]
    if not title or not info:
        return None
    metadata = info.get("extmetadata") or {}
    license_name = metadata_text(metadata, "LicenseShortName")
    description = metadata_text(metadata, "ImageDescription")
    categories = metadata_text(metadata, "Categories")
    coordinates = (page.get("coordinates") or [{}])[0]
    latitude = coordinates.get("lat")
    longitude = coordinates.get("lon")
    return {
        "title": title,
        "pageUrl": page_url(title),
        "imageUrl": info.get("thumburl") or info.get("url") or "",
        "mime": info.get("mime") or "",
        "license": license_name,
        "licenseUrl": metadata_text(metadata, "LicenseUrl") or license_url(license_name),
        "author": metadata_text(metadata, "Artist") or metadata_text(metadata, "Credit"),
        "text": f"{title} {description} {categories}",
        "latitude": latitude if isinstance(latitude, (int, float)) else None,
        "longitude": longitude if isinstance(longitude, (int, float)) else None,
        "reusable": info.get("mime") in ALLOWED_MIME and license_is_reusable(license_name),
    }


def parse_pages(payload: dict[str, Any]) -> list[dict[str, Any]]:
    pages = payload.get("query", {}).get("pages", {})
    return [candidate for page in pages.values() if (candidate := parse_candidate(page)) is not None]


def image_title_from_url(value: str | None) -> str | None:
    if not value:
        return None
    parsed = urlparse(value)
    path = unquote(parsed.path)
    if "/wiki/" in path:
        title = path.split("/wiki/", 1)[1]
    elif "/Special:FilePath/" in path:
        title = path.split("/Special:FilePath/", 1)[1]
    else:
        return None
    return title.removeprefix("File:").replace("_", " ")


def search_by_references(references: list[str]) -> dict[str, list[dict[str, Any]]]:
    matches: dict[str, list[dict[str, Any]]] = {reference: [] for reference in references}
    for offset in range(0, len(references), 25):
        batch = references[offset: offset + 25]
        # MediaWiki's search parser does not evaluate an OR made of several
        # quoted insource clauses reliably. A single regex keeps the batch
        # query exact while returning files whose title/description contains
        # one of the references.
        search = "insource:/" + "|".join(re.escape(reference) for reference in batch) + "/"
        try:
            payload = request_json({
                "action": "query",
                "generator": "search",
                "gsrsearch": search,
                "gsrnamespace": "6",
                "gsrlimit": "500",
                "prop": "imageinfo|coordinates",
                "iiprop": "url|mime|extmetadata",
                "iiurlwidth": "900",
                "format": "json",
            })
        except (HTTPError, URLError, TimeoutError) as error:
            print(f"Lot Commons {offset // 25 + 1} échoué: {error}", file=sys.stderr)
            continue
        candidates = parse_pages(payload)
        for candidate in candidates:
            for reference in batch:
                if reference.lower() in candidate["text"].lower():
                    matches[reference].append(candidate)
        print(f"Références vérifiées: {min(offset + len(batch), len(references))}/{len(references)}", file=sys.stderr)
    return matches


def fetch_titles(titles: list[str]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    unique_titles = list(dict.fromkeys(title for title in titles if title))
    for offset in range(0, len(unique_titles), 50):
        batch = unique_titles[offset: offset + 50]
        try:
            payload = request_json({
                "action": "query",
                "titles": "|".join(f"File:{title.removeprefix('File:')}" for title in batch),
                "prop": "imageinfo|coordinates",
                "iiprop": "url|mime|extmetadata",
                "iiurlwidth": "900",
                "format": "json",
            })
        except (HTTPError, URLError, TimeoutError) as error:
            print(f"Métadonnées Commons lot {offset // 50 + 1} échoué: {error}", file=sys.stderr)
            continue
        for candidate in parse_pages(payload):
            result[candidate["title"].removeprefix("File:")] = candidate
    return result


def audit(monuments: list[dict[str, Any]], all_monuments: bool) -> list[dict[str, Any]]:
    embedded_titles = [image_title_from_url(item.get("imagePageUrl")) for item in monuments]
    embedded = fetch_titles([title for title in embedded_titles if title])
    by_reference: dict[str, list[dict[str, Any]]] = {}
    if all_monuments:
        references = sorted({item["merimeeReference"] for item in monuments if item.get("merimeeReference")})
        by_reference = search_by_references(references)

    rows: list[dict[str, Any]] = []
    for item in monuments:
        reference = item.get("merimeeReference") or ""
        current_title = image_title_from_url(item.get("imagePageUrl"))
        current = embedded.get(current_title.removeprefix("File:")) if current_title else None
        candidates = list(by_reference.get(reference, []))
        if current and all(candidate.get("title") != current.get("title") for candidate in candidates):
            candidates.append(current)
        scored = []
        for candidate in candidates:
            score = score_candidate(
                candidate_text=candidate["text"],
                monument_name=item.get("name", ""),
                wikipedia_url=item.get("wikipediaUrl"),
                merimee_reference=reference or None,
                candidate_latitude=candidate.get("latitude"),
                candidate_longitude=candidate.get("longitude"),
                monument_latitude=item.get("latitude", 0),
                monument_longitude=item.get("longitude", 0),
            )
            scored.append((score, candidate))
        best_score, best = max(scored, key=lambda pair: pair[0], default=(0, None))

        if current is not None:
            current_score = next((score for score, candidate in scored if candidate.get("title") == current.get("title")), 0)
            if not current.get("reusable"):
                status = "unlicensed"
            elif current_score < 70:
                status = "mismatch"
            else:
                status = "ok"
            score = current_score
        elif not all_monuments:
            status = "not_embedded"
            score = 0
        elif best is None:
            status = "no_verified_candidate"
            score = 0
        elif not best.get("reusable"):
            status = "candidate_unlicensed"
            score = best_score
        elif best_score < 70:
            status = "candidate_mismatch"
            score = best_score
        else:
            status = "candidate_ok"
            score = best_score

        rows.append({
            "id": item.get("id", ""),
            "merimeeReference": reference,
            "name": item.get("name", ""),
            "status": status,
            "score": score,
            "currentImagePageUrl": current.get("pageUrl", "") if current else "",
            "candidateImagePageUrl": best.get("pageUrl", "") if best else "",
            "candidateLicense": best.get("license", "") if best else "",
            "candidateLatitude": best.get("latitude", "") if best else "",
            "candidateLongitude": best.get("longitude", "") if best else "",
        })
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output-dir", type=Path, default=REPOSITORY_ROOT / "outputs/image_audit")
    parser.add_argument("--all", action="store_true", help="Search and audit Commons candidates for every Mérimée reference")
    args = parser.parse_args()

    monuments = json.loads(args.input.read_text(encoding="utf-8"))
    rows = audit(monuments, all_monuments=args.all)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    csv_path = args.output_dir / "wikimedia_image_audit.csv"
    json_path = args.output_dir / "wikimedia_image_audit.json"
    with csv_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()) if rows else ["status"])
        writer.writeheader()
        writer.writerows(rows)
    summary: dict[str, Any] = {
        "input": str(args.input),
        "allMonuments": args.all,
        "monuments": len(rows),
        "statusCounts": {},
        "reviewRequired": sum(row["status"] in {"mismatch", "unlicensed", "candidate_mismatch", "candidate_unlicensed"} for row in rows),
    }
    for row in rows:
        summary["statusCounts"][row["status"]] = summary["statusCounts"].get(row["status"], 0) + 1
    json_path.write_text(json.dumps({"summary": summary, "rows": rows}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"Rapport CSV: {csv_path}")
    return 0 if summary["reviewRequired"] == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
