#!/usr/bin/env python3
"""Complete missing monument descriptions from French Wikipedia.

The application already displays the local ``description`` field when a POP
historical description is unavailable.  This script fills that field only for
monuments which have no POP description and no existing description.

The lookup order is deliberately conservative:

1. use the French Wikipedia URL already present in the catalogue;
2. otherwise search French Wikipedia by monument name and Paris, accepting a
   result only when its title is sufficiently close to the monument and its
   lead extract is usable;
3. keep the monument empty when no reliable page is found.

Wikipedia API responses are stored in a SQLite cache, including negative
results.  Re-running the script therefore does not repeat completed lookups.
The resulting short lead extracts remain in the application asset, while the
``wikipediaUrl`` field provides the source page in the app.

Examples:

    python tools/enrich_missing_descriptions.py
    python tools/enrich_missing_descriptions.py --dry-run
    python tools/enrich_missing_descriptions.py --request-interval 0.5
"""

from __future__ import annotations

import argparse
import difflib
import json
import re
import sqlite3
import sys
import tempfile
import time
import unicodedata
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import quote, unquote, urlencode, urlparse
from urllib.request import Request, urlopen


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JSON_PATH = REPOSITORY_ROOT / "app" / "src" / "main" / "assets" / "paris_monuments.json"
DEFAULT_CACHE_PATH = REPOSITORY_ROOT / "outputs" / "description_enrichment" / "wikipedia_description_cache.sqlite3"
DEFAULT_REPORT_PATH = REPOSITORY_ROOT / "outputs" / "description_enrichment" / "description_enrichment_report.json"
WIKIPEDIA_API = "https://fr.wikipedia.org/w/api.php"
USER_AGENT = "LeFlaneurDeParis-description-enrichment/1.0 (https://www.mediawiki.org/wiki/API:Etiquette)"
MAX_RETRIES = 4

GENERIC_TOKENS = {
    "ancien", "ancienne", "actuel", "actuellement", "autre", "bains", "batiment",
    "chapelle", "chateau", "eglise", "ensemble", "fontaine", "hotel", "immeuble",
    "historique", "maison", "monument", "palais", "paris", "porte", "site", "tour",
}
STOPWORDS = {"a", "au", "aux", "de", "des", "du", "en", "et", "la", "le", "les", "l", "un", "une"}
REFERENCE_TOKEN = re.compile(r"^(?:PA|IA|AC|MH)[A-Z0-9_-]*\d[A-Z0-9_-]*$", re.IGNORECASE)


def clean(value: Any) -> str:
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value)).strip()


def normalize(value: str) -> str:
    value = unicodedata.normalize("NFKD", value.casefold())
    value = "".join(char for char in value if not unicodedata.combining(char))
    return re.sub(r"[^a-z0-9]+", " ", value).strip()


def meaningful_tokens(value: str) -> set[str]:
    return {
        token
        for token in normalize(value).split()
        if len(token) >= 3 and token not in GENERIC_TOKENS and token not in STOPWORDS
        and not REFERENCE_TOKEN.fullmatch(token)
    }


def title_similarity(source_name: str, page_title: str) -> tuple[float, float]:
    source = normalize(source_name)
    title = normalize(page_title)
    source_tokens = meaningful_tokens(source_name)
    title_tokens = meaningful_tokens(page_title)
    overlap = len(source_tokens & title_tokens) / max(1, len(source_tokens))
    return difflib.SequenceMatcher(None, source, title).ratio(), overlap


def usable_extract(value: Any) -> str:
    text = clean(value)
    text = re.sub(r"\[\d+(?:,\s*\d+)*\]", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    if not text or len(text) < 60:
        return ""
    lowered = normalize(text)
    if any(
        marker in lowered
        for marker in (
            "page dhomonymie",
            "homonymie",
            "desambiguïsation",
            "peut designer plusieurs sujets",
            "peut faire reference a",
        )
    ):
        return ""
    return text[:1000].rstrip()


def title_from_wikipedia_url(value: Any) -> str:
    raw = clean(value)
    if not raw:
        return ""
    parsed = urlparse(raw)
    if parsed.netloc.casefold() not in {"fr.wikipedia.org", "www.fr.wikipedia.org"}:
        return ""
    prefix = "/wiki/"
    if not parsed.path.startswith(prefix):
        return ""
    title = unquote(parsed.path[len(prefix):]).replace("_", " ").strip()
    if not title or title.casefold().startswith(("special:", "categorie:", "category:")):
        return ""
    return title


def wikipedia_url(title: str) -> str:
    return "https://fr.wikipedia.org/wiki/" + quote(title.replace(" ", "_"), safe="():,'-_")


class ResponseCache:
    """Small persistent JSON cache for successful and negative API results."""

    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path, timeout=60)
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS wikipedia_cache (
                namespace TEXT NOT NULL,
                cache_key TEXT NOT NULL,
                value_json TEXT NOT NULL,
                updated_at REAL NOT NULL,
                PRIMARY KEY(namespace, cache_key)
            )
            """
        )
        self.connection.commit()

    def get(self, namespace: str, key: str) -> dict[str, Any] | None:
        row = self.connection.execute(
            "SELECT value_json FROM wikipedia_cache WHERE namespace=? AND cache_key=?",
            (namespace, key),
        ).fetchone()
        if not row:
            return None
        try:
            value = json.loads(row[0])
        except json.JSONDecodeError:
            return None
        return value if isinstance(value, dict) else None

    def set(self, namespace: str, key: str, value: dict[str, Any]) -> None:
        self.connection.execute(
            "INSERT OR REPLACE INTO wikipedia_cache(namespace, cache_key, value_json, updated_at) VALUES (?, ?, ?, ?)",
            (namespace, key, json.dumps(value, ensure_ascii=False), time.time()),
        )
        self.connection.commit()

    def close(self) -> None:
        self.connection.close()


class WikipediaClient:
    def __init__(self, cache: ResponseCache, request_interval: float):
        self.cache = cache
        self.request_interval = max(0.0, request_interval)
        self.last_request_at = 0.0
        self.request_count = 0

    def request(self, params: dict[str, str]) -> dict[str, Any]:
        request_url = f"{WIKIPEDIA_API}?{urlencode(params)}"
        request = Request(
            request_url,
            headers={"Accept": "application/json", "User-Agent": USER_AGENT},
        )
        for attempt in range(MAX_RETRIES + 1):
            delay = self.request_interval - (time.monotonic() - self.last_request_at)
            if delay > 0:
                time.sleep(delay)
            self.last_request_at = time.monotonic()
            try:
                with urlopen(request, timeout=45) as response:
                    payload = json.load(response)
                self.request_count += 1
                if "error" in payload:
                    raise RuntimeError(str(payload["error"]))
                return payload
            except HTTPError as error:
                retryable = error.code in {429, 500, 502, 503, 504}
                if not retryable or attempt >= MAX_RETRIES:
                    raise
                time.sleep(min(60.0, 2.0 ** (attempt + 1)))
            except (TimeoutError, URLError):
                if attempt >= MAX_RETRIES:
                    raise
                time.sleep(min(60.0, 2.0 ** (attempt + 1)))
        raise RuntimeError("Échec inattendu de la requête Wikipédia")

    @staticmethod
    def pages_from_payload(payload: dict[str, Any]) -> list[dict[str, Any]]:
        pages = payload.get("query", {}).get("pages", [])
        if isinstance(pages, dict):
            pages = list(pages.values())
        return [page for page in pages if isinstance(page, dict)]

    def exact_pages(self, titles: Iterable[str]) -> dict[str, dict[str, Any]]:
        requested = list(dict.fromkeys(title for title in titles if title))
        results: dict[str, dict[str, Any]] = {}
        missing = [title for title in requested if self.cache.get("extract", normalize(title)) is None]
        for start in range(0, len(missing), 20):
            batch = missing[start : start + 20]
            try:
                payload = self.request(
                    {
                        "action": "query",
                        "prop": "extracts|info",
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
            except (HTTPError, URLError, TimeoutError, RuntimeError) as error:
                raise RuntimeError(f"Lecture Wikipédia impossible pour le lot {start + 1}: {error}") from error
            pages = self.pages_from_payload(payload)
            by_title = {normalize(clean(page.get("title"))): page for page in pages}
            for requested_title in batch:
                page = by_title.get(normalize(requested_title))
                if page is None:
                    self.cache.set("extract", normalize(requested_title), {"found": False})
                else:
                    result = self.page_result(page)
                    self.cache.set("extract", normalize(requested_title), result)
        for title in requested:
            cached = self.cache.get("extract", normalize(title))
            if cached:
                results[title] = cached
        return results

    def search_pages(self, query: str) -> list[dict[str, Any]]:
        cache_key = normalize(query)
        cached = self.cache.get("search", cache_key)
        if cached is not None:
            return list(cached.get("pages") or [])
        payload = self.request(
            {
                "action": "query",
                "generator": "search",
                "gsrsearch": query,
                "gsrnamespace": "0",
                "gsrlimit": "8",
                "prop": "extracts|coordinates|info",
                "inprop": "url",
                "exintro": "1",
                "explaintext": "1",
                "exsentences": "3",
                "exlimit": "max",
                "redirects": "1",
                "format": "json",
                "formatversion": "2",
            }
        )
        pages = self.pages_from_payload(payload)
        self.cache.set("search", cache_key, {"pages": pages})
        return pages

    @staticmethod
    def page_result(page: dict[str, Any]) -> dict[str, Any]:
        title = clean(page.get("title"))
        extract = usable_extract(page.get("extract"))
        return {
            "found": bool(title),
            "title": title,
            "description": extract,
            "url": clean(page.get("fullurl")) or wikipedia_url(title),
        }


def page_coordinates(page: dict[str, Any]) -> tuple[float, float] | None:
    coordinates = page.get("coordinates") or []
    if not isinstance(coordinates, list) or not coordinates:
        return None
    coordinate = coordinates[0]
    try:
        return float(coordinate["lat"]), float(coordinate["lon"])
    except (KeyError, TypeError, ValueError):
        return None


def haversine_metres(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    import math

    radius = 6_371_000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    value = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return radius * 2 * math.atan2(math.sqrt(value), math.sqrt(1 - value))


def choose_page(monument: dict[str, Any], pages: list[dict[str, Any]]) -> dict[str, Any] | None:
    name = clean(monument.get("name"))
    if not name or not meaningful_tokens(name):
        return None
    candidates: list[tuple[float, dict[str, Any]]] = []
    for page in pages:
        title = clean(page.get("title"))
        extract = usable_extract(page.get("extract"))
        if not title or not extract:
            continue
        similarity, overlap = title_similarity(name, title)
        if overlap == 0:
            continue
        distance = None
        coordinates = page_coordinates(page)
        if coordinates and monument.get("latitude") is not None and monument.get("longitude") is not None:
            try:
                distance = haversine_metres(
                    float(monument["latitude"]),
                    float(monument["longitude"]),
                    coordinates[0],
                    coordinates[1],
                )
            except (TypeError, ValueError):
                distance = None
        if distance is not None:
            accepted = distance <= 1_500 and overlap >= 0.50 and similarity >= 0.42
        else:
            accepted = overlap >= 0.66 and similarity >= 0.62
        if not accepted:
            continue
        score = 0.55 * similarity + 0.45 * overlap
        if distance is not None:
            score += max(0.0, 0.20 * (1.0 - distance / 1_500))
        candidates.append((score, page))
    if not candidates:
        return None
    candidates.sort(key=lambda item: (-item[0], clean(item[1].get("title"))))
    best_score, best_page = candidates[0]
    if len(candidates) > 1 and best_score - candidates[1][0] < 0.06:
        return None
    return WikipediaClient.page_result(best_page)


def query_for_monument(monument: dict[str, Any]) -> list[str]:
    name = clean(monument.get("name"))
    queries = [f'"{name}" Paris']
    if name:
        queries.append(f"{name} Paris")
    return list(dict.fromkeys(queries))


def atomic_write_json(path: Path, monuments: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            dir=path.parent,
            prefix=f"{path.stem}-",
            suffix=".tmp",
            delete=False,
        ) as handle:
            temporary_path = Path(handle.name)
            json.dump(monuments, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        temporary_path.replace(path)
    finally:
        if temporary_path and temporary_path.exists():
            temporary_path.unlink()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON_PATH, help="asset JSON à compléter")
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE_PATH, help="cache SQLite des recherches Wikipédia")
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT_PATH, help="rapport JSON de l'enrichissement")
    parser.add_argument("--request-interval", type=float, default=0.35, help="délai minimal entre deux requêtes (secondes)")
    parser.add_argument("--max-searches", type=int, default=0, help="limiter les recherches de pages sans lien (0 = sans limite)")
    parser.add_argument("--dry-run", action="store_true", help="chercher et produire le rapport sans modifier l'asset")
    args = parser.parse_args()

    json_path = args.json.resolve()
    monuments = json.loads(json_path.read_text(encoding="utf-8"))
    if not isinstance(monuments, list):
        raise ValueError(f"L'asset doit contenir une liste JSON : {json_path}")

    cache = ResponseCache(args.cache.resolve())
    client = WikipediaClient(cache, args.request_interval)
    report: dict[str, Any] = {
        "asset": str(json_path),
        "cache": str(args.cache.resolve()),
        "total_monuments": len(monuments),
        "without_pop_description": 0,
        "skipped_existing_description": 0,
        "completed": 0,
        "not_found": 0,
        "errors": [],
        "sources": {"existing_wikipedia_url": 0, "wikipedia_search": 0},
        "requests": 0,
        "dry_run": args.dry_run,
    }
    targets: list[dict[str, Any]] = []
    for monument in monuments:
        if clean(monument.get("popHistoricalDescription")):
            continue
        report["without_pop_description"] += 1
        if clean(monument.get("description")):
            report["skipped_existing_description"] += 1
        else:
            targets.append(monument)

    direct_titles = {
        title_from_wikipedia_url(monument.get("wikipediaUrl"))
        for monument in targets
        if title_from_wikipedia_url(monument.get("wikipediaUrl"))
    }
    try:
        direct_results = client.exact_pages(direct_titles)
        search_count = 0
        for index, monument in enumerate(targets, start=1):
            result: dict[str, Any] | None = None
            direct_title = title_from_wikipedia_url(monument.get("wikipediaUrl"))
            if direct_title:
                result = direct_results.get(direct_title)
                if result and result.get("description"):
                    report["sources"]["existing_wikipedia_url"] += 1
            if not result or not result.get("description"):
                if args.max_searches and search_count >= args.max_searches:
                    report["not_found"] += 1
                    continue
                for query in query_for_monument(monument):
                    search_count += 1
                    try:
                        result = choose_page(monument, client.search_pages(query))
                    except (HTTPError, URLError, TimeoutError, RuntimeError) as error:
                        report["errors"].append(f"{monument.get('id', monument.get('name', ''))}: {error}")
                        result = None
                        break
                    if result:
                        report["sources"]["wikipedia_search"] += 1
                        break
            if result and result.get("description"):
                if not args.dry_run:
                    monument["description"] = result["description"]
                    monument["descriptionSource"] = "Wikipédia"
                    if result.get("url"):
                        monument["wikipediaUrl"] = result["url"]
                report["completed"] += 1
            else:
                report["not_found"] += 1
            if index % 25 == 0 or index == len(targets):
                print(f"Descriptions complémentaires : {index}/{len(targets)}", file=sys.stderr)
    finally:
        report["requests"] = client.request_count
        cache.close()

    report["errors"] = report["errors"][:100]
    report_path = args.report.resolve()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if not args.dry_run:
        atomic_write_json(json_path, monuments)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("Interrompu.", file=sys.stderr)
        raise SystemExit(130)
