#!/usr/bin/env python3
"""Complete monument metadata from POP, Wikidata and Wikipedia.

This is the second enrichment pass after ``enrich_missing_descriptions.py``.
It implements three conservative fallbacks:

* POP structured fields (title, denomination, dating, protection) produce a
  factual description when POP.HIST is empty;
* an exact Mérimée reference is resolved through Wikidata P380 before using a
  Wikipedia page, avoiding a loose name-only association;
* raw Wikipedia links from the source CSV are retried and cross-checked with
  the exact Wikidata match.

The script also preserves historical dates and derives one display period:
Préhistoire, Antiquité, Moyen Âge, Renaissance, Époque moderne or Époque
contemporaine. Missing or ambiguous dates remain unclassified.

The cache is shared with ``enrich_missing_descriptions.py``. The source CSV is
optional, but recommended because it contains the original construction and
protection date fields without requiring one POP request per monument.

Example:

    python tools/enrich_monument_metadata.py \
      --source-csv "C:/path/to/export.csv"
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import time
import unicodedata
from http.client import IncompleteRead
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import quote, unquote, urlencode, urlparse
from urllib.request import Request, urlopen

from enrich_missing_descriptions import (
    DEFAULT_CACHE_PATH,
    DEFAULT_JSON_PATH,
    ResponseCache,
    WikipediaClient,
    atomic_write_json,
    clean,
    normalize,
    usable_extract,
)


DEFAULT_SOURCE_CSV = Path(
    r"C:\Users\edoua\Downloads\Immeubles protégés au titre des Monuments Historiques-Analyser_Merge_Heritage_FR_Merimee.byOSM.csv\Immeubles protégés au titre des Monuments Historiques-Analyser_Merge_Heritage_FR_Merimee.byOSM.csv"
)
DEFAULT_METADATA_REPORT_PATH = DEFAULT_CACHE_PATH.parent / "metadata_enrichment_report.json"
POP_API = "https://api.pop.culture.gouv.fr/notices/merimee/"
WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"
USER_AGENT = "LeFlaneurDeParis-metadata-enrichment/1.0 (https://www.mediawiki.org/wiki/API:Etiquette)"
MAX_RETRIES = 4

PERIOD_PREHISTORY = "Préhistoire"
PERIOD_ANTIQUITY = "Antiquité"
PERIOD_MIDDLE_AGES = "Moyen Âge"
PERIOD_RENAISSANCE = "Renaissance"
PERIOD_MODERN = "Époque moderne"
PERIOD_CONTEMPORARY = "Époque contemporaine"

# These are subject pages rather than pages about a particular Parisian
# monument.  They are only used as a repair signal when no exact Mérimée /
# Wikidata association confirms the Wikipedia page.
GENERIC_WIKIPEDIA_TITLES = {
    "architecture",
    "boucherie",
    "brasserie",
    "charcuterie",
    "chapelle",
    "chateau",
    "cremerie",
    "eglise",
    "fontaine",
    "horlogerie",
    "hotel",
    "immeuble",
    "imprimerie",
    "jeu de paume",
    "maison",
    "marche",
    "monument",
    "moulin",
    "peinture",
    "presbytere",
    "sculpture",
    "synagogue",
    "temple",
    "theatre",
    "tour",
}

FIELD_ALIASES = {
    "historical": {
        "construction_date",
        "start_date:construction",
        "start_date",
        "start_date:fr",
        "building:start_date",
        "start_date:edtf",
        "historic:period",
        "historic:era",
        "historic:civilization",
        "historic:civilization:fr",
    },
    "protection": {
        "mhs:inscription_date",
        "mhs:inscription_date_1",
        "heritage:inscription_date",
        "heritage:since",
        "mh:inscription_date",
        "mhs:date",
        "igpc:inscription_date",
    },
    "architect": {"architect", "architect_name", "architect:wikipedia", "source:architect"},
    "wikipedia": {"wikipedia", "wikipedia:fr", "related:wikipedia", "name:wikipedia", "old_wikipedia"},
}
REFERENCE_PATTERN = re.compile(r"\b(?:PA|IA|AC|MH)[A-Z0-9_-]*\d[A-Z0-9_-]*\b", re.IGNORECASE)
YEAR_PATTERN = re.compile(r"(?<!\d)(1[0-9]{3}|20[0-9]{2}|21[0-9]{2})(?!\d)")
CENTURY_PATTERN = re.compile(r"(?<!\d)([1-9]|1[0-9]|2[0-1])(?:er|e|eme|ème)?\s*(?:siècle|siecle)", re.IGNORECASE)
ROMAN_CENTURY_PATTERN = re.compile(r"\bC\s*(1[0-9]|2[0-1])\b", re.IGNORECASE)


def values_from_cell(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        values: list[str] = []
        for item in value:
            values.extend(values_from_cell(item))
        return values
    if isinstance(value, dict):
        return []
    text = clean(value)
    return [text] if text else []


def unique_values(values: Iterable[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        value = clean(value)
        if not value:
            continue
        key = normalize(value)
        if key not in seen:
            seen.add(key)
            result.append(value)
    return result


def split_references(value: str) -> list[str]:
    return list(dict.fromkeys(match.upper() for match in REFERENCE_PATTERN.findall(value)))


def load_source_metadata(path: Path | None) -> dict[str, dict[str, list[str]]]:
    if path is None:
        return {}
    if not path.exists():
        raise FileNotFoundError(f"CSV source introuvable : {path}")
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.reader(handle)
        header = next(reader)
        lowered = [clean(value).casefold() for value in header]
        reference_indexes = [
            index for index, value in enumerate(lowered)
            if value in {"ref:mhs", "ref:fr:merimee", "heritage:ref"}
        ]
        field_indexes = {
            field: [index for index, value in enumerate(lowered) if value in aliases]
            for field, aliases in FIELD_ALIASES.items()
        }
        result: dict[str, dict[str, list[str]]] = {}
        for row in reader:
            references: set[str] = set()
            for index in reference_indexes:
                if index < len(row):
                    references.update(split_references(row[index]))
            if not references:
                continue
            metadata = {
                field: unique_values(
                    value
                    for index in indexes
                    if index < len(row)
                    for value in values_from_cell(row[index])
                )
                for field, indexes in field_indexes.items()
            }
            for reference in references:
                existing = result.setdefault(reference, {field: [] for field in FIELD_ALIASES})
                for field, values in metadata.items():
                    existing[field] = unique_values(existing[field] + values)
    return result


def wikipedia_url_from_raw(value: str) -> str | None:
    for candidate in re.split(r"\s*;\s*", clean(value)):
        if not candidate:
            continue
        parsed = urlparse(candidate)
        if "wikipedia.org" in parsed.netloc.casefold() and parsed.scheme in {"http", "https"}:
            return candidate
        match = re.match(r"^([a-z]{2,12}):(.+)$", candidate, re.IGNORECASE)
        if match and match.group(1).casefold() == "fr":
            return "https://fr.wikipedia.org/wiki/" + quote(match.group(2).strip().replace(" ", "_"), safe="():,'-_")
        if ":" not in candidate and len(candidate) < 200:
            return "https://fr.wikipedia.org/wiki/" + quote(candidate.replace(" ", "_"), safe="():,'-_")
    return None


def title_from_url(value: str | None) -> str:
    if not value:
        return ""
    parsed = urlparse(value)
    if "wikipedia.org" not in parsed.netloc.casefold() or not parsed.path.startswith("/wiki/"):
        return ""
    return unquote(parsed.path[len("/wiki/"):]).replace("_", " ").strip()


def period_from_text(value: str) -> str | None:
    text = normalize(value)
    if not text:
        return None
    if any(token in text for token in ("prehistoire", "prehistorique", "neolithique", "paleolithique", "age du bronze", "age du fer")):
        return PERIOD_PREHISTORY
    if any(token in text for token in ("antiquite", "antique", "gallo romain", "gallo romaine", "romain", "romaine", "gaulois", "gauloise", "celtique")):
        return PERIOD_ANTIQUITY
    if any(token in text for token in ("moyen age", "medieval", "medievale", "feodal")):
        return PERIOD_MIDDLE_AGES
    if "renaissance" in text:
        return PERIOD_RENAISSANCE
    if any(token in text for token in ("epoque moderne", "temps modernes", "baroque", "classique")):
        return PERIOD_MODERN
    if any(token in text for token in ("epoque contemporaine", "contemporain", "contemporaine", "moderne")):
        return PERIOD_CONTEMPORARY

    centuries = [int(match.group(1)) for match in CENTURY_PATTERN.finditer(text)]
    centuries.extend(int(match.group(1)) for match in ROMAN_CENTURY_PATTERN.finditer(value))
    years = [int(match.group(1)) for match in YEAR_PATTERN.finditer(value)]
    if centuries:
        century = min(centuries)
        if century <= 5:
            return PERIOD_ANTIQUITY
        if century <= 15:
            return PERIOD_MIDDLE_AGES
        if century <= 16:
            return PERIOD_RENAISSANCE
        if century <= 18:
            return PERIOD_MODERN
        return PERIOD_CONTEMPORARY
    if years:
        year = min(years)
        if year < 500:
            return PERIOD_ANTIQUITY
        if year < 1500:
            return PERIOD_MIDDLE_AGES
        if year < 1600:
            return PERIOD_RENAISSANCE
        if year < 1790:
            return PERIOD_MODERN
        return PERIOD_CONTEMPORARY
    return None


def derive_period(*values: str) -> str | None:
    for value in values:
        period = period_from_text(value)
        if period:
            return period
    return None


def join_metadata(*values: Iterable[str]) -> str | None:
    merged = unique_values(value for group in values for value in group)
    return " ; ".join(merged) if merged else None


def notice_values(notice: dict[str, Any], *keys: str) -> list[str]:
    return unique_values(value for key in keys for value in values_from_cell(notice.get(key)))


def is_generic_wikipedia_title(title: str) -> bool:
    return normalize(title) in GENERIC_WIKIPEDIA_TITLES


class MetadataHttp:
    def __init__(self, cache: ResponseCache, request_interval: float):
        self.cache = cache
        self.request_interval = max(0.0, request_interval)
        self.last_request_at = 0.0
        self.request_count = 0

    def request(self, url: str, params: dict[str, str], *, post: bool = False, accept: str = "application/json") -> dict[str, Any]:
        encoded = urlencode(params).encode("utf-8") if post else None
        request_url = url if post else f"{url}?{urlencode(params)}"
        request = Request(
            request_url,
            data=encoded,
            method="POST" if post else "GET",
            headers={
                "Accept": accept,
                "Content-Type": "application/x-www-form-urlencoded" if post else "application/json",
                "User-Agent": USER_AGENT,
            },
        )
        for attempt in range(MAX_RETRIES + 1):
            delay = self.request_interval - (time.monotonic() - self.last_request_at)
            if delay > 0:
                time.sleep(delay)
            self.last_request_at = time.monotonic()
            try:
                with urlopen(request, timeout=60) as response:
                    payload = json.load(response)
                self.request_count += 1
                if "error" in payload:
                    raise RuntimeError(str(payload["error"]))
                return payload
            except HTTPError as error:
                if error.code not in {429, 500, 502, 503, 504} or attempt >= MAX_RETRIES:
                    raise
                time.sleep(min(60.0, 2.0 ** (attempt + 1)))
            except (TimeoutError, URLError, IncompleteRead, json.JSONDecodeError, ConnectionError):
                if attempt >= MAX_RETRIES:
                    raise
                time.sleep(min(60.0, 2.0 ** (attempt + 1)))
        raise RuntimeError("Échec inattendu de l’appel externe")

    def pop_notice(self, reference: str) -> dict[str, Any]:
        key = reference.upper()
        cached = self.cache.get("pop_notice", key)
        if cached is not None:
            return cached.get("notice") if isinstance(cached.get("notice"), dict) else {}
        try:
            payload = self.request(f"{POP_API}{quote(reference)}/public", {})
        except HTTPError as error:
            if error.code == 404:
                self.cache.set("pop_notice", key, {"_status": 404})
                return {}
            raise
        notice = payload.get("notice") if isinstance(payload.get("notice"), dict) else {}
        self.cache.set("pop_notice", key, {"notice": notice})
        return notice

    def wikidata_by_references(self, references: list[str], batch_size: int = 80) -> dict[str, dict[str, str]]:
        result: dict[str, dict[str, str]] = {}
        missing = []
        for reference in dict.fromkeys(reference.upper() for reference in references if reference):
            cached = self.cache.get("wikidata_merimee", reference)
            if cached is None:
                missing.append(reference)
            elif cached.get("found"):
                result[reference] = cached
        for start in range(0, len(missing), batch_size):
            batch = missing[start : start + batch_size]
            values = " ".join(json.dumps(reference) for reference in batch)
            query = f"""
                SELECT ?merimee ?item ?article ?label ?description WHERE {{
                    VALUES ?merimee {{ {values} }}
                    ?item <http://www.wikidata.org/prop/direct/P380> ?merimee .
                    OPTIONAL {{
                        ?article <http://schema.org/about> ?item ;
                                 <http://schema.org/isPartOf> <https://fr.wikipedia.org/> .
                    }}
                    OPTIONAL {{
                        ?item <http://www.w3.org/2000/01/rdf-schema#label> ?label .
                        FILTER(LANG(?label) = "fr")
                    }}
                    OPTIONAL {{
                        ?item <http://schema.org/description> ?description .
                        FILTER(LANG(?description) = "fr")
                    }}
                }}
            """
            try:
                payload = self.request(
                    WIKIDATA_SPARQL,
                    {"query": query, "format": "json"},
                    post=True,
                    accept="application/sparql-results+json",
                )
            except (HTTPError, URLError, TimeoutError, RuntimeError) as error:
                raise RuntimeError(f"Recherche Wikidata échouée pour le lot {start + 1}: {error}") from error
            found: dict[str, dict[str, str]] = {}
            for row in payload.get("results", {}).get("bindings", []):
                reference = clean(row.get("merimee", {}).get("value")).upper()
                if not reference or reference in found:
                    continue
                item_url = clean(row.get("item", {}).get("value"))
                article_url = clean(row.get("article", {}).get("value"))
                label = clean(row.get("label", {}).get("value"))
                description = clean(row.get("description", {}).get("value"))
                found[reference] = {
                    "found": "true",
                    "qid": item_url.rsplit("/", 1)[-1] if item_url else "",
                    "article_url": article_url,
                    "label": label,
                    "description": description,
                    "title": title_from_url(article_url),
                }
            for reference in batch:
                value = found.get(reference, {"found": "false"})
                self.cache.set("wikidata_merimee", reference, value)
                if value.get("found") == "true":
                    result[reference] = value
        return result


def structured_pop_description(monument: dict[str, Any], notice: dict[str, Any], historical_dates: str | None) -> str | None:
    direct = " ".join(notice_values(notice, "DESC"))
    if usable_extract(direct):
        return usable_extract(direct)
    title = clean(notice.get("TICO")) or clean(monument.get("name"))
    denominations = notice_values(notice, "DENO")
    periods = notice_values(notice, "SCLE", "DATE")
    protection = notice_values(notice, "PROT")
    if not title or not (denominations or periods or historical_dates or protection):
        return None
    parts = [f"La notice POP identifie ce monument sous le nom « {title} »." ]
    if denominations:
        parts.append(f"Dénomination : {', '.join(denominations)}.")
    dating = historical_dates or " ; ".join(periods)
    if dating:
        parts.append(f"Datation indiquée : {dating}.")
    if protection:
        parts.append(f"Protection indiquée : {', '.join(protection)}.")
    return " ".join(parts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON_PATH)
    parser.add_argument("--source-csv", type=Path, default=DEFAULT_SOURCE_CSV, help="export Mérimée/OSM original")
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE_PATH)
    parser.add_argument("--report", type=Path, default=DEFAULT_METADATA_REPORT_PATH)
    parser.add_argument("--request-interval", type=float, default=0.35)
    parser.add_argument("--max-targets", type=int, default=0, help="limiter les monuments sans description traités (0 = tous)")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    json_path = args.json.resolve()
    monuments = json.loads(json_path.read_text(encoding="utf-8"))
    source_metadata = load_source_metadata(args.source_csv.resolve() if args.source_csv else None)
    cache = ResponseCache(args.cache.resolve())
    http = MetadataHttp(cache, args.request_interval)
    wikipedia = WikipediaClient(cache, args.request_interval)
    report: dict[str, Any] = {
        "asset": str(json_path),
        "source_csv": str(args.source_csv.resolve()) if args.source_csv else None,
        "total_monuments": len(monuments),
        "pop_notices": 0,
        "wikidata_exact_matches": 0,
        "wikipedia_from_exact_match": 0,
        "structured_pop_descriptions": 0,
        "wikidata_descriptions": 0,
        "repaired_wikipedia_descriptions": 0,
        "repaired_generic_wikipedia_descriptions": 0,
        "historical_metadata": 0,
        "protection_metadata": 0,
        "not_found": 0,
        "errors": [],
        "requests": 0,
        "dry_run": args.dry_run,
    }

    references = [clean(monument.get("merimeeReference")).upper() for monument in monuments if clean(monument.get("merimeeReference"))]
    targets = [
        monument for monument in monuments
        if not clean(monument.get("popHistoricalDescription")) and not clean(monument.get("description"))
    ]
    if args.max_targets:
        targets = targets[:args.max_targets]
    target_references = [clean(monument.get("merimeeReference")).upper() for monument in targets if clean(monument.get("merimeeReference"))]
    target_reference_set = set(target_references)
    repair_candidates = []
    for monument in monuments:
        if normalize(clean(monument.get("descriptionSource"))) != "wikipedia":
            continue
        title = title_from_url(clean(monument.get("wikipediaUrl")))
        if title and is_generic_wikipedia_title(title):
            repair_candidates.append(monument)
    repair_references = [
        clean(monument.get("merimeeReference")).upper()
        for monument in repair_candidates
        if clean(monument.get("merimeeReference"))
    ]
    repair_reference_set = set(repair_references)
    lookup_references = list(dict.fromkeys(target_references + repair_references))
    wikidata: dict[str, dict[str, str]] = {}
    try:
        wikidata = http.wikidata_by_references(lookup_references)
        report["wikidata_exact_matches"] = len(wikidata)
    except (HTTPError, URLError, TimeoutError, RuntimeError) as error:
        report["errors"].append(str(error))

    direct_urls: dict[str, str] = {}
    direct_url_monuments = list(targets) + repair_candidates
    for monument in direct_url_monuments:
        reference = clean(monument.get("merimeeReference")).upper()
        source = source_metadata.get(reference, {})
        url = clean(monument.get("wikipediaUrl")) or wikipedia_url_from_raw(" ; ".join(source.get("wikipedia", [])))
        wd_url = wikidata.get(reference, {}).get("article_url", "")
        direct_urls[reference] = wd_url or url
    titles = [title_from_url(url) for url in direct_urls.values() if title_from_url(url)]
    try:
        wikipedia_results = wikipedia.exact_pages(titles)
    except (HTTPError, URLError, TimeoutError, RuntimeError) as error:
        wikipedia_results = {}
        report["errors"].append(str(error))

    for index, monument in enumerate(monuments, start=1):
        reference = clean(monument.get("merimeeReference")).upper()
        source = source_metadata.get(reference, {})
        notice: dict[str, Any] = {}
        # POP.SCLE is the authoritative construction-period hint for this
        # catalogue.  Read every available notice so an old CSV date cannot
        # leave a monument incorrectly classified as "non renseignée".
        needs_pop = bool(reference)
        if reference and needs_pop:
            try:
                notice = http.pop_notice(reference)
                if notice:
                    report["pop_notices"] += 1
            except (HTTPError, URLError, TimeoutError, RuntimeError) as error:
                report["errors"].append(f"{reference}: {error}")

        pop_dates = notice_values(notice, "DATE", "SCLE")
        historical_dates = join_metadata(source.get("historical", []), pop_dates)
        protection_dates = join_metadata(source.get("protection", []))
        period = derive_period(historical_dates or "", " ".join(notice_values(notice, "SCLE", "DATE")))

        reference_match = wikidata.get(reference, {})
        candidate_url = reference_match.get("article_url", "") or direct_urls.get(reference, "")
        candidate_title = reference_match.get("title", "") or title_from_url(candidate_url)
        wiki_result = wikipedia_results.get(candidate_title, {}) if candidate_title else {}
        wiki_description = clean(wiki_result.get("description"))
        wikidata_description = clean(reference_match.get("description"))
        description = clean(monument.get("description"))
        clear_generic_wikipedia_url = (
            normalize(clean(monument.get("descriptionSource"))) == "pop"
            and is_generic_wikipedia_title(title_from_url(clean(monument.get("wikipediaUrl"))))
        )
        if not description and reference in target_reference_set:
            if wiki_description:
                if not args.dry_run:
                    monument["description"] = wiki_description
                    monument["descriptionSource"] = "Wikipédia"
                    if wiki_result.get("url"):
                        monument["wikipediaUrl"] = wiki_result["url"]
                report["wikipedia_from_exact_match"] += 1
            elif len(wikidata_description) >= 40:
                if not args.dry_run:
                    monument["description"] = wikidata_description
                    monument["descriptionSource"] = "Wikidata"
                    if candidate_url:
                        monument["wikipediaUrl"] = candidate_url
                report["wikidata_descriptions"] += 1
            else:
                structured = structured_pop_description(monument, notice, historical_dates)
                if structured:
                    if not args.dry_run:
                        monument["description"] = structured
                        monument["descriptionSource"] = "POP"
                    report["structured_pop_descriptions"] += 1
                else:
                    report["not_found"] += 1

        if reference in repair_reference_set and description:
            current_title = title_from_url(clean(monument.get("wikipediaUrl")))
            exact_wikipedia_description = wiki_description if reference_match.get("article_url") else ""
            if exact_wikipedia_description:
                if not args.dry_run:
                    monument["description"] = exact_wikipedia_description
                    monument["descriptionSource"] = "Wikipédia"
                    monument["wikipediaUrl"] = reference_match["article_url"]
                report["repaired_wikipedia_descriptions"] += 1
            elif is_generic_wikipedia_title(current_title):
                structured = structured_pop_description(monument, notice, historical_dates)
                if structured:
                    if not args.dry_run:
                        monument["description"] = structured
                        monument["descriptionSource"] = "POP"
                        monument["wikipediaUrl"] = None
                    clear_generic_wikipedia_url = True
                    report["repaired_generic_wikipedia_descriptions"] += 1
                elif len(wikidata_description) >= 40:
                    if not args.dry_run:
                        monument["description"] = wikidata_description
                        monument["descriptionSource"] = "Wikidata"
                        monument["wikipediaUrl"] = None
                    report["repaired_wikipedia_descriptions"] += 1

        if not args.dry_run:
            if historical_dates:
                monument["historicalDates"] = historical_dates
            if period:
                monument["historicalPeriod"] = period
            if protection_dates:
                monument["protectionDates"] = protection_dates
            if clear_generic_wikipedia_url:
                monument["wikipediaUrl"] = None
            elif candidate_url and not clean(monument.get("wikipediaUrl")):
                monument["wikipediaUrl"] = candidate_url
        if historical_dates or period:
            report["historical_metadata"] += 1
        if protection_dates:
            report["protection_metadata"] += 1
        if index % 100 == 0 or index == len(monuments):
            print(f"Métadonnées historiques : {index}/{len(monuments)}", file=sys.stderr)

    report["requests"] = http.request_count + wikipedia.request_count
    report["errors"] = report["errors"][:100]
    report_path = args.report.resolve()
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    cache.close()
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
