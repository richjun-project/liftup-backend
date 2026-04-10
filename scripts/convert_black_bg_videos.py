#!/usr/bin/env python3
"""비디오만 흰색 배경 → 검정 배경 변환 (썸네일은 이미 완료)"""

import csv
import subprocess
import sys
from pathlib import Path
from collections import defaultdict

BASE_DIR = Path("/Users/gimjunhyeong/develop/liftupai")
MANIFEST = BASE_DIR / "build/exercise-media/exercise-media-manifest.csv"
OUTPUT_DIR = BASE_DIR / "build/exercise-media-black"
LOG_FILE = OUTPUT_DIR / "convert_videos.log"

FILTER = (
    "geq="
    "r='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,r(X,Y))':"
    "g='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,g(X,Y))':"
    "b='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,b(X,Y))'"
)


def build_video_index():
    """비디오 파일만 인덱스"""
    index = defaultdict(list)
    base = BASE_DIR / "FULL HD 1080P"
    for f in base.rglob("*.mp4"):
        if "_black" in f.stem:
            continue
        stem = f.stem.lower().strip()
        index[stem].append(f)
        normalized = stem.replace(" ", "")
        if normalized != stem:
            index[normalized].append(f)
    return index


def find_video(rel_path: str, index: dict) -> Path | None:
    if not rel_path:
        return None

    p = Path(rel_path)
    stem_raw = p.stem.lower().strip()

    # 정확한 경로
    full = BASE_DIR / rel_path
    if full.exists():
        return full

    # 다양한 정규화 시도
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
            return index[key][0]

    return None


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

    print("비디오 인덱스 빌드 중...")
    index = build_video_index()
    print(f"비디오 파일: {sum(len(v) for v in index.values())} 항목\n")

    ok = fail = skip = 0

    with open(MANIFEST, newline="") as f:
        rows = list(csv.DictReader(f))

    # 이미 변환된 비디오 건너뛰기
    need = []
    for row in rows:
        if row["video_status"] == "missing" or not row["video_source"]:
            skip += 1
            continue
        dst = OUTPUT_DIR / row["target_video_key"]
        if dst.exists() and dst.stat().st_size > 0:
            ok += 1  # 이미 변환됨
            continue
        need.append(row)

    total = len(need)
    print(f"변환 필요: {total}개 (이미 완료: {ok}, 스킵: {skip})\n")

    for i, row in enumerate(need, 1):
        slug = row["slug"]
        src = find_video(row["video_source"], index)
        dst = OUTPUT_DIR / row["target_video_key"]

        if src:
            if convert_video(src, dst):
                ok += 1
            else:
                fail += 1
                log.write(f"FAIL: {slug} ({src})\n")
        else:
            skip += 1
            log.write(f"NOT FOUND: {slug} ({row['video_source']})\n")

        if i % 10 == 0 or i == total:
            sys.stdout.write(f"\r[{i}/{total}] OK={ok} FAIL={fail}")
            sys.stdout.flush()
            log.write(f"[{i}/{total}] OK={ok}\n")
            log.flush()

    log.close()
    print(f"\n\n=== 비디오 변환 완료 ===")
    print(f"OK={ok} FAIL={fail} SKIP={skip}")
    print(f"출력: {OUTPUT_DIR}/exercises/")
    print(f"로그: {LOG_FILE}")


if __name__ == "__main__":
    main()
