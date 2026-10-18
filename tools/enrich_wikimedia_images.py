#!/usr/bin/env python3
"""Enrich the local monument catalogue with reusable Wikimedia Commons images.

The script is intentionally conservative: it keeps only Public Domain, CC0,
CC BY and CC BY-SA files, and it uses a Mérimée reference before attempting a
small nearby-name fallback. It stores the thumbnail URL and the attribution
metadata required by the Commons file page.

Run from any directory:
    python C:/path/to/paris-fog-of-war/tools/enrich_wikimedia_images.py

The runtime app has the same conservative Commons lookup as a fallback for
catalogues generated without network access.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sys
import time
import unicodedata
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

from image_match import score_candidate

COMMONS_API = "https://commons.wikimedia.org/w/api.php"
WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"
REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
USER_AGENT = "LeFlaneurDeParis/0.1 (https://www.mediawiki.org/wiki/API:Etiquette)"
MIN_REQUEST_INTERVAL_SECONDS = 1.25
MAX_RETRIES = 6
LAST_REQUEST_AT = 0.0
ALLOWED_EXTENSIONS = {"image/jpeg", "image/png", "image/webp"}
GENERIC_TOKENS = {
    "ancien", "ancienne", "actuellement", "immeuble", "monument", "historique",
    "maison", "hotel", "hôtel", "paris", "eglise", "église", "chapelle",
}


def request_json(url: str, params: dict[str, str], timeout: int = 30) -> dict[str, Any]:
    request_url = f"{url}?{urlencode(params)}"
    request = Request(request_url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
    return open_json(request, timeout)


def post_json(
    url: str,
    params: dict[str, str],
    timeout: int = 60,
    accept: str = "application/sparql-results+json",
) -> dict[str, Any]:
    request = Request(
        url,
        data=urlencode(params).encode("utf-8"),
        headers={
            "Accept": accept,
            "Content-Type": "application/x-www-form-urlencoded",
            "User-Agent": USER_AGENT,
        },
        method="POST",
    )
    return open_json(request, timeout)


def open_json(request: Request, timeout: int) -> dict[str, Any]:
    """Open one API request, respecting throttling and retry instructions."""
    global LAST_REQUEST_AT
    for attempt in range(MAX_RETRIES + 1):
        wait = MIN_REQUEST_INTERVAL_SECONDS - (time.monotonic() - LAST_REQUEST_AT)
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
            backoff = min(120.0, 5.0 * (2 ** attempt))
            delay = max(server_wait, backoff)
            print(f"API temporairement limitée ({error.code}); nouvelle tentative dans {delay:.0f}s…", file=sys.stderr)
            time.sleep(delay)
        except (TimeoutError, URLError):
            if attempt >= MAX_RETRIES:
                raise
            delay = min(120.0, 5.0 * (2 ** attempt))
            print(f"API momentanément inaccessible; nouvelle tentative dans {delay:.0f}s…", file=sys.stderr)
            time.sleep(delay)


def clean_metadata(value: str | None) -> str | None:
    if not value:
        return None
    value = re.sub(r"<[^>]+>", " ", html.unescape(value))
    value = re.sub(r"\s+", " ", value).strip()
    return value or None


def allowed_license(value: str | None) -> bool:
    if not value:
        return False
    normalized = value.lower()
    if any(token in normalized for token in ("nc", "nd", "non-free", "non free", "fair use", "copyright", "gfdl")):
        return False
    return (
        "public domain" in normalized
        or "public-domain" in normalized
        or "cc0" in normalized
        or re.search(r"\bcc[- ]?by(?:[- ]?sa)?\b", normalized) is not None
    )


def license_url(value: str | None) -> str | None:
    if not value:
        return None
    normalized = value.lower()
    if "cc0" in normalized:
        return "https://creativecommons.org/publicdomain/zero/1.0/"
    match = re.search(r"(\d+(?:\.\d+)?)", normalized)
    if not match:
        return None
    family = "by-sa" if "sa" in normalized else "by"
    return f"https://creativecommons.org/licenses/{family}/{match.group(1)}/"


def parse_images(payload: dict[str, Any]) -> list[dict[str, Any]]:
    pages = payload.get("query", {}).get("pages", {})
    results: list[dict[str, Any]] = []
    for page in pages.values():
        info = (page.get("imageinfo") or [{}])[0]
        if info.get("mime") not in ALLOWED_EXTENSIONS:
            continue
        metadata = info.get("extmetadata") or {}
        license_name = clean_metadata((metadata.get("LicenseShortName") or {}).get("value"))
        if not allowed_license(license_name):
            continue
        title = page.get("title") or ""
        image_url = info.get("thumburl") or info.get("url")
        if not title or not image_url:
            continue
        results.append({
            "imageUrl": image_url.replace("http://", "https://", 1),
            "imagePageUrl": "https://commons.wikimedia.org/wiki/" + quote(title.replace(" ", "_"), safe="/:()"),
            "imageAuthor": clean_metadata((metadata.get("Artist") or {}).get("value"))
            or clean_metadata((metadata.get("Credit") or {}).get("value")),
            "imageLicense": license_name,
            "imageLicenseUrl": clean_metadata((metadata.get("LicenseUrl") or {}).get("value")) or license_url(license_name),
            "imageSource": "Wikimedia Commons",
            "_title": title,
            "_description": clean_metadata((metadata.get("ImageDescription") or {}).get("value")) or "",
            "_latitude": ((page.get("coordinates") or [{}])[0]).get("lat"),
            "_longitude": ((page.get("coordinates") or [{}])[0]).get("lon"),
        })
    return results


def normalize(value: str) -> str:
    value = unicodedata.normalize("NFKD", value.lower()).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", " ", value).strip()


def matches_name(image: dict[str, Any], monument_name: str) -> bool:
    haystack = normalize(f"{image.get('_title', '')} {image.get('_description', '')}")
    name = normalize(monument_name)
    if len(name) >= 8 and name in haystack:
        return True
    tokens = [token for token in name.split() if len(token) >= 5 and token not in GENERIC_TOKENS]
    return bool(tokens) and sum(token in haystack for token in tokens) >= max(1, (len(tokens) + 1) // 2)


def candidate_matches_monument(image: dict[str, Any], item: dict[str, Any]) -> bool:
    return score_candidate(
        candidate_text=f'{image.get("_title", "")} {image.get("_description", "")}',
        monument_name=item.get("name", ""),
        wikipedia_url=item.get("wikipediaUrl"),
        merimee_reference=item.get("merimeeReference"),
        candidate_latitude=image.get("_latitude"),
        candidate_longitude=image.get("_longitude"),
        monument_latitude=item.get("latitude", 0),
        monument_longitude=item.get("longitude", 0),
    ) >= 70


def commons_search(params: dict[str, str], use_post: bool = False) -> list[dict[str, Any]]:
    request_params = {
        "action": "query",
        "prop": "imageinfo",
        "iiprop": "url|mime|extmetadata",
        "iiurlwidth": "900",
        "format": "json",
        "origin": "*",
        "maxlag": "5",
        **params,
    }
    payload = (
        post_json(COMMONS_API, request_params, accept="application/json")
        if use_post
        else request_json(COMMONS_API, request_params)
    )
    return parse_images(payload)


def wikidata_images(references: list[str], batch_size: int = 120) -> dict[str, str]:
    matches: dict[str, str] = {}
    for offset in range(0, len(references), batch_size):
        batch = references[offset: offset + batch_size]
        values = " ".join(json.dumps(reference) for reference in batch)
        query = f"""
            SELECT ?merimee ?image WHERE {{
                VALUES ?merimee {{ {values} }}
                ?item wdt:P380 ?merimee ; wdt:P18 ?image .
            }}
        """
        try:
            payload = post_json(WIKIDATA_SPARQL, {"query": query, "format": "json"})
        except (HTTPError, URLError, TimeoutError) as error:
            print(f"Wikidata batch {offset // batch_size + 1} failed: {error}", file=sys.stderr)
            continue
        for row in payload.get("results", {}).get("bindings", []):
            reference = row.get("merimee", {}).get("value")
            image = row.get("image", {}).get("value")
            if reference and image and reference not in matches:
                matches[reference] = image.removeprefix("File:")
        time.sleep(0.2)
    return matches


def image_info_for_titles(titles: list[str]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for offset in range(0, len(titles), 50):
        batch = titles[offset: offset + 50]
        try:
            images = commons_search(
                {"titles": "|".join(f"File:{title}" for title in batch)},
                use_post=True,
            )
        except (HTTPError, URLError, TimeoutError) as error:
            print(f"Commons metadata batch {offset // 50 + 1} failed: {error}", file=sys.stderr)
            continue
        for image in images:
            result[image["_title"].removeprefix("File:")] = image
    return result


def commons_reference_search(references: list[str], batch_size: int = 25) -> dict[str, dict[str, Any]]:
    """Search many Mérimée references with one Commons request per batch."""
    result: dict[str, dict[str, Any]] = {}
    for offset in range(0, len(references), batch_size):
        batch = references[offset: offset + batch_size]
        search = "insource:/" + "|".join(re.escape(reference) for reference in batch) + "/"
        try:
            images = commons_search({
                "generator": "search",
                "gsrsearch": search,
                "gsrnamespace": "6",
                "gsrlimit": "500",
            }, use_post=True)
        except (HTTPError, URLError, TimeoutError) as error:
            print(f"Recherche Commons groupée échouée pour le lot {offset // batch_size + 1}: {error}", file=sys.stderr)
            continue
        for image in images:
            haystack = f'{image.get("_title", "")} {image.get("_description", "")}'
            for reference in batch:
                if reference in haystack and reference not in result:
                    result[reference] = image
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=REPOSITORY_ROOT / "app/src/main/assets/paris_monuments.json")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--skip-fallback", action="store_true", help="Only use Wikidata P380/P18 exact matches")
    parser.add_argument("--geosearch", action="store_true", help="Also perform slow nearby-name searches for unresolved monuments")
    parser.add_argument("--geosearch-limit", type=int, default=100, help="Maximum nearby-name searches when --geosearch is used")
    args = parser.parse_args()
    output = args.output or args.input

    monuments = json.loads(args.input.read_text(encoding="utf-8"))
    references = sorted({item["merimeeReference"] for item in monuments if item.get("merimeeReference")})
    print(f"Recherche Wikidata pour {len(references)} références Mérimée…")
    wikidata_by_reference = wikidata_images(references)
    image_by_title = image_info_for_titles(sorted(set(wikidata_by_reference.values())))
    commons_by_reference: dict[str, dict[str, Any]] = {}
    unresolved_references = [reference for reference in references if reference not in wikidata_by_reference]
    if not args.skip_fallback and unresolved_references:
        print(f"Recherche Commons groupée pour {len(unresolved_references)} références restantes…")
        commons_by_reference = commons_reference_search(unresolved_references)

    found = 0
    geosearch_count = 0
    for item in monuments:
        # Drop legacy OSM/Google Photos links unless a fresh reusable record replaces them.
        for key in ("imageUrl", "imagePageUrl", "imageAuthor", "imageLicense", "imageLicenseUrl", "imageSource"):
            item[key] = None
        item["imageLookupCompleted"] = False

        reference = item.get("merimeeReference")
        candidate = image_by_title.get(wikidata_by_reference.get(reference, "")) if reference else None
        if candidate is None and reference:
            candidate = commons_by_reference.get(reference)

        if candidate is None and args.geosearch and geosearch_count < args.geosearch_limit:
            geosearch_count += 1
            try:
                candidate = next((image for image in commons_search({
                    "generator": "geosearch",
                    "ggsprimary": "all",
                    "ggsnamespace": "6",
                    "ggsradius": "250",
                    "ggscoord": f'{item["latitude"]}|{item["longitude"]}',
                    "ggslimit": "20",
                }) if matches_name(image, item.get("name", ""))), None)
            except (HTTPError, URLError, TimeoutError) as error:
                print(f"Recherche géographique échouée pour {item.get('name')}: {error}", file=sys.stderr)

        if candidate is not None and not candidate_matches_monument(candidate, item):
            print(f"Candidat Commons rejeté après vérification: {item.get('name')} ({item.get('merimeeReference')})", file=sys.stderr)
            candidate = None

        if candidate is not None:
            found += 1
            for key in ("imageUrl", "imagePageUrl", "imageAuthor", "imageLicense", "imageLicenseUrl", "imageSource"):
                item[key] = candidate.get(key)
        item["imageLookupCompleted"] = True

        if found and found % 25 == 0:
            print(f"{found} images retenues…")

    output.write_text(json.dumps(monuments, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Terminé : {found}/{len(monuments)} monuments avec image réutilisable.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
