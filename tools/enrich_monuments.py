#!/usr/bin/env python3
"""Enrich a Mérimée/OSM CSV with reproducible heritage and notoriety scores.

The input export used by this project has 1,348 columns and a duplicated
``ref:mhs`` header.  For that reason this script deliberately keeps the raw
header as a list, reads rows with pandas in chunks, and writes rows through a
CSV writer.  This preserves duplicate column names and the order of every
source column while appending only the three requested scores.

The scoring is intentionally conservative:

* the OSM French heritage convention is used when ``heritage:operator=mhs``:
  ``heritage=2`` means classified and ``heritage=3`` means registered;
* protection dates are taken only from protection-related fields;
* UNESCO points require an explicit UNESCO/World Heritage reference or tag;
  geographic inclusion in a large UNESCO area is never inferred;
* Wikidata/Wikipedia matching starts with structured IDs already present in
  the CSV, then uses an exact Wikipedia pageprops link, and only lastly a
  name + coordinate search with a strict distance threshold;
* external responses are cached in SQLite.  The cache is written after every
  successful or terminal API response, so a restart does not repeat calls.

Offline mode is the default and is suitable for deterministic local runs.  To
enrich missing matches and retrieve Wikimedia pageviews, pass ``--online``.
Physical attendance can be supplied as a structured CSV with
``--attendance-csv``.  Its columns may be named in French or English; see
``DATA_ENRICHMENT.md`` for the accepted schema and formulas.

Example:

    python tools/enrich_monuments.py \
      --input "C:/path/to/source.csv" \
      --output-dir outputs/heritage_enrichment \
      --attendance-csv data/frequentation.csv

The final CSV contains exactly the original columns followed by:
``importance_patrimoniale``, ``notoriete``, ``note_globale``,
``percentile_interet`` and ``etoiles``.  The percentile and stars are
calibrated on the Paris bounding-box population by default, so the full
France export can be used as an intermediate source without changing the
meaning of the application rating.
"""

from __future__ import annotations

import argparse
from bisect import bisect_left, bisect_right
import csv
import datetime as dt
import difflib
import json
import math
import re
import sqlite3
import sys
import time
import unicodedata
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Iterator
from urllib.error import HTTPError, URLError
from urllib.parse import quote, unquote, urlencode, urlparse
from urllib.request import Request, urlopen

import pandas as pd


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT_DIR = REPOSITORY_ROOT / "outputs" / "heritage_enrichment"
USER_AGENT = (
    "LeFlaneurDeParis-heritage-enrichment/1.0 "
    "(https://www.mediawiki.org/wiki/API:Etiquette)"
)
WIKIDATA_API = "https://www.wikidata.org/w/api.php"
WIKIPEDIA_API = "https://fr.wikipedia.org/w/api.php"
PAGEVIEWS_API = "https://wikimedia.org/api/rest_v1/metrics/pageviews"
MAX_DISTANCE_METRES = 150.0
CONTRADICTORY_DISTANCE_METRES = 250.0
PARIS_BBOX = (48.815, 48.903, 2.224, 2.470)
GENERIC_NAME_TOKENS = {
    "ancien",
    "ancienne",
    "autre",
    "chapelle",
    "chateau",
    "eglise",
    "fontaine",
    "hotel",
    "immeuble",
    "maison",
    "monument",
    "palais",
    "pont",
    "tour",
}
NAME_MATCH_STOPWORDS = {"a", "au", "aux", "arc", "d", "de", "des", "du", "en", "et", "la", "le", "l", "les", "sur"}


FIELD_ALIASES: dict[str, list[str]] = {
    "merimee_ref": ["ref:mhs", "ref:FR:Merimee", "heritage:ref"],
    "lat": ["lat"],
    "lon": ["lon"],
    "name": ["name:fr", "name", "mhs:name", "official_name", "alt_name:fr"],
    "wikidata": ["wikidata", "alt_wikidata"],
    "wikipedia": ["wikipedia:fr", "wikipedia"],
    "street": ["addr:street", "contact:street"],
    "house_number": ["addr:housenumber", "contact:housenumber"],
    "city": ["addr:city", "Commune", "addr:town", "is_in:town"],
    "postcode": ["addr:postcode", "contact:postcode", "postal_code"],
    "heritage": ["heritage"],
    "heritage_operator": ["heritage:operator"],
    "protection_title": [
        "protection_title",
        "classement",
        "inscription",
        "heritage:type",
        "organ:listed_status",
        "whc:protection_title",
    ],
    "protection_dates": [
        "mhs:inscription_date",
        "heritage:inscription_date",
        "heritage:since",
        "heritage:inscription_date_1",
        "mh:inscription_date",
        "mhs:date",
        "igpc:inscription_date",
        "whc:inscription_date",
        "classement",
    ],
    "criteria": [
        "mhs:criteria",
        "mhs:description",
        "description:mhs",
        "description:fr:mhs",
        "heritage:description",
        "description",
        "description:fr",
        "note",
        "note:fr",
        "importance",
        "historic:period",
        "historic:era",
    ],
    "construction_dates": [
        "construction_date",
        "year_of_construction",
        "start_date:construction",
        "start_date",
        "construction",
        "period",
    ],
    "architecture": [
        "architect",
        "architect_name",
        "architect:wikidata",
        "architect:wikipedia",
        "source:architect",
        "artist_name",
        "building:architecture",
        "building:style",
        "architectural_style",
        "style",
        "historic:period",
        "historic:era",
    ],
    "unesco": [
        "ref:whc",
        "whc:inscription_date",
        "whc:criteria",
        "whc:protection_title",
        "heritage:operator",
        "heritage:type",
    ],
    "ref_whc": ["ref:whc"],
    "other_protection": [
        "protected",
        "protect_class",
        "heritage:type",
        "protection_title",
        "heritage:label:FR",
        "award:FR:remarkable_garden",
        "ref:FR:remarkable_garden",
        "site",
    ],
    "physical_visitors": ["tourism:visitors", "tourism_1", "tourism_2"],
}


def clean(value: Any) -> str:
    """Convert a pandas cell to text without changing non-empty source data."""

    if value is None:
        return ""
    try:
        if pd.isna(value):
            return ""
    except (TypeError, ValueError):
        pass
    return str(value)


def normalize(value: str) -> str:
    value = unicodedata.normalize("NFKD", value.casefold())
    value = "".join(char for char in value if not unicodedata.combining(char))
    return re.sub(r"[^a-z0-9]+", " ", value).strip()


def compact(value: str) -> str:
    return re.sub(r"[^A-Z0-9]+", "", value.upper())


def split_semicolon(value: str) -> list[str]:
    return [part.strip() for part in re.split(r"\s*;\s*", value) if part.strip()]


def parse_float(value: str) -> float | None:
    value = value.strip().replace("\u202f", "").replace(" ", "")
    if not value:
        return None
    try:
        return float(value.replace(",", "."))
    except ValueError:
        return None


def parse_integer(value: str) -> int | None:
    """Parse visitor counts such as ``1 234 567`` or ``1,234,567``."""

    raw = value.strip().replace("\u202f", "").replace(" ", "")
    if not raw:
        return None
    if raw.count(",") > 1 or raw.count(".") > 1:
        raw = raw.replace(",", "").replace(".", "")
    elif re.fullmatch(r"\d{1,3}(?:[,.]\d{3})", raw):
        raw = raw.replace(",", "").replace(".", "")
    match = re.search(r"\d+", raw)
    return int(match.group(0)) if match else None


def parse_years(text: str) -> list[int]:
    years: list[int] = []
    for match in re.finditer(r"(?<!\d)(1[0-9]{3}|20[0-9]{2}|21[0-9]{2})(?!\d)", text):
        year = int(match.group(1))
        if 1000 <= year <= dt.date.today().year + 1:
            years.append(year)
    return years


def haversine_metres(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6_371_000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return radius * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def round_half_up(value: float) -> int:
    return int(math.floor(value + 0.5))


class ColumnResolver:
    """Resolve fields by position so duplicated source headers remain valid."""

    def __init__(self, header: list[str]):
        self.header = header
        self.positions: dict[str, list[int]] = {}
        lowered = [name.strip().casefold() for name in header]
        for key, aliases in FIELD_ALIASES.items():
            positions: list[int] = []
            for alias in aliases:
                alias_lower = alias.casefold()
                positions.extend(index for index, name in enumerate(lowered) if name == alias_lower)
            self.positions[key] = list(dict.fromkeys(positions))

    def values(self, row: list[str], key: str) -> list[str]:
        return [row[index].strip() for index in self.positions.get(key, []) if index < len(row) and row[index].strip()]

    def first(self, row: list[str], key: str) -> str:
        values = self.values(row, key)
        return values[0] if values else ""

    def joined(self, row: list[str], key: str) -> str:
        return " | ".join(self.values(row, key))


def read_header(path: Path) -> list[str]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        header = next(csv.reader(handle))
    if not header:
        raise ValueError(f"CSV vide : {path}")
    return header


def iter_pandas_chunks(path: Path, column_count: int, chunksize: int) -> Iterator[pd.DataFrame]:
    """Read logical CSV records in chunks while keeping all values as strings."""

    yield from pd.read_csv(
        path,
        header=None,
        names=list(range(column_count)),
        skiprows=1,
        dtype="string",
        keep_default_na=False,
        na_filter=False,
        chunksize=chunksize,
        encoding="utf-8-sig",
        low_memory=False,
    )


def extract_qids(values: Iterable[str]) -> list[str]:
    qids: list[str] = []
    for value in values:
        qids.extend(re.findall(r"\bQ[1-9][0-9]*\b", value, flags=re.IGNORECASE))
    return list(dict.fromkeys(qid.upper() for qid in qids))


def extract_merimee_refs(values: Iterable[str]) -> list[str]:
    refs: list[str] = []
    for value in values:
        for part in split_semicolon(value):
            # Keep the original reference spelling except for harmless surrounding spaces.
            if re.search(r"\b(?:PA|IA|AC|MH)[A-Z0-9_-]*\d[A-Z0-9_-]*\b", part, flags=re.IGNORECASE):
                refs.append(part.strip())
    return list(dict.fromkeys(refs))


def parse_wikipedia_titles(value: str) -> dict[str, str]:
    """Return language -> title for structured Wikipedia values."""

    result: dict[str, str] = {}
    for candidate in split_semicolon(value):
        language = "fr"
        title = ""
        if candidate.lower().startswith("http://") or candidate.lower().startswith("https://"):
            parsed = urlparse(candidate)
            host = parsed.netloc.casefold()
            match = re.match(r"^([a-z-]+)\.wikipedia\.org$", host)
            if not match or not parsed.path.startswith("/wiki/"):
                continue
            language = match.group(1).split("-")[0]
            title = unquote(parsed.path[len("/wiki/") :]).replace("_", " ")
        else:
            localized = re.match(r"^([a-z]{2,12}):(.+)$", candidate, flags=re.IGNORECASE)
            if localized:
                language, title = localized.group(1).lower(), localized.group(2).strip()
            elif ":" not in candidate and len(candidate) < 200:
                title = candidate
        title = title.split("#", 1)[0].strip()
        if title and language not in result:
            result[language] = title
    return result


class PopDescriptionIndex:
    """Index the historical descriptions already imported in the Paris asset."""

    def __init__(self, path: Path | None):
        self.by_ref: dict[str, str] = {}
        if not path or not path.exists():
            return
        try:
            entries = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return
        for entry in entries if isinstance(entries, list) else []:
            reference = clean(entry.get("merimeeReference"))
            description = clean(entry.get("popHistoricalDescription"))
            if not reference or not description:
                continue
            key = compact(reference)
            # Keep the richest description if an asset contains a duplicated ref.
            if len(description) > len(self.by_ref.get(key, "")):
                self.by_ref[key] = description

    def get(self, references: Iterable[str]) -> str:
        descriptions = [self.by_ref[compact(reference)] for reference in references if compact(reference) in self.by_ref]
        return max(descriptions, key=len, default="")


def protection_components(
    resolver: ColumnResolver,
    row: list[str],
    pop_description: str = "",
    wikidata_flags: dict[str, bool] | None = None,
) -> dict[str, int | str | list[str]]:
    """Score the historical/architectural importance from local protection tags."""

    heritage_values = resolver.values(row, "heritage")
    heritage_text = " ".join(heritage_values)
    operator = normalize(resolver.joined(row, "heritage_operator"))
    protection_text = " ".join(resolver.values(row, "protection_title"))
    criteria_text = " ".join(resolver.values(row, "criteria"))
    all_protection = normalize(f"{heritage_text} {protection_text} {operator}")

    # In the French OSM convention, 2 = classé MH and 3 = inscrit MH.
    # Textual formulations are also accepted for POP-derived exports.
    is_classed = bool(re.search(r"\bclass(?:e|ee|es|ees|ement)?\b|\bclassement\b", all_protection))
    is_inscribed = bool(re.search(r"\binscrit(?:e|es)?\b|\binscription\b", all_protection))
    numeric_heritage = set(re.findall(r"(?<!\d)([123456789])(?!\d)", heritage_text))
    if "2" in numeric_heritage and (not operator or "mhs" in operator):
        is_classed = True
    if "3" in numeric_heritage and (not operator or "mhs" in operator):
        is_inscribed = True

    partial_markers = (
        "partiel",
        "partielle",
        "facade",
        "toiture",
        "elevation",
        "escalier",
        "portail",
        "decor interieur",
        "chevet",
        "nef",
        "clocher",
        "vestiges",
        "parcelle",
    )
    explicitly_total = bool(re.search(r"en totalite|totalite|total", all_protection))
    is_partial = not explicitly_total and any(marker in normalize(criteria_text + " " + protection_text) for marker in partial_markers)

    if is_classed and is_inscribed:
        protection_score = 42 if is_partial else 45
    elif is_classed:
        protection_score = 42 if is_partial else 50
    elif is_inscribed:
        protection_score = 25 if is_partial else 32
    else:
        protection_score = 0

    date_text = " ".join(resolver.values(row, "protection_dates"))
    if re.search(r"liste\s+de\s+1840|liste\s+1840", normalize(date_text + " " + protection_text)):
        first_year = 1840
    else:
        years = parse_years(date_text)
        first_year = min(years) if years else None
    if first_year is None:
        antiquity_score = 0
    elif first_year <= 1840:
        antiquity_score = 15
    elif first_year < 1900:
        antiquity_score = 12
    elif first_year <= 1945:
        antiquity_score = 9
    elif first_year <= 1980:
        antiquity_score = 6
    else:
        antiquity_score = 3

    interest_text = normalize(" ".join(resolver.values(row, "criteria")) + " " + pop_description)
    if any(term in interest_text for term in ("interet exceptionnel", "interet national", "importance nationale", "chef d oeuvre")):
        exceptional_score = 10
    elif any(term in interest_text for term in ("a signaler", "monument majeur", "site majeur", "interet majeur")):
        exceptional_score = 7
    elif "interet" in interest_text or "remarquable" in interest_text:
        exceptional_score = 4
    else:
        exceptional_score = 0

    unesco_text = normalize(resolver.joined(row, "unesco"))
    # A dedicated ref:whc, a WHC operator, or an explicit WHC/UNESCO field is
    # required. Coordinates in a broad UNESCO area are never enough.
    ref_whc = bool(resolver.values(row, "ref_whc"))
    operator_whc = any(normalize(value) in {"whc", "unesco", "world heritage"} for value in resolver.values(row, "heritage_operator"))
    whc_reference = ref_whc or operator_whc or bool(
        re.search(r"\b(?:whc|unesco)\b", unesco_text)
        and any(resolver.values(row, field) for field in ("ref_whc", "protection_dates"))
    )
    unesco_score = 15 if whc_reference else 0

    other_text = normalize(resolver.joined(row, "other_protection"))
    if "domaine national" in other_text or "domain national" in other_text:
        other_score = 10
    elif any(term in other_text for term in ("grand site de france", "monument national", "label national")):
        other_score = 7
    elif any(term in other_text for term in ("jardin remarquable", "site classe", "protected")):
        other_score = 4
    else:
        other_score = 0

    architecture_values = resolver.values(row, "architecture")
    architecture_text = normalize(" ".join(architecture_values))
    flags = wikidata_flags or {}
    has_authorship = bool(architecture_values) or flags.get("P84", False) or flags.get("P170", False)
    has_style = bool(architecture_text) or flags.get("P149", False) or flags.get("P186", False)
    authorship_score = (2 if has_authorship else 0) + (1 if has_style else 0)

    description_length = len(pop_description.strip())
    if description_length >= 1200:
        documentation_score = 4
    elif description_length >= 700:
        documentation_score = 3
    elif description_length >= 300:
        documentation_score = 2
    elif description_length >= 100:
        documentation_score = 1
    else:
        documentation_score = 0
    if any(term in interest_text for term in ("rare", "unique", "premier", "seul", "oeuvre majeure", "chef d oeuvre", "remarquable")):
        documentation_score = min(4, documentation_score + 1)

    context_score = min(
        3,
        sum(
            1
            for key in ("P793", "P138", "P527", "P166")
            if flags.get(key, False)
        ),
    )
    documented_interest_score = min(8, authorship_score + documentation_score + context_score)

    return {
        "score_protection": protection_score,
        "score_anciennete": antiquity_score,
        "score_exceptionnel": exceptional_score,
        "score_unesco": unesco_score,
        "score_autres_protections": other_score,
        "score_interet_documente": documented_interest_score,
        "score_auteur_style": authorship_score,
        "premiere_annee_protection": first_year or "",
        "protection_partielle_detectee": "oui" if is_partial else "non",
    }


def physical_score(visitors: int | None) -> int | None:
    """Convert annual attendance to a roughly logarithmic 0-100 score."""

    if visitors is None or visitors <= 0:
        return None
    anchors = [
        (10_000, 20),
        (50_000, 50),
        (100_000, 60),
        (200_000, 70),
        (500_000, 80),
        (1_000_000, 90),
        (2_000_000, 95),
        (5_000_000, 100),
    ]
    if visitors < anchors[0][0]:
        return 20
    if visitors >= anchors[-1][0]:
        return 100
    log_value = math.log10(visitors)
    for (low_value, low_score), (high_value, high_score) in zip(anchors, anchors[1:]):
        if low_value <= visitors <= high_value:
            fraction = (log_value - math.log10(low_value)) / (math.log10(high_value) - math.log10(low_value))
            return round_half_up(low_score + fraction * (high_score - low_score))
    return 20


def pageviews_score(views: int | None) -> int | None:
    if views is None or views <= 0:
        return None
    return min(100, round_half_up(100 * math.log1p(views) / math.log1p(5_000_000)))


def sitelinks_score(sitelinks: int | None) -> int | None:
    if sitelinks is None:
        return None
    return min(100, round_half_up(100 * math.log1p(max(0, sitelinks)) / math.log1p(60)))


class Cache:
    """Small persistent JSON-value cache backed by SQLite."""

    def __init__(self, path: Path | str):
        self.path = str(path)
        self.connection = sqlite3.connect(self.path, timeout=60)
        self.connection.execute("PRAGMA journal_mode=WAL")
        self.connection.execute(
            """CREATE TABLE IF NOT EXISTS cache (
                namespace TEXT NOT NULL,
                cache_key TEXT NOT NULL,
                value_json TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY(namespace, cache_key)
            )"""
        )
        self.connection.commit()

    def get(self, namespace: str, key: str) -> Any | None:
        result = self.connection.execute(
            "SELECT value_json FROM cache WHERE namespace=? AND cache_key=?",
            (namespace, key),
        ).fetchone()
        return json.loads(result[0]) if result else None

    def set(self, namespace: str, key: str, value: Any) -> None:
        self.connection.execute(
            "INSERT OR REPLACE INTO cache(namespace, cache_key, value_json, updated_at) VALUES (?, ?, ?, ?)",
            (namespace, key, json.dumps(value, ensure_ascii=False), dt.datetime.now(dt.UTC).isoformat()),
        )
        self.connection.commit()

    def set_many(self, namespace: str, values: dict[str, Any]) -> None:
        if not values:
            return
        timestamp = dt.datetime.now(dt.UTC).isoformat()
        self.connection.executemany(
            "INSERT OR REPLACE INTO cache(namespace, cache_key, value_json, updated_at) VALUES (?, ?, ?, ?)",
            [
                (namespace, key, json.dumps(value, ensure_ascii=False), timestamp)
                for key, value in values.items()
            ],
        )
        self.connection.commit()

    def close(self) -> None:
        self.connection.close()


class HttpClient:
    def __init__(self, online: bool, min_interval: float = 1.0, max_retries: int = 5):
        self.online = online
        self.min_interval = min_interval
        self.max_retries = max_retries
        self.last_request_at = 0.0

    def get_json(self, url: str, params: dict[str, str] | None = None) -> dict[str, Any] | None:
        if not self.online:
            return None
        if params:
            url = f"{url}?{urlencode(params)}"
        request = Request(url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
        for attempt in range(self.max_retries + 1):
            wait = self.min_interval - (time.monotonic() - self.last_request_at)
            if wait > 0:
                time.sleep(wait)
            self.last_request_at = time.monotonic()
            try:
                with urlopen(request, timeout=30) as response:
                    return json.load(response)
            except HTTPError as error:
                if error.code == 404:
                    return {"_http_status": 404}
                if error.code not in {429, 500, 502, 503, 504} or attempt >= self.max_retries:
                    return {"_error": f"HTTP {error.code}"}
                retry_after = error.headers.get("Retry-After") if error.headers else None
                try:
                    retry_wait = float(retry_after) if retry_after else 0.0
                except ValueError:
                    retry_wait = 0.0
                time.sleep(max(retry_wait, min(120.0, 2.0 ** attempt)))
            except (TimeoutError, URLError, ValueError) as error:
                if attempt >= self.max_retries:
                    return {"_error": str(error)}
                time.sleep(min(120.0, 2.0 ** attempt))
        return {"_error": "retry limit"}


def reporting_period() -> tuple[str, str]:
    """Return the 12 latest complete calendar months as API dates."""

    first_of_this_month = dt.date.today().replace(day=1)
    last_complete_month = first_of_this_month - dt.timedelta(days=1)
    end = last_complete_month.isoformat()
    start_month = last_complete_month.replace(day=1)
    for _ in range(11):
        start_month = (start_month.replace(day=1) - dt.timedelta(days=1)).replace(day=1)
    return start_month.strftime("%Y%m%d"), end.replace("-", "")


def entity_coordinate(entity: dict[str, Any]) -> tuple[float, float] | None:
    claims = entity.get("claims", {})
    coordinates = claims.get("P625") or []
    for claim in coordinates:
        value = ((claim.get("mainsnak") or {}).get("datavalue") or {}).get("value") or {}
        lat, lon = value.get("latitude"), value.get("longitude")
        if isinstance(lat, (int, float)) and isinstance(lon, (int, float)):
            return float(lat), float(lon)
    return None


def entity_merimee_refs(entity: dict[str, Any]) -> set[str]:
    refs: set[str] = set()
    for claim in (entity.get("claims", {}).get("P380") or []):
        value = ((claim.get("mainsnak") or {}).get("datavalue") or {}).get("value")
        if isinstance(value, str):
            refs.add(compact(value))
    return refs


def entity_claim_flags(entity: dict[str, Any] | None) -> dict[str, bool]:
    claims = entity.get("claims", {}) if entity else {}
    return {
        property_id: bool(claims.get(property_id))
        for property_id in ("P84", "P149", "P170", "P186", "P793", "P138", "P527", "P166")
    }


class WikidataMatcher:
    def __init__(
        self,
        cache: Cache,
        online: bool,
        max_entity_requests: int = 0,
        max_pageview_requests: int = 0,
        request_interval: float = 1.0,
        pageview_bbox: tuple[float, float, float, float] | None = None,
    ):
        self.cache = cache
        self.http = HttpClient(online=online, min_interval=max(0.0, request_interval))
        self.online = online
        self.max_entity_requests = max_entity_requests
        self.max_pageview_requests = max_pageview_requests
        self.pageview_bbox = pageview_bbox
        self.entity_requests = 0
        self.pageview_requests = 0

    def get_entity(self, qid: str) -> dict[str, Any] | None:
        cached = self.cache.get("wikidata_entity", qid)
        if cached is not None:
            return cached if cached.get("_http_status") != 404 and "_error" not in cached else None
        if not self.online or (self.max_entity_requests and self.entity_requests >= self.max_entity_requests):
            return None
        self.entity_requests += 1
        payload = self.http.get_json(
            WIKIDATA_API,
            {
                "action": "wbgetentities",
                "ids": qid,
                "props": "labels|claims|sitelinks",
                "languages": "fr|en",
                "format": "json",
            },
        ) or {"_error": "offline"}
        self.cache.set("wikidata_entity", qid, payload)
        entity = (payload.get("entities") or {}).get(qid)
        return entity if isinstance(entity, dict) else None

    def prefetch_entities(self, qids: Iterable[str], batch_size: int = 50) -> None:
        """Fetch direct source QIDs in Wikidata batches before row scoring."""

        if not self.online:
            return
        unique_qids = list(dict.fromkeys(qids))
        missing = [qid for qid in unique_qids if self.cache.get("wikidata_entity", qid) is None]
        for start in range(0, len(missing), batch_size):
            if self.max_entity_requests and self.entity_requests >= self.max_entity_requests:
                break
            batch = missing[start : start + batch_size]
            payload = self.http.get_json(
                WIKIDATA_API,
                {
                    "action": "wbgetentities",
                    "ids": "|".join(batch),
                    "props": "labels|claims|sitelinks",
                    "languages": "fr|en",
                    "format": "json",
                },
            ) or {"_error": "online request failed"}
            self.entity_requests += 1
            entities = payload.get("entities") or {}
            cached_values: dict[str, Any] = {}
            for qid in batch:
                entity = entities.get(qid)
                cached_values[qid] = entity if isinstance(entity, dict) else {"_http_status": 404}
            self.cache.set_many("wikidata_entity", cached_values)
            if self.entity_requests % 25 == 0 or start + batch_size >= len(missing):
                print(f"Préchargement Wikidata : {min(start + batch_size, len(missing))}/{len(missing)} QID", file=sys.stderr)

    def in_pageview_scope(self, metadata: dict[str, Any]) -> bool:
        if self.pageview_bbox is None:
            return True
        if metadata["lat"] is None or metadata["lon"] is None:
            return False
        south, north, west, east = self.pageview_bbox
        return south <= metadata["lat"] <= north and west <= metadata["lon"] <= east

    def wikipedia_pageprops(self, title: str) -> str | None:
        cache_key = normalize(title)
        cached = self.cache.get("wikipedia_pageprops", cache_key)
        if cached is not None:
            return cached.get("qid")
        if not self.online:
            return None
        payload = self.http.get_json(
            WIKIPEDIA_API,
            {
                "action": "query",
                "prop": "pageprops",
                "titles": title,
                "format": "json",
                "formatversion": "2",
            },
        ) or {"_error": "offline"}
        qid = None
        for page in payload.get("query", {}).get("pages", []):
            qid = (page.get("pageprops") or {}).get("wikibase_item")
            if qid:
                break
        self.cache.set("wikipedia_pageprops", cache_key, {"qid": qid or ""})
        return qid

    def search_candidates(self, name: str, lat: float | None, lon: float | None) -> list[str]:
        name_key = f"{normalize(name)}|{lat:.5f}|{lon:.5f}" if lat is not None and lon is not None else normalize(name)
        cached = self.cache.get("wikidata_association", name_key)
        if cached is not None:
            return list(cached.get("candidates") or [])
        if not self.online or len(normalize(name)) < 6:
            return []
        payload = self.http.get_json(
            WIKIDATA_API,
            {
                "action": "wbsearchentities",
                "search": name,
                "language": "fr",
                "uselang": "fr",
                "limit": "5",
                "format": "json",
            },
        ) or {"_error": "offline"}
        candidates = [str(item.get("id")) for item in payload.get("search", []) if re.fullmatch(r"Q[1-9][0-9]*", str(item.get("id")))]
        self.cache.set("wikidata_association", name_key, {"candidates": candidates})
        return candidates

    def pageviews(self, title: str) -> tuple[int | None, str]:
        start, end = reporting_period()
        key = f"fr.wikipedia.org|{title}|{start}|{end}"
        cached = self.cache.get("wikipedia_pageviews", key)
        if cached is not None:
            return cached.get("views"), f"{start}-{end}"
        if not self.online or (self.max_pageview_requests and self.pageview_requests >= self.max_pageview_requests):
            return None, f"{start}-{end}"
        self.pageview_requests += 1
        encoded_title = quote(title.replace(" ", "_"), safe="")
        url = f"{PAGEVIEWS_API}/per-article/fr.wikipedia.org/all-access/all-agents/{encoded_title}/monthly/{start}/{end}"
        payload = self.http.get_json(url) or {"_error": "offline"}
        views = None
        if "items" in payload:
            views = sum(int(item.get("views", 0) or 0) for item in payload.get("items", []))
        self.cache.set("wikipedia_pageviews", key, {"views": views if views is not None else 0, "period": f"{start}-{end}"})
        return views, f"{start}-{end}"

    def resolve(self, metadata: dict[str, Any]) -> dict[str, Any]:
        qids = metadata["qids"]
        refs = metadata["merimee_refs"]
        wikipedia_titles = parse_wikipedia_titles(metadata["wikipedia_raw"])
        fr_title = wikipedia_titles.get("fr")
        reasons: list[str] = []
        matching_source = ""
        confidence = 0.0
        qid = ""
        entity: dict[str, Any] | None = None

        if len(qids) > 1:
            reasons.append("plusieurs QID Wikidata dans la source")
        if qids:
            qid = qids[0]
            matching_source = "source:wikidata"
            confidence = 0.98
            entity = self.get_entity(qid)
            if self.online and entity is None:
                reasons.append("QID source introuvable ou non vérifié par Wikidata")
        elif fr_title:
            pageprops_qid = self.wikipedia_pageprops(fr_title) if self.in_pageview_scope(metadata) else None
            if pageprops_qid:
                qid = pageprops_qid
                matching_source = "wikipedia:pageprops"
                confidence = 0.95
                entity = self.get_entity(qid)
            else:
                matching_source = "source:wikipedia-fr"
                confidence = 0.90
        else:
            meaningful = [token for token in normalize(metadata["name"]).split() if token not in GENERIC_NAME_TOKENS and len(token) >= 4]
            if self.online and self.in_pageview_scope(metadata) and len(meaningful) >= 1 and metadata["lat"] is not None and metadata["lon"] is not None:
                candidates = self.search_candidates(metadata["name"], metadata["lat"], metadata["lon"])
                scored: list[tuple[float, float, str, dict[str, Any]]] = []
                source_name = normalize(metadata["name"])
                for candidate_qid in candidates:
                    candidate_entity = self.get_entity(candidate_qid)
                    if not candidate_entity:
                        continue
                    labels = []
                    for language in ("fr", "en"):
                        label = (candidate_entity.get("labels", {}).get(language) or {}).get("value")
                        if label:
                            labels.append(normalize(label))
                    candidate_name_score = max((difflib.SequenceMatcher(None, source_name, label).ratio() for label in labels), default=0.0)
                    candidate_coord = entity_coordinate(candidate_entity)
                    distance = float("inf")
                    if candidate_coord:
                        distance = haversine_metres(metadata["lat"], metadata["lon"], candidate_coord[0], candidate_coord[1])
                    if candidate_name_score >= 0.86 and distance <= MAX_DISTANCE_METRES:
                        scored.append((candidate_name_score, distance, candidate_qid, candidate_entity))
                scored.sort(key=lambda item: (-item[0], item[1], item[2]))
                if len(scored) == 1 or (scored and (len(scored) == 1 or scored[0][0] - scored[1][0] >= 0.08)):
                    _, _, qid, entity = scored[0]
                    matching_source = "wikidata:nom-coordonnees"
                    confidence = 0.86
                elif len(scored) > 1:
                    reasons.append("plusieurs correspondances Wikidata nom + coordonnées")

        entity_coord = entity_coordinate(entity) if entity else None
        if entity and refs and entity_merimee_refs(entity).intersection(compact(ref) for ref in refs):
            matching_source = "wikidata:ref-merimee"
            confidence = 1.0
        if entity_coord and metadata["lat"] is not None and metadata["lon"] is not None:
            distance = haversine_metres(metadata["lat"], metadata["lon"], entity_coord[0], entity_coord[1])
            if distance > CONTRADICTORY_DISTANCE_METRES:
                reasons.append(f"coordonnées contradictoires ({distance:.0f} m)")
                confidence = min(confidence, 0.25)
            elif distance > MAX_DISTANCE_METRES:
                reasons.append(f"coordonnées éloignées ({distance:.0f} m)")
                confidence = min(confidence, 0.70)

        if entity:
            fr_title = ((entity.get("sitelinks") or {}).get("frwiki") or {}).get("title") or fr_title
            sitelinks = len(entity.get("sitelinks") or {})
        else:
            sitelinks = None

        # A source QID/Wikipedia tag is strong evidence, but the export can
        # still contain stale or misplaced OSM links.  When a French title is
        # available locally, catch obvious contradictions without rejecting
        # ordinary wording changes such as "Arc de Triomphe" vs "Arc de
        # triomphe de l'Étoile".
        if fr_title and metadata["name"]:
            source_tokens = set(normalize(metadata["name"]).split()) - GENERIC_NAME_TOKENS - NAME_MATCH_STOPWORDS
            title_tokens = set(normalize(fr_title).split()) - GENERIC_NAME_TOKENS - NAME_MATCH_STOPWORDS
            similarity = difflib.SequenceMatcher(None, normalize(metadata["name"]), normalize(fr_title)).ratio()
            distinctive_name = len(source_tokens) >= 2 or (len(source_tokens) == 1 and len(next(iter(source_tokens))) >= 8)
            if distinctive_name and similarity < 0.60 and not source_tokens.intersection(title_tokens):
                reasons.append("nom source et titre Wikipédia peu concordants")
                confidence = min(confidence, 0.35)

        views: int | None = None
        pageview_period = ""
        if fr_title and confidence >= 0.85 and self.in_pageview_scope(metadata):
            views, pageview_period = self.pageviews(fr_title)

        return {
            "wikidata_qid": qid,
            "wikipedia_fr_title": fr_title or "",
            "matching_source": matching_source,
            "matching_confidence": confidence,
            "wikidata_sitelinks": sitelinks,
            "wikidata_claim_flags": entity_claim_flags(entity),
            "wikipedia_pageviews_12m": views,
            "wikipedia_pageviews_period": pageview_period,
            "matching_reasons": reasons,
        }


class AttendanceIndex:
    """Load CMN/Musées de France-style annual attendance files.

    The adapter accepts a reference column, a visitor-count column, an
    optional year column and an optional source column.  Matching by Mérimée
    reference is exact; name + coordinates is only a fallback within 150 m.
    """

    def __init__(self, path: Path | None):
        self.records_by_ref: dict[str, list[dict[str, Any]]] = {}
        self.records: list[dict[str, Any]] = []
        if not path:
            return
        with path.open("r", encoding="utf-8-sig", newline="") as handle:
            preview = handle.read(8192)
        try:
            delimiter = csv.Sniffer().sniff(preview, delimiters=",;\t").delimiter
        except csv.Error:
            delimiter = ","
        frame = pd.read_csv(path, sep=delimiter, dtype="string", keep_default_na=False, na_filter=False)
        columns = {normalize(str(column)): column for column in frame.columns}

        def find(*names: str) -> Any:
            for name in names:
                if normalize(name) in columns:
                    return columns[normalize(name)]
            return None

        ref_col = find("ref:mhs", "merimee_reference", "merimee", "reference", "ref")
        name_col = find("name", "nom", "monument", "title", "titre", "nom de l'établissement")
        city_col = find("city", "commune", "ville", "libellé géographique commune")
        lat_col = find("lat", "latitude")
        lon_col = find("lon", "longitude", "lng")
        visitors_col = find("visitors", "visitor_count", "visites", "visiteurs", "frequentation", "attendance", "total")
        year_col = find("year", "annee", "année")
        source_col = find("source", "source_url", "source_name")
        if visitors_col is None:
            raise ValueError("Le fichier de fréquentation doit avoir une colonne visitors/visiteurs/visites/frequentation.")
        for raw in frame.to_dict(orient="records"):
            visitors = parse_integer(clean(raw.get(visitors_col)))
            if visitors is None or visitors <= 0:
                continue
            year = parse_integer(clean(raw.get(year_col))) if year_col is not None else None
            record = {
                "visitors": visitors,
                "year": year,
                "source": clean(raw.get(source_col)) if source_col is not None else str(path),
                "name": clean(raw.get(name_col)) if name_col is not None else "",
                "city": clean(raw.get(city_col)) if city_col is not None else "",
                "lat": parse_float(clean(raw.get(lat_col))) if lat_col is not None else None,
                "lon": parse_float(clean(raw.get(lon_col))) if lon_col is not None else None,
            }
            self.records.append(record)
            if ref_col is not None:
                for ref in split_semicolon(clean(raw.get(ref_col))):
                    self.records_by_ref.setdefault(compact(ref), []).append(record)

    @staticmethod
    def choose(records: list[dict[str, Any]]) -> dict[str, Any] | None:
        if not records:
            return None
        non_covid = [record for record in records if record.get("year") not in {2020, 2021}]
        pool = non_covid or records
        return sorted(pool, key=lambda record: (record.get("year") or 0, record.get("visitors") or 0), reverse=True)[0]

    def find(self, metadata: dict[str, Any]) -> dict[str, Any] | None:
        for ref in metadata["merimee_refs"]:
            match = self.choose(self.records_by_ref.get(compact(ref), []))
            if match:
                return {**match, "match_confidence": 1.0, "match_method": "ref:mhs"}
        if metadata["name"] and metadata["lat"] is not None and metadata["lon"] is not None:
            candidates: list[tuple[float, dict[str, Any]]] = []
            source_name = normalize(metadata["name"])
            for record in self.records:
                if record.get("lat") is None or record.get("lon") is None or not record.get("name"):
                    continue
                distance = haversine_metres(metadata["lat"], metadata["lon"], record["lat"], record["lon"])
                similarity = difflib.SequenceMatcher(None, source_name, normalize(record["name"])).ratio()
                if distance <= MAX_DISTANCE_METRES and similarity >= 0.90:
                    candidates.append((distance, record))
            if len(candidates) == 1:
                return {**candidates[0][1], "match_confidence": 0.82, "match_method": "nom+coordonnees"}
        # Official CMN exports do not provide coordinates.  Permit a unique,
        # near-exact name match only; if the source has a city, it must agree.
        if metadata["name"]:
            name_candidates: list[tuple[float, dict[str, Any]]] = []
            source_name = normalize(metadata["name"])
            source_city = normalize(metadata.get("city", ""))
            for record in self.records:
                if not record.get("name"):
                    continue
                similarity = difflib.SequenceMatcher(None, source_name, normalize(record["name"])).ratio()
                city_matches = not source_city or not record.get("city") or source_city == normalize(record["city"])
                if similarity >= 0.96 and city_matches:
                    name_candidates.append((similarity, record))
            if name_candidates:
                best_score = max(score for score, _ in name_candidates)
                best = [record for score, record in name_candidates if score == best_score]
                cities = {normalize(record.get("city", "")) for record in best if record.get("city")}
                chosen = None if not source_city and len(cities) > 1 else self.choose(best)
                if chosen:
                    return {**chosen, "match_confidence": 0.86, "match_method": "nom exact unique (année la plus récente hors Covid)"}
        return None


def metadata_from_row(row: list[str], resolver: ColumnResolver) -> dict[str, Any]:
    ref_values = resolver.values(row, "merimee_ref")
    merimee_refs = extract_merimee_refs(ref_values)
    qids = extract_qids(resolver.values(row, "wikidata"))
    lat = parse_float(resolver.first(row, "lat"))
    lon = parse_float(resolver.first(row, "lon"))
    return {
        "merimee_refs": merimee_refs,
        "qids": qids,
        "name": resolver.first(row, "name"),
        "city": resolver.first(row, "city"),
        "lat": lat,
        "lon": lon,
        "wikipedia_raw": resolver.joined(row, "wikipedia"),
        "heritage_raw": resolver.joined(row, "heritage"),
        "protection_dates_raw": resolver.joined(row, "protection_dates"),
        "physical_raw": resolver.first(row, "physical_visitors"),
    }


def score_row(
    metadata: dict[str, Any],
    resolver: ColumnResolver,
    row: list[str],
    matcher: WikidataMatcher,
    attendance: AttendanceIndex,
    pop_index: PopDescriptionIndex,
) -> tuple[dict[str, Any], list[str]]:
    match = matcher.resolve(metadata)
    pop_description = pop_index.get(metadata["merimee_refs"])
    importance_parts = protection_components(
        resolver,
        row,
        pop_description=pop_description,
        wikidata_flags=match["wikidata_claim_flags"],
    )
    importance = min(
        100,
        sum(
            int(importance_parts[key])
            for key in (
                "score_protection",
                "score_anciennete",
                "score_exceptionnel",
                "score_unesco",
                "score_autres_protections",
                "score_interet_documente",
            )
        ),
    )

    attendance_match = attendance.find(metadata)
    if attendance_match is None:
        source_visitors = parse_integer(metadata["physical_raw"])
        if source_visitors and source_visitors > 0:
            attendance_match = {
                "visitors": source_visitors,
                "year": None,
                "source": "source CSV: tourism:visitors",
                "match_confidence": 0.75,
                "match_method": "tourism:visitors",
            }
    if attendance_match:
        visitors = attendance_match.get("visitors")
        attendance_component = physical_score(visitors)
    else:
        visitors = None
        attendance_component = None

    wiki_component = pageviews_score(match["wikipedia_pageviews_12m"])
    links_component = sitelinks_score(match["wikidata_sitelinks"])
    available: list[tuple[int, float, str]] = []
    if attendance_component is not None:
        available.append((attendance_component, 0.40, "fréquentation physique"))
    if wiki_component is not None:
        available.append((wiki_component, 0.40, "consultations Wikipédia"))
    if links_component is not None:
        available.append((links_component, 0.20, "sitelinks Wikidata"))
    if available:
        weight_sum = sum(weight for _, weight, _ in available)
        notoriety = round_half_up(sum(score * weight for score, weight, _ in available) / weight_sum)
        basis = "coefficients disponibles renormalisés: " + ", ".join(label for _, _, label in available)
    elif match["wikipedia_fr_title"]:
        notoriety = 5
        basis = "présence Wikipédia fiable sans métrique récupérable (plancher prudent)"
    elif match["wikidata_qid"]:
        notoriety = 3
        basis = "QID Wikidata fiable sans métrique récupérable (plancher prudent)"
    else:
        notoriety = 2
        basis = "aucun indicateur de notoriété identifiable"
    global_score = max(0, min(100, round_half_up(0.60 * importance + 0.40 * notoriety)))

    reasons = list(match["matching_reasons"])
    if attendance_match and attendance_match.get("match_confidence", 1.0) < 0.90:
        reasons.append("fréquentation trouvée avec une identification non certaine")
    if "no" in normalize(metadata["heritage_raw"]) and metadata["merimee_refs"]:
        reasons.append("heritage=no malgré une référence Mérimée")
    years = parse_years(metadata["protection_dates_raw"])
    if any(year > dt.date.today().year for year in years):
        reasons.append("date de protection postérieure à la date courante")

    debug = {
        "nom": metadata["name"],
        "identifiant_merimee": ";".join(metadata["merimee_refs"]),
        "latitude": metadata["lat"] if metadata["lat"] is not None else "",
        "longitude": metadata["lon"] if metadata["lon"] is not None else "",
        "heritage_source": metadata["heritage_raw"],
        "wikidata_qid": match["wikidata_qid"],
        "titre_wikipedia_fr": match["wikipedia_fr_title"],
        "source_matching": match["matching_source"],
        "confiance_matching": round(match["matching_confidence"], 3),
        "motifs_matching": " | ".join(match["matching_reasons"]),
        "vues_wikipedia_12_mois": match["wikipedia_pageviews_12m"] if match["wikipedia_pageviews_12m"] is not None else "",
        "periode_vues_wikipedia": match["wikipedia_pageviews_period"],
        "sitelinks_wikidata": match["wikidata_sitelinks"] if match["wikidata_sitelinks"] is not None else "",
        "frequentation_physique": visitors if visitors is not None else "",
        "annee_frequentation": attendance_match.get("year", "") if attendance_match else "",
        "source_frequentation": attendance_match.get("source", "") if attendance_match else "",
        "methode_frequentation": attendance_match.get("match_method", "") if attendance_match else "",
        "description_pop_disponible": "oui" if pop_description else "non",
        "criteres_wikidata": ";".join(key for key, value in match["wikidata_claim_flags"].items() if value),
        "score_protection": importance_parts["score_protection"],
        "score_anciennete": importance_parts["score_anciennete"],
        "premiere_annee_protection": importance_parts["premiere_annee_protection"],
        "score_exceptionnel": importance_parts["score_exceptionnel"],
        "score_unesco": importance_parts["score_unesco"],
        "score_autres_protections": importance_parts["score_autres_protections"],
        "score_interet_documente": importance_parts["score_interet_documente"],
        "score_auteur_style": importance_parts["score_auteur_style"],
        "score_frequentation": attendance_component if attendance_component is not None else "",
        "score_wikipedia": wiki_component if wiki_component is not None else "",
        "score_wikidata": links_component if links_component is not None else "",
        "importance_patrimoniale": importance,
        "notoriete": notoriety,
        "base_notoriete": basis,
        "note_globale": global_score,
        "raisons_verification": " | ".join(reasons),
    }
    return debug, reasons


DEBUG_FIELDS = [
    "nom",
    "identifiant_merimee",
    "latitude",
    "longitude",
    "heritage_source",
    "wikidata_qid",
    "titre_wikipedia_fr",
    "source_matching",
    "confiance_matching",
    "motifs_matching",
    "vues_wikipedia_12_mois",
    "periode_vues_wikipedia",
    "sitelinks_wikidata",
    "frequentation_physique",
    "annee_frequentation",
    "source_frequentation",
    "methode_frequentation",
    "description_pop_disponible",
    "criteres_wikidata",
    "score_protection",
    "score_anciennete",
    "premiere_annee_protection",
    "score_exceptionnel",
    "score_unesco",
    "score_autres_protections",
    "score_interet_documente",
    "score_auteur_style",
    "score_frequentation",
    "score_wikipedia",
    "score_wikidata",
    "importance_patrimoniale",
    "notoriete",
    "base_notoriete",
    "note_globale",
    "raisons_verification",
]


def bucket(score: int) -> str:
    if score <= 34:
        return "0-34"
    if score <= 54:
        return "35-54"
    if score <= 74:
        return "55-74"
    if score <= 89:
        return "75-89"
    return "90-100"


SCORE_FIELDS = ["importance_patrimoniale", "notoriete", "note_globale"]
STAR_FIELDS = ["percentile_interet", "etoiles"]


def in_paris_bbox(latitude: str, longitude: str) -> bool:
    lat = parse_float(latitude)
    lon = parse_float(longitude)
    return lat is not None and lon is not None and PARIS_BBOX[0] <= lat <= PARIS_BBOX[1] and PARIS_BBOX[2] <= lon <= PARIS_BBOX[3]


def quantile_score(sorted_scores: list[int], fraction: float) -> int:
    if not sorted_scores:
        return 0
    index = min(len(sorted_scores) - 1, max(0, round((len(sorted_scores) - 1) * fraction)))
    return sorted_scores[index]


def interest_percentile(score: int, sorted_reference_scores: list[int]) -> int:
    if not sorted_reference_scores:
        return 0
    lower = bisect_left(sorted_reference_scores, score)
    upper = bisect_right(sorted_reference_scores, score)
    percentile = 100 * (lower + 0.5 * (upper - lower)) / len(sorted_reference_scores)
    return max(0, min(100, round_half_up(percentile)))


def interest_stars(score: int, threshold_two: int, threshold_three: int) -> int:
    if score >= threshold_three:
        return 3
    if score >= threshold_two:
        return 2
    return 1


def finalize_star_exports(
    base_enriched_path: Path,
    base_debug_path: Path,
    base_verify_path: Path,
    enriched_path: Path,
    debug_path: Path,
    verify_path: Path,
    original_header: list[str],
    star_scope: str,
) -> dict[str, Any]:
    """Add a Paris-calibrated percentile and a deliberately asymmetric star rating."""

    reference_scores: list[int] = []
    all_scores: list[int] = []
    with base_debug_path.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            score = int(row["note_globale"])
            all_scores.append(score)
            if star_scope == "all" or in_paris_bbox(row["latitude"], row["longitude"]):
                reference_scores.append(score)
    if not reference_scores:
        reference_scores = all_scores
        star_scope = "all"
    reference_scores.sort()
    threshold_two = quantile_score(reference_scores, 0.75)
    threshold_three = quantile_score(reference_scores, 0.95)

    def enrich_row(row: dict[str, str]) -> dict[str, Any]:
        score = int(row["note_globale"])
        return {
            **row,
            "percentile_interet": interest_percentile(score, reference_scores),
            "etoiles": interest_stars(score, threshold_two, threshold_three),
        }

    with base_enriched_path.open("r", encoding="utf-8-sig", newline="") as source_handle, enriched_path.open("w", encoding="utf-8", newline="") as output_handle:
        source_reader = csv.reader(source_handle)
        output_writer = csv.writer(output_handle, lineterminator="\n")
        output_writer.writerow(next(source_reader) + STAR_FIELDS)
        for row in source_reader:
            score_index = len(original_header) + SCORE_FIELDS.index("note_globale")
            score = int(row[score_index])
            output_writer.writerow(row + [interest_percentile(score, reference_scores), interest_stars(score, threshold_two, threshold_three)])

    def finalize_debug(source_path: Path, output_path: Path) -> None:
        with source_path.open("r", encoding="utf-8-sig", newline="") as source_handle, output_path.open("w", encoding="utf-8", newline="") as output_handle:
            reader = csv.DictReader(source_handle)
            fieldnames = (reader.fieldnames or []) + STAR_FIELDS
            writer = csv.DictWriter(output_handle, fieldnames=fieldnames, lineterminator="\n")
            writer.writeheader()
            for row in reader:
                writer.writerow(enrich_row(row))

    finalize_debug(base_debug_path, debug_path)
    finalize_debug(base_verify_path, verify_path)
    base_enriched_path.unlink(missing_ok=True)
    base_debug_path.unlink(missing_ok=True)
    base_verify_path.unlink(missing_ok=True)

    star_counts = Counter(interest_stars(score, threshold_two, threshold_three) for score in all_scores)
    return {
        "reference": star_scope,
        "reference_monuments": len(reference_scores),
        "thresholds": {
            "une_etoile_max_exclusif": threshold_two - 1,
            "deux_etoiles_minimum": threshold_two,
            "deux_etoiles_maximum_exclusif": threshold_three,
            "trois_etoiles_minimum": threshold_three,
        },
        "distribution": {str(stars): count for stars, count in sorted(star_counts.items())},
    }


def sample_rows(input_path: Path, resolver: ColumnResolver, chunksize: int, sample_size: int) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    selected_by_category: dict[str, dict[str, Any]] = {}
    targets = re.compile(r"notre.?dame|tour eiffel|arc de triomphe|panth[ée]on|louvre|sacre.?coeur", flags=re.IGNORECASE)
    for chunk in iter_pandas_chunks(input_path, len(resolver.header), chunksize):
        for row_tuple in chunk.itertuples(index=False, name=None):
            row = [clean(value) for value in row_tuple]
            metadata = metadata_from_row(row, resolver)
            parts = protection_components(resolver, row)
            importance = min(
                100,
                sum(
                    int(parts[key])
                    for key in (
                        "score_protection",
                        "score_anciennete",
                        "score_exceptionnel",
                        "score_unesco",
                        "score_autres_protections",
                        "score_interet_documente",
                    )
                ),
            )
            name_key = normalize(metadata["name"])
            is_target = bool(targets.search(metadata["name"]))
            is_category = any(word in name_key for word in ("eglise", "hotel", "immeuble"))
            is_partial = parts["protection_partielle_detectee"] == "oui"
            is_paris = metadata["lat"] is not None and metadata["lon"] is not None and 48.815 <= metadata["lat"] <= 48.903 and 2.224 <= metadata["lon"] <= 2.470
            candidate = {
                    "reference": ";".join(metadata["merimee_refs"]),
                    "nom": metadata["name"],
                    "heritage": metadata["heritage_raw"],
                    "date_protection": metadata["protection_dates_raw"],
                    "importance_hors_notoriete": importance,
                    "wikidata": ";".join(metadata["qids"]),
                    "wikipedia": parse_wikipedia_titles(metadata["wikipedia_raw"]).get("fr", ""),
                }
            key = ";".join(metadata["merimee_refs"]) or f"{metadata['name']}|{metadata['lat']}|{metadata['lon']}"
            categories: list[str] = []
            normalized_name = normalize(metadata["name"])
            if is_paris and "tour eiffel" in normalized_name:
                categories.append("Paris Tour Eiffel")
            elif is_paris and "arc de triomphe" in normalized_name:
                categories.append("Paris Arc de Triomphe")
            elif is_paris and "notre dame de paris" in normalized_name:
                categories.append("Paris Notre-Dame")
            elif is_paris and "pantheon" in normalized_name:
                categories.append("Paris Panthéon")
            elif is_target:
                categories.append("monument connu")
            if is_paris and importance >= 75:
                categories.append("Paris importance haute")
            if is_paris and is_partial:
                categories.append("Paris protection partielle")
            if is_paris and "eglise" in name_key:
                categories.append("Paris église")
            if is_paris and "hotel" in name_key:
                categories.append("Paris hôtel particulier")
            if is_paris and "immeuble" in name_key:
                categories.append("Paris immeuble")
            if is_paris and importance < 55:
                categories.append("Paris à découvrir")
            if importance >= 90:
                categories.append("importance exceptionnelle")
            if is_category:
                categories.append("type générique")
            if not categories and is_paris:
                categories.append("Paris obscur")
            for category in categories:
                selected_by_category.setdefault(category, {"_key": key, **candidate})
    ordered_categories = [
        "Paris Tour Eiffel",
        "Paris Arc de Triomphe",
        "Paris Notre-Dame",
        "Paris Panthéon",
        "monument connu",
        "importance exceptionnelle",
        "Paris importance haute",
        "Paris protection partielle",
        "Paris église",
        "Paris hôtel particulier",
        "Paris immeuble",
        "Paris à découvrir",
        "Paris obscur",
        "type générique",
    ]
    seen: set[str] = set()
    for category in ordered_categories:
        candidate = selected_by_category.get(category)
        if candidate and candidate["_key"] not in seen:
            seen.add(candidate["_key"])
            selected.append({key: value for key, value in candidate.items() if key != "_key"})
        if len(selected) >= sample_size:
            break
    return selected[:sample_size]


def collect_source_qids(input_path: Path, resolver: ColumnResolver, chunksize: int) -> list[str]:
    """Collect direct source QIDs once so online Wikidata calls can be batched."""

    qids: set[str] = set()
    for chunk in iter_pandas_chunks(input_path, len(resolver.header), chunksize):
        for row_tuple in chunk.itertuples(index=False, name=None):
            row = [clean(value) for value in row_tuple]
            qids.update(extract_qids(resolver.values(row, "wikidata")))
    return sorted(qids)


def print_sample(input_path: Path, resolver: ColumnResolver, chunksize: int, sample_size: int) -> None:
    sample = sample_rows(input_path, resolver, chunksize, sample_size)
    if not sample:
        print("Échantillon : aucune ligne sélectionnée.")
        return
    frame = pd.DataFrame(sample)
    print("Échantillon représentatif avant traitement complet (notoriété offline non encore appliquée) :")
    print(frame.to_string(index=False, max_colwidth=48))


def validate_csv_integrity(input_path: Path, output_path: Path, original_header: list[str]) -> tuple[int, list[str]]:
    problems: list[str] = []
    rows = 0
    with input_path.open("r", encoding="utf-8-sig", newline="") as source_handle, output_path.open("r", encoding="utf-8-sig", newline="") as output_handle:
        source_reader = csv.reader(source_handle)
        output_reader = csv.reader(output_handle)
        source_header = next(source_reader, [])
        output_header = next(output_reader, [])
        expected_header = original_header + SCORE_FIELDS + STAR_FIELDS
        if source_header != original_header:
            problems.append("en-tête source relu différemment de l'en-tête initial")
        if output_header != expected_header:
            problems.append("en-tête de sortie différent de l'en-tête source + 3 colonnes")
        for source_row, output_row in zip(source_reader, output_reader):
            if source_row != output_row[: len(original_header)]:
                problems.append(f"données source modifiées à la ligne {rows + 2}")
                break
            rows += 1
        if next(source_reader, None) is not None or next(output_reader, None) is not None:
            problems.append("nombre de lignes différent entre entrée et sortie")
    return rows, problems


def make_summary(metrics: dict[str, Any]) -> dict[str, Any]:
    summary = dict(metrics)
    for key in ("importance_patrimoniale", "notoriete", "note_globale"):
        values = summary.pop(f"_{key}_values")
        if values:
            summary["distribution"] = summary.get("distribution", {})
            summary["distribution"][key] = {
                "min": min(values),
                "max": max(values),
                "moyenne": round(sum(values) / len(values), 2),
                "classes": dict(Counter(bucket(value) for value in values)),
            }
    return summary


def enrich(args: argparse.Namespace) -> dict[str, Any]:
    input_path = args.input.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    header = read_header(input_path)
    resolver = ColumnResolver(header)
    cache_path = args.cache_path.resolve() if args.cache_path else output_dir / "heritage_cache.sqlite3"
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache = Cache(cache_path)
    pageview_bbox = PARIS_BBOX if args.pageview_scope == "paris" else None
    matcher = WikidataMatcher(
        cache,
        args.online,
        args.max_entity_requests,
        args.max_pageview_requests,
        args.request_interval,
        pageview_bbox,
    )
    attendance = AttendanceIndex(args.attendance_csv.resolve() if args.attendance_csv else None)
    pop_asset_path = args.pop_asset.resolve() if args.pop_asset else REPOSITORY_ROOT / "app" / "src" / "main" / "assets" / "paris_monuments.json"
    pop_index = PopDescriptionIndex(pop_asset_path)

    if args.online:
        source_qids = collect_source_qids(input_path, resolver, args.chunksize)
        print(f"Préchargement des QID Wikidata : {len(source_qids)} identifiants", file=sys.stderr)
        matcher.prefetch_entities(source_qids)

    enriched_path = output_dir / "monuments_enrichis.csv"
    debug_path = output_dir / "monuments_enrichis_debug.csv"
    verify_path = output_dir / "monuments_a_verifier.csv"
    summary_path = output_dir / "monuments_enrichis_summary.json"
    base_enriched_path = output_dir / ".monuments_enrichis_base.csv"
    base_debug_path = output_dir / ".monuments_enrichis_debug_base.csv"
    base_verify_path = output_dir / ".monuments_a_verifier_base.csv"
    for temporary_path in (base_enriched_path, base_debug_path, base_verify_path):
        temporary_path.unlink(missing_ok=True)
    metrics: dict[str, Any] = {
        "input": str(input_path),
        "output_directory": str(output_dir),
        "cache": str(cache_path),
        "mode": "online" if args.online else "offline",
        "total_monuments": 0,
        "associes_wikidata": 0,
        "associes_wikipedia_fr": 0,
        "disposant_d_une_frequentation_physique": 0,
        "necessitant_verification": 0,
        "wikidata_requetes": 0,
        "pageviews_requetes": 0,
        "_importance_patrimoniale_values": [],
        "_notoriete_values": [],
        "_note_globale_values": [],
    }

    with base_enriched_path.open("w", encoding="utf-8", newline="") as enriched_handle, base_debug_path.open("w", encoding="utf-8", newline="") as debug_handle, base_verify_path.open("w", encoding="utf-8", newline="") as verify_handle:
        enriched_writer = csv.writer(enriched_handle, lineterminator="\n")
        debug_writer = csv.DictWriter(debug_handle, fieldnames=["ligne_source"] + DEBUG_FIELDS, lineterminator="\n")
        verify_writer = csv.DictWriter(verify_handle, fieldnames=["ligne_source"] + DEBUG_FIELDS, lineterminator="\n")
        enriched_writer.writerow(header + SCORE_FIELDS)
        debug_writer.writeheader()
        verify_writer.writeheader()
        row_number = 1
        for chunk in iter_pandas_chunks(input_path, len(header), args.chunksize):
            for row_tuple in chunk.itertuples(index=False, name=None):
                row_number += 1
                row = [clean(value) for value in row_tuple]
                metadata = metadata_from_row(row, resolver)
                debug, reasons = score_row(metadata, resolver, row, matcher, attendance, pop_index)
                enriched_writer.writerow(row + [debug["importance_patrimoniale"], debug["notoriete"], debug["note_globale"]])
                debug_writer.writerow({"ligne_source": row_number, **debug})
                if reasons:
                    verify_writer.writerow({"ligne_source": row_number, **debug})
                metrics["total_monuments"] += 1
                if debug["wikidata_qid"]:
                    metrics["associes_wikidata"] += 1
                if debug["titre_wikipedia_fr"]:
                    metrics["associes_wikipedia_fr"] += 1
                if debug["frequentation_physique"] != "":
                    metrics["disposant_d_une_frequentation_physique"] += 1
                if reasons:
                    metrics["necessitant_verification"] += 1
                metrics["_importance_patrimoniale_values"].append(int(debug["importance_patrimoniale"]))
                metrics["_notoriete_values"].append(int(debug["notoriete"]))
                metrics["_note_globale_values"].append(int(debug["note_globale"]))
                if args.limit and metrics["total_monuments"] >= args.limit:
                    break
            if args.limit and metrics["total_monuments"] >= args.limit:
                break
            if metrics["total_monuments"] and metrics["total_monuments"] % 1000 == 0:
                print(f"Traitement : {metrics['total_monuments']} lignes", file=sys.stderr)

    metrics["wikidata_requetes"] = matcher.entity_requests
    metrics["pageviews_requetes"] = matcher.pageview_requests
    star_summary = finalize_star_exports(
        base_enriched_path,
        base_debug_path,
        base_verify_path,
        enriched_path,
        debug_path,
        verify_path,
        header,
        args.star_scope,
    )
    summary = make_summary(metrics)
    summary["etoiles"] = star_summary
    summary["source_description_pop"] = str(pop_asset_path)
    summary["integrite_source"] = "non vérifiée (limite utilisée)" if args.limit else "vérifiée ci-dessous"
    if not args.limit:
        row_count, problems = validate_csv_integrity(input_path, enriched_path, header)
        summary["lignes_comparees_integrite"] = row_count
        summary["problemes_integrite"] = problems
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    cache.close()
    return summary


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="CSV Mérimée/OSM source")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR, help="dossier des trois CSV, du résumé et du cache")
    parser.add_argument("--cache-path", type=Path, help="chemin SQLite du cache; par défaut dans --output-dir")
    parser.add_argument("--attendance-csv", type=Path, help="CSV structuré de fréquentation annuelle")
    parser.add_argument("--pop-asset", type=Path, help="asset JSON contenant les descriptions historiques POP; par défaut, celui de l'application")
    parser.add_argument("--online", action="store_true", help="interroger Wikidata, pageprops et Pageviews pour les données absentes du cache")
    parser.add_argument("--max-entity-requests", type=int, default=0, help="plafond de requêtes Wikidata (0 = illimité)")
    parser.add_argument("--max-pageview-requests", type=int, default=0, help="plafond de requêtes Pageviews (0 = illimité)")
    parser.add_argument("--request-interval", type=float, default=1.0, help="délai minimal entre appels HTTP (secondes; 1.0 recommandé)")
    parser.add_argument("--pageview-scope", choices=("all", "paris"), default="all", help="limiter les pageviews et les recherches Wikipedia à Paris")
    parser.add_argument("--star-scope", choices=("all", "paris"), default="paris", help="population de référence pour calibrer les percentiles et les étoiles")
    parser.add_argument("--chunksize", type=int, default=500, help="nombre de lignes pandas traitées par bloc")
    parser.add_argument("--limit", type=int, default=0, help="limiter le traitement pour un essai; 0 = fichier complet")
    parser.add_argument("--sample-only", action="store_true", help="afficher un échantillon représentatif sans écrire les exports")
    parser.add_argument("--sample-size", type=int, default=18, help="nombre maximal de lignes de l'échantillon")
    parser.add_argument("--no-sample", action="store_true", help="ne pas afficher l'échantillon avant le traitement")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    if not args.input.exists():
        raise SystemExit(f"Fichier introuvable : {args.input}")
    header = read_header(args.input)
    resolver = ColumnResolver(header)
    if args.sample_only or not args.no_sample:
        print_sample(args.input, resolver, args.chunksize, args.sample_size)
    if args.sample_only:
        return
    summary = enrich(args)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
