#!/usr/bin/env python3
"""Merge the calculated interest ratings into the Android monument asset."""

from __future__ import annotations

import csv
import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEBUG_PATH = ROOT / "outputs" / "heritage_enrichment" / "monuments_enrichis_debug.csv"
ASSET_PATH = ROOT / "app" / "src" / "main" / "assets" / "paris_monuments.json"


def compact(value: str) -> str:
    return "".join(character for character in value.upper() if character.isalnum())


def refs(value: str) -> list[str]:
    return [compact(part) for part in value.split(";") if compact(part)]


def distance_sq(first_lat: float, first_lon: float, second_lat: str, second_lon: str) -> float:
    try:
        latitude = float(second_lat)
        longitude = float(second_lon)
    except (TypeError, ValueError):
        return math.inf
    return (first_lat - latitude) ** 2 + (first_lon - longitude) ** 2


def main() -> None:
    by_reference: dict[str, list[dict[str, str]]] = {}
    with DEBUG_PATH.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            if not row.get("etoiles"):
                continue
            for reference in refs(row.get("identifiant_merimee", "")):
                by_reference.setdefault(reference, []).append(row)

    asset = json.loads(ASSET_PATH.read_text(encoding="utf-8"))
    matched = 0
    missing = 0
    for item in asset:
        candidates = [row for reference in refs(item.get("merimeeReference", "")) for row in by_reference.get(reference, [])]
        if not candidates:
            missing += 1
            continue
        best = min(
            candidates,
            key=lambda row: distance_sq(
                float(item["latitude"]),
                float(item["longitude"]),
                row.get("latitude", ""),
                row.get("longitude", ""),
            ),
        )
        item["interestScore"] = int(best["note_globale"])
        item["interestPercentile"] = int(best["percentile_interet"])
        item["stars"] = int(best["etoiles"])
        matched += 1

    ASSET_PATH.write_text(json.dumps(asset, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"asset_rows": len(asset), "matched": matched, "missing": missing}, ensure_ascii=False))


if __name__ == "__main__":
    main()
