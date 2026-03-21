#!/usr/bin/env python3
"""
Build a slug-based media manifest for exercise thumbnails and videos.

The script reads the normalized exercise catalog, scans the raw media folders,
matches source files to exercises, and writes a reviewable CSV manifest.
It can also materialize the final upload tree:

  exercises/<exercise-slug>/thumb.jpg
  exercises/<exercise-slug>/video.mp4
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import shutil
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Iterable


CATEGORY_FOLDER_HINTS = {
    "CHEST": {"Chest", "Powerlifting"},
    "BACK": {"Back", "Powerlifting"},
    "LEGS": {"Legs", "Powerlifting"},
    "SHOULDERS": {"Shoulders", "Powerlifting"},
    "ARMS": {"Biceps", "Triceps", "Forearms"},
    "CORE": {"Abdominals"},
    "FULL_BODY": {"Calisthenics-Cardio-Plyo-Functional", "Powerlifting"},
}

KEYWORD_FOLDER_HINTS = {
    "stretch": {"Stretching - Mobility", "Yoga"},
    "mobility": {"Stretching - Mobility", "Yoga"},
    "yoga": {"Yoga"},
    "cardio": {"Calisthenics-Cardio-Plyo-Functional"},
    "jump": {"Calisthenics-Cardio-Plyo-Functional"},
    "burpee": {"Calisthenics-Cardio-Plyo-Functional"},
    "walkout": {"Calisthenics-Cardio-Plyo-Functional"},
}

PHRASE_ALIASES = (
    ("alternating", "alternate"),
    ("dumbbells", "dumbbell"),
    ("barbells", "barbell"),
    ("kettlebells", "kettlebell"),
    ("bands", "band"),
    ("bicep", "biceps"),
    ("tricep", "triceps"),
    ("ez-bar", "ez bar"),
    ("ezbar", "ez bar"),
    ("crossover machine", "cable"),
    ("resistance band", "band"),
)

TOKEN_ALIASES = {
    "alternating": "alternate",
    "dumbbells": "dumbbell",
    "barbells": "barbell",
    "kettlebells": "kettlebell",
    "bands": "band",
    "bicep": "biceps",
    "tricep": "triceps",
}

EXACT_SCORE = 1.0
FUZZY_THRESHOLD = 0.90
REVIEW_THRESHOLD = 0.78


@dataclass(frozen=True)
class ExerciseRecord:
    slug: str
    display_name: str
    category: str
    source_category: str | None
    canonical_text: str
    canonical_tokens: frozenset[str]
    exact_keys: frozenset[str]


@dataclass(frozen=True)
class MediaFile:
    path: Path
    top_folder: str
    canonical_text: str
    canonical_tokens: frozenset[str]
    exact_keys: frozenset[str]
    is_female_variant: bool
    stem_length: int


@dataclass(frozen=True)
class MatchResult:
    status: str
    score: float
    source: str


def normalize_base_text(value: str) -> str:
    text = value.lower().strip()
    text = text.replace("&", " and ")
    text = re.sub(r"\(([^)]*)\)", r" \1 ", text)
    text = re.sub(r"([a-z])([0-9])", r"\1 \2", text)
    text = re.sub(r"([0-9])([a-z])", r"\1 \2", text)
    text = text.replace("_", " ").replace("-", " ")
    for source, target in PHRASE_ALIASES:
        text = text.replace(source, target)
    text = re.sub(r"[^a-z0-9]+", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def canonical_tokens(value: str) -> list[str]:
    text = normalize_base_text(value)
    tokens = text.split()
    if tokens and tokens[-1] in {"1", "2", "3"} and "version" not in tokens:
        tokens = tokens[:-1]
    normalized: list[str] = []
    for token in tokens:
        normalized.append(TOKEN_ALIASES.get(token, token))
    return normalized


def canonical_text(value: str) -> str:
    return " ".join(canonical_tokens(value))


def slug_key(value: str) -> str:
    return canonical_text(value).replace(" ", "-")


def canonical_variants(value: str) -> set[str]:
    text = value.rsplit(".", 1)[0]
    forms = {
        text,
        re.sub(r"[_ -]*(female|male)\b", "", text, flags=re.IGNORECASE),
        re.sub(r"\bversion\s*2\b", "version 2", text, flags=re.IGNORECASE),
        re.sub(r"\bversion\s*3\b", "version 3", text, flags=re.IGNORECASE),
    }
    variants = set()
    for form in forms:
        cleaned = canonical_text(form)
        if cleaned:
            variants.add(cleaned)
            variants.add(cleaned.replace(" ", "-"))
    return variants


def load_exercises(catalog_path: Path) -> list[ExerciseRecord]:
    catalog = json.loads(catalog_path.read_text())
    exercises: list[ExerciseRecord] = []
    for record in catalog:
        translations = record.get("translations") or []
        display_name = translations[0]["name"] if translations else record["slug"]
        canonical = canonical_text(record["slug"])
        exercises.append(
            ExerciseRecord(
                slug=record["slug"],
                display_name=display_name,
                category=record["category"],
                source_category=record.get("sourceCategory"),
                canonical_text=canonical,
                canonical_tokens=frozenset(canonical.split()),
                exact_keys=frozenset({
                    canonical,
                    slug_key(record["slug"]),
                    canonical_text(display_name),
                    slug_key(display_name),
                }),
            )
        )
    return exercises


def load_media_files(root: Path) -> list[MediaFile]:
    files: list[MediaFile] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.name.startswith("."):
            continue
        rel_parts = path.relative_to(root).parts
        top_folder = rel_parts[0] if rel_parts else root.name
        exact_keys = canonical_variants(path.name)
        canonical = next(iter(sorted(exact_keys))).replace("-", " ")
        files.append(
            MediaFile(
                path=path,
                top_folder=top_folder,
                canonical_text=canonical,
                canonical_tokens=frozenset(canonical.split()),
                exact_keys=frozenset(exact_keys),
                is_female_variant=bool(re.search(r"female", path.stem, re.IGNORECASE)),
                stem_length=len(path.stem),
            )
        )
    return files


def preferred_folders(exercise: ExerciseRecord) -> set[str]:
    folders = set(CATEGORY_FOLDER_HINTS.get(exercise.category, set()))
    name = exercise.canonical_text
    for keyword, hinted in KEYWORD_FOLDER_HINTS.items():
        if keyword in name:
            folders.update(hinted)
    return folders


def exact_match(exercise: ExerciseRecord, media_files: list[MediaFile]) -> MatchResult | None:
    matches: list[MediaFile] = []
    for media in media_files:
        if exercise.exact_keys & media.exact_keys:
            matches.append(media)
    if not matches:
        return None
    preferred = preferred_folders(exercise)
    matches.sort(
        key=lambda media: (
            media.top_folder not in preferred,
            media.is_female_variant,
            media.stem_length,
            str(media.path),
        )
    )
    return MatchResult("exact", EXACT_SCORE, str(matches[0].path))


def fuzzy_score(exercise: ExerciseRecord, media: MediaFile, preferred: set[str]) -> float:
    overlap = exercise.canonical_tokens & media.canonical_tokens
    if not overlap:
        return 0.0

    coverage = len(overlap) / max(len(exercise.canonical_tokens), 1)
    jaccard = len(overlap) / len(exercise.canonical_tokens | media.canonical_tokens)
    sequence = SequenceMatcher(None, exercise.canonical_text, media.canonical_text).ratio()
    score = (sequence * 0.45) + (coverage * 0.35) + (jaccard * 0.20)

    if media.top_folder in preferred:
        score += 0.04
    if media.is_female_variant:
        score -= 0.03

    return score


def fuzzy_match(exercise: ExerciseRecord, media_files: list[MediaFile]) -> MatchResult:
    preferred = preferred_folders(exercise)
    pool = [media for media in media_files if media.top_folder in preferred] or media_files

    best_media: MediaFile | None = None
    best_score = 0.0
    for media in pool:
        score = fuzzy_score(exercise, media, preferred)
        if score > best_score:
            best_score = score
            best_media = media

    if best_media is None or best_score < REVIEW_THRESHOLD:
        return MatchResult("missing", 0.0, "")
    if best_score >= FUZZY_THRESHOLD:
        return MatchResult("fuzzy", round(best_score, 4), str(best_media.path))
    return MatchResult("review", round(best_score, 4), str(best_media.path))


def match_media(exercises: list[ExerciseRecord], media_files: list[MediaFile]) -> dict[str, MatchResult]:
    results: dict[str, MatchResult] = {}
    for exercise in exercises:
        exact = exact_match(exercise, media_files)
        results[exercise.slug] = exact if exact is not None else fuzzy_match(exercise, media_files)
    return results


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def materialize_media_tree(
    output_root: Path,
    exercises: Iterable[ExerciseRecord],
    thumb_matches: dict[str, MatchResult],
    video_matches: dict[str, MatchResult],
) -> None:
    for exercise in exercises:
        target_dir = output_root / "exercises" / exercise.slug
        target_dir.mkdir(parents=True, exist_ok=True)

        thumb = thumb_matches[exercise.slug]
        if thumb.source and thumb.status != "missing":
            shutil.copy2(thumb.source, target_dir / "thumb.jpg")

        video = video_matches[exercise.slug]
        if video.source and video.status != "missing":
            shutil.copy2(video.source, target_dir / "video.mp4")


def write_manifest(
    output_dir: Path,
    exercises: list[ExerciseRecord],
    thumb_matches: dict[str, MatchResult],
    video_matches: dict[str, MatchResult],
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = output_dir / "exercise-media-manifest.csv"
    with manifest_path.open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "slug",
                "display_name",
                "category",
                "thumb_status",
                "thumb_score",
                "thumb_source",
                "video_status",
                "video_score",
                "video_source",
                "target_prefix",
                "target_thumb_key",
                "target_video_key",
            ]
        )
        for exercise in exercises:
            thumb = thumb_matches[exercise.slug]
            video = video_matches[exercise.slug]
            writer.writerow(
                [
                    exercise.slug,
                    exercise.display_name,
                    exercise.category,
                    thumb.status,
                    thumb.score,
                    thumb.source,
                    video.status,
                    video.score,
                    video.source,
                    f"exercises/{exercise.slug}/",
                    f"exercises/{exercise.slug}/thumb.jpg",
                    f"exercises/{exercise.slug}/video.mp4",
                ]
            )

    summary_path = output_dir / "exercise-media-summary.txt"
    summary_path.write_text(
        "\n".join(
            [
                summarize("thumb", thumb_matches),
                summarize("video", video_matches),
            ]
        )
        + "\n"
    )


def summarize(label: str, matches: dict[str, MatchResult]) -> str:
    counts = {"exact": 0, "fuzzy": 0, "review": 0, "missing": 0}
    for match in matches.values():
        counts[match.status] = counts.get(match.status, 0) + 1
    return (
        f"{label}: exact={counts['exact']}, fuzzy={counts['fuzzy']}, "
        f"review={counts['review']}, missing={counts['missing']}"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--catalog",
        default="src/main/resources/catalog/exercise-catalog.json",
        type=Path,
        help="Path to the normalized exercise catalog JSON",
    )
    parser.add_argument(
        "--illustrations-dir",
        default="ILLUSTRATIONS",
        type=Path,
        help="Directory containing raw thumbnail images",
    )
    parser.add_argument(
        "--videos-dir",
        default="FULL HD 1080P",
        type=Path,
        help="Directory containing raw videos",
    )
    parser.add_argument(
        "--output-dir",
        default="build/exercise-media",
        type=Path,
        help="Directory for manifest outputs",
    )
    parser.add_argument(
        "--materialize-dir",
        type=Path,
        help="Optional directory where the final upload tree is copied",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    exercises = load_exercises(args.catalog)
    image_files = load_media_files(args.illustrations_dir)
    video_files = load_media_files(args.videos_dir)

    thumb_matches = match_media(exercises, image_files)
    video_matches = match_media(exercises, video_files)

    write_manifest(args.output_dir, exercises, thumb_matches, video_matches)

    if args.materialize_dir is not None:
        materialize_media_tree(args.materialize_dir, exercises, thumb_matches, video_matches)

    print(f"Manifest written to {args.output_dir / 'exercise-media-manifest.csv'}")
    print(summarize("thumb", thumb_matches))
    print(summarize("video", video_matches))
    if args.materialize_dir is not None:
        print(f"Upload tree copied to {args.materialize_dir}")


if __name__ == "__main__":
    main()
