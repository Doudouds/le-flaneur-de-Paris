"""Conservative image-to-monument matching shared by enrichment audits."""

from __future__ import annotations

import math
import re
import unicodedata
from urllib.parse import unquote

GENERIC_TOKENS = {
    "ancien", "ancienne", "actuellement", "immeuble", "monument", "historique",
    "maison", "hotel", "paris", "eglise", "chapelle",
}


def normalize(value: str | None) -> str:
    value = unicodedata.normalize("NFKD", value or "").lower()
    value = "".join(char for char in value if not unicodedata.combining(char))
    value = value.replace("œ", "oe").replace("æ", "ae")
    return re.sub(r"[^a-z0-9]+", " ", value).strip()


def _aliases(monument_name: str, wikipedia_url: str | None) -> list[str]:
    aliases = [normalize(monument_name)]
    if wikipedia_url:
        title = unquote(wikipedia_url.rstrip("/").rsplit("/", 1)[-1]).replace("_", " ")
        if title:
            aliases.append(normalize(title))
    return list(dict.fromkeys(alias for alias in aliases if len(alias) >= 5))


def distance_meters(latitude_a: float, longitude_a: float, latitude_b: float, longitude_b: float) -> float:
    earth_radius_m = 6_371_000.0
    lat_distance = math.radians(latitude_b - latitude_a)
    lon_distance = math.radians(longitude_b - longitude_a)
    haversine = math.sin(lat_distance / 2) ** 2 + math.cos(math.radians(latitude_a)) * math.cos(math.radians(latitude_b)) * math.sin(lon_distance / 2) ** 2
    return earth_radius_m * 2 * math.atan2(math.sqrt(haversine), math.sqrt(1 - haversine))


def score_candidate(
    candidate_text: str,
    monument_name: str,
    wikipedia_url: str | None,
    merimee_reference: str | None,
    candidate_latitude: float | None,
    candidate_longitude: float | None,
    monument_latitude: float,
    monument_longitude: float,
) -> int:
    """Return a score compatible with the Android resolver.

    A reference match without a name/alias or location match scores zero. This
    is intentional: a stale Commons/POP description must not be trusted only
    because it repeats a Mérimée identifier.
    """
    haystack = normalize(candidate_text)
    aliases = _aliases(monument_name, wikipedia_url)
    strongest_alias = 0
    strongest_token_count = 0
    for alias in aliases:
        tokens = [token for token in alias.split() if len(token) >= 5 and token not in GENERIC_TOKENS]
        strongest_alias = max(strongest_alias, sum(token in haystack.split() for token in tokens))
        strongest_token_count = max(strongest_token_count, len(tokens))

    strong_phrase = any(len(alias) >= 8 and alias in haystack for alias in aliases)
    strong_name = strong_phrase or (
        strongest_token_count >= 2 and strongest_alias >= max(2, (strongest_token_count + 1) // 2)
    )
    reference_match = bool(merimee_reference) and normalize(merimee_reference) in haystack
    near_monument = (
        candidate_latitude is not None
        and candidate_longitude is not None
        and distance_meters(candidate_latitude, candidate_longitude, monument_latitude, monument_longitude) <= 750
    )

    if reference_match and strong_name:
        return 100
    if reference_match and near_monument:
        return 96
    if strong_name and near_monument:
        return 90
    if strong_name:
        return 70
    return 0
