#!/usr/bin/env python3
"""흰색 배경 → 검정 배경 변환 후 S3 업로드 구조로 출력"""

import csv
import subprocess
import sys
from pathlib import Path
from collections import defaultdict

BASE_DIR = Path("/Users/gimjunhyeong/develop/liftupai")
MANIFEST = BASE_DIR / "build/exercise-media/exercise-media-manifest.csv"
OUTPUT_DIR = BASE_DIR / "build/exercise-media-black"
LOG_FILE = OUTPUT_DIR / "convert.log"

FILTER = (
    "geq="
    "r='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,r(X,Y))':"
    "g='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,g(X,Y))':"
    "b='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,b(X,Y))'"
)


def build_file_index():
    """실제 파일 인덱스: 정규화된 키 → [Path, ...]"""
    index = defaultdict(list)
    for pattern in ["ILLUSTRATIONS", "FULL HD 1080P"]:
        base = BASE_DIR / pattern
        if not base.exists():
            continue
        for f in base.rglob("*"):
            if f.is_file() and f.suffix.lower() in {".jpg", ".jpeg", ".png", ".mp4", ".gif"}:
                if "_black" in f.stem:
                    continue
                stem = f.stem.lower().strip()
                index[stem].append(f)
                # 공백 제거 버전도 등록
                normalized = stem.replace(" ", "")
                if normalized != stem:
                    index[normalized].append(f)
    return index


def find_source(rel_path: str, index: dict) -> Path | None:
    if not rel_path:
        return None

    p = Path(rel_path)
    stem_raw = p.stem.lower().strip()
    ext = p.suffix.lower()

    # 1) 정확한 경로
    full = BASE_DIR / rel_path
    if full.exists():
        return full

    # 2) 다양한 정규화 시도
    attempts = [
        stem_raw,
        stem_raw + "1",
        stem_raw.replace(" ", ""),
        stem_raw.replace(" ", "") + "1",
    ]
    for sfx in ["_female", "_male", " _female", " _male"]:
        if stem_raw.endswith(sfx):
            base = stem_raw[: -len(sfx)].strip()
            attempts += [base, base + "1", base.replace(" ", ""), base.replace(" ", "") + "1"]

    for key in attempts:
        if key in index:
            # 같은 확장자 우선
            for f in index[key]:
                if f.suffix.lower() == ext:
                    return f
            return index[key][0]

    return None


def convert_image(src: Path, dst: Path) -> bool:
    dst.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        ["ffmpeg", "-y", "-i", str(src), "-vf", FILTER,
         "-update", "1", "-frames:v", "1", str(dst)],
        capture_output=True, timeout=60,
    )
    return result.returncode == 0


def convert_video(src: Path, dst: Path) -> bool:
    dst.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        ["ffmpeg", "-y", "-i", str(src), "-vf", FILTER,
         "-c:a", "copy", "-preset", "fast", str(dst)],
        capture_output=True, timeout=600,
    )
    return result.returncode == 0


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    log = open(LOG_FILE, "w")

    print("파일 인덱스 빌드 중...")
    index = build_file_index()
    print(f"인덱스 완료: {sum(len(v) for v in index.values())} 항목\n")

    stats = {
        "thumb_ok": 0, "thumb_fail": 0, "thumb_skip": 0,
        "video_ok": 0, "video_fail": 0, "video_skip": 0,
    }

    with open(MANIFEST, newline="") as f:
        rows = list(csv.DictReader(f))

    total = len(rows)
    print(f"총 {total}개 운동 처리 시작...\n")

    for i, row in enumerate(rows, 1):
        slug = row["slug"]

        # === 썸네일 ===
        if row["thumb_status"] == "missing" or not row["thumb_source"]:
            stats["thumb_skip"] += 1
        else:
            src = find_source(row["thumb_source"], index)
            dst = OUTPUT_DIR / row["target_thumb_key"]
            if src and src.suffix.lower() in {".jpg", ".jpeg", ".png", ".gif"}:
                if convert_image(src, dst):
                    stats["thumb_ok"] += 1
                else:
                    stats["thumb_fail"] += 1
                    log.write(f"THUMB FAIL: {slug} ({src})\n")
            else:
                stats["thumb_skip"] += 1
                log.write(f"THUMB NOT FOUND: {slug} ({row['thumb_source']})\n")

        # === 비디오 ===
        if row["video_status"] == "missing" or not row["video_source"]:
            stats["video_skip"] += 1
        else:
            src = find_source(row["video_source"], index)
            dst = OUTPUT_DIR / row["target_video_key"]
            if src and src.suffix.lower() == ".mp4":
                if convert_video(src, dst):
                    stats["video_ok"] += 1
                else:
                    stats["video_fail"] += 1
                    log.write(f"VIDEO FAIL: {slug} ({src})\n")
            else:
                stats["video_skip"] += 1
                log.write(f"VIDEO NOT FOUND: {slug} ({row['video_source']})\n")

        # 진행 상황
        if i % 25 == 0 or i == total:
            t = stats["thumb_ok"]
            v = stats["video_ok"]
            sys.stdout.write(f"\r[{i}/{total}] Thumb={t} Video={v}")
            sys.stdout.flush()
            log.write(f"[{i}/{total}] Thumb={t} Video={v}\n")
            log.flush()

    log.close()
    print(f"\n\n=== 변환 완료 ===")
    print(f"Thumb: OK={stats['thumb_ok']} FAIL={stats['thumb_fail']} SKIP={stats['thumb_skip']}")
    print(f"Video: OK={stats['video_ok']} FAIL={stats['video_fail']} SKIP={stats['video_skip']}")
    print(f"\n출력: {OUTPUT_DIR}/exercises/")
    print(f"S3에 그대로 업로드하면 됩니다: exercises/{{slug}}/thumb.jpg, video.mp4")
    print(f"로그: {LOG_FILE}")


if __name__ == "__main__":
    main()
