#!/usr/bin/env python3
"""
모든 운동 비디오를 검정 배경으로 변환하여 S3 업로드 구조로 출력

소스 우선순위:
  1순위: 1000+ GREEN SCREEN VIDEOS (그린→검정)
  2순위: FULL HD 1080P (흰배경→검정)

사용법:
  python3 scripts/convert_green_to_black.py --dry-run   # 매칭만 확인
  python3 scripts/convert_green_to_black.py              # 실제 변환
  python3 scripts/convert_green_to_black.py --workers 4  # workers 조절
"""

import argparse
import csv
import json
import re
import subprocess
import sys
import time
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor, as_completed
from difflib import SequenceMatcher
from pathlib import Path

# ─── 설정 ───────────────────────────────────────────
BASE_DIR = Path("/Users/gimjunhyeong/Develop/liftupai")
CATALOG = BASE_DIR / "src/main/resources/catalog/exercise-catalog.json"
GREEN_DIR = BASE_DIR / "1000+ GREEN SCREEN VIDEOS"
WHITE_DIR = BASE_DIR / "FULL HD 1080P"
OUTPUT_DIR = BASE_DIR / "build/exercise-media-black/exercises"
LOG_FILE = BASE_DIR / "build/exercise-media-black/convert_videos_all.log"
MAPPING_CSV = BASE_DIR / "build/exercise-media-green/video-mapping-all.csv"

WORKERS = 8
MATCH_THRESHOLD = 0.72

# 그린스크린 → 검정: chromakey
FILTER_GREEN = (
    "[0:v]split[main][bg];"
    "[bg]drawbox=c=black:t=fill[black];"
    "[main]format=yuva420p,chromakey=0x00D700:0.28:0.08[fg];"
    "[black][fg]overlay=format=auto"
)

# 흰배경 → 검정: RGB합 680+ 픽셀을 검정으로
FILTER_WHITE = (
    "geq="
    "r='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,r(X,Y))':"
    "g='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,g(X,Y))':"
    "b='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,b(X,Y))'"
)

# ─── 정규화 ─────────────────────────────────────────
TYPO_FIXES = {
    "bycicle": "bicycle", "coners": "corners", "dumbells": "dumbbells",
    "stepback": "step back", "peck deck": "pec deck",
}
STRIP_TOKENS = {"1", "2", "3", "female", "male", "version"}


def normalize(value: str) -> str:
    text = value.lower().strip()
    text = re.sub(r"\(([^)]*)\)", r" \1 ", text)
    text = text.replace("_", " ").replace("-", " ").replace("&", " and ")
    for typo, fix in TYPO_FIXES.items():
        text = text.replace(typo, fix)
    text = re.sub(r"[^a-z0-9]+", " ", text).strip()
    text = re.sub(r"\s+", " ", text)
    tokens = text.split()
    while tokens and tokens[-1] in STRIP_TOKENS:
        tokens.pop()
    return " ".join(tokens)


# ─── 매칭 ───────────────────────────────────────────
def load_exercises() -> list[dict]:
    with open(CATALOG) as f:
        catalog = json.load(f)
    exercises = []
    for e in catalog:
        en_name = e["slug"]
        for t in e.get("translations", []):
            if t["locale"] == "en":
                en_name = t["name"]
        sn = normalize(e["slug"])
        nn = normalize(en_name)
        exercises.append({
            "slug": e["slug"], "name": en_name,
            "slug_norm": sn, "name_norm": nn,
            "tokens": frozenset(sn.split()),
        })
    return exercises


def load_video_dir(directory: Path) -> dict[str, Path]:
    """고유 비디오 {norm: path} — non-female 우선"""
    vid_map: dict[str, Path] = {}
    if not directory.exists():
        return vid_map
    for f in sorted(directory.rglob("*.mp4")):
        if "_black" in f.stem:
            continue
        norm = normalize(f.stem)
        is_female = "female" in f.stem.lower()
        if norm not in vid_map or not is_female:
            vid_map[norm] = f
    return vid_map


def match_score(ex: dict, vnorm: str) -> float:
    s1 = SequenceMatcher(None, ex["slug_norm"], vnorm).ratio()
    s2 = SequenceMatcher(None, ex["name_norm"], vnorm).ratio()
    base = max(s1, s2)
    vtokens = frozenset(vnorm.split())
    overlap = len(ex["tokens"] & vtokens)
    total = len(ex["tokens"] | vtokens)
    if total > 0:
        base = base * 0.7 + (overlap / total) * 0.3
    return base


def match_exercises_to_videos(
    exercises: list[dict], vid_map: dict[str, Path]
) -> dict[str, Path]:
    """양방향 1:1 매칭"""
    matched: dict[str, Path] = {}
    used: set[str] = set()

    # Pass 1: 정확 매칭
    for ex in exercises:
        for key in [ex["slug_norm"], ex["name_norm"]]:
            if key in vid_map and key not in used:
                matched[ex["slug"]] = vid_map[key]
                used.add(key)
                break

    # Pass 2: 포함 매칭
    for ex in exercises:
        if ex["slug"] in matched:
            continue
        for vn, vp in vid_map.items():
            if vn in used:
                continue
            sn, nn = ex["slug_norm"], ex["name_norm"]
            if (sn in vn or vn in sn) and abs(len(sn) - len(vn)) < 12:
                matched[ex["slug"]] = vp
                used.add(vn)
                break
            if (nn in vn or vn in nn) and abs(len(nn) - len(vn)) < 12:
                matched[ex["slug"]] = vp
                used.add(vn)
                break

    # Pass 3: 퍼지 매칭
    remaining_ex = [e for e in exercises if e["slug"] not in matched]
    remaining_vid = {n: p for n, p in vid_map.items() if n not in used}
    pairs = []
    for ex in remaining_ex:
        for vn, vp in remaining_vid.items():
            score = match_score(ex, vn)
            if score >= MATCH_THRESHOLD:
                pairs.append((score, ex["slug"], vn, vp))
    pairs.sort(reverse=True)
    for score, slug, vn, vp in pairs:
        if slug in matched or vn in used:
            continue
        matched[slug] = vp
        used.add(vn)

    return matched


# ─── 변환 ───────────────────────────────────────────
def convert_video(args: tuple) -> tuple[str, bool, str]:
    slug, src, dst, source_type = args
    Path(dst).parent.mkdir(parents=True, exist_ok=True)

    if source_type == "green":
        cmd = [
            "ffmpeg", "-y", "-i", src,
            "-filter_complex", FILTER_GREEN,
            "-c:v", "libx264", "-profile:v", "main",
            "-pix_fmt", "yuv420p", "-preset", "fast",
            "-movflags", "+faststart", "-c:a", "copy", dst,
        ]
    else:  # white
        cmd = [
            "ffmpeg", "-y", "-i", src,
            "-vf", FILTER_WHITE,
            "-c:v", "libx264", "-profile:v", "main",
            "-pix_fmt", "yuv420p", "-preset", "fast",
            "-movflags", "+faststart", "-c:a", "copy", dst,
        ]

    r = subprocess.run(cmd, capture_output=True, timeout=600)
    if r.returncode != 0:
        return (slug, False, r.stderr.decode()[-300:])
    return (slug, True, "")


# ─── 메인 ───────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="모든 비디오 → 검정 배경 변환")
    parser.add_argument("--dry-run", action="store_true", help="매칭만 확인")
    parser.add_argument("--workers", type=int, default=WORKERS)
    args = parser.parse_args()

    print("=== 카탈로그 로드 ===")
    exercises = load_exercises()
    print(f"운동: {len(exercises)}개\n")

    # 1) 그린스크린 매칭 (1순위)
    print("=== 그린스크린 비디오 매칭 ===")
    green_map = load_video_dir(GREEN_DIR)
    print(f"고유 비디오: {len(green_map)}개")
    green_matched = match_exercises_to_videos(exercises, green_map)
    print(f"매칭됨: {len(green_matched)}개\n")

    # 2) 흰배경 매칭 (2순위 — 그린에서 빠진 것만)
    print("=== 흰배경 비디오 매칭 (나머지) ===")
    white_map = load_video_dir(WHITE_DIR)
    print(f"고유 비디오: {len(white_map)}개")
    remaining = [e for e in exercises if e["slug"] not in green_matched]
    white_matched = match_exercises_to_videos(remaining, white_map)
    print(f"추가 매칭: {len(white_matched)}개\n")

    # 3) 합산
    # source_type 기록: slug → (path, "green"|"white")
    final_mapping: dict[str, tuple[Path, str]] = {}
    for slug, path in green_matched.items():
        final_mapping[slug] = (path, "green")
    for slug, path in white_matched.items():
        final_mapping[slug] = (path, "white")

    total_matched = len(final_mapping)
    total_missing = len(exercises) - total_matched
    green_count = sum(1 for _, t in final_mapping.values() if t == "green")
    white_count = sum(1 for _, t in final_mapping.values() if t == "white")

    print("=== 최종 결과 ===")
    print(f"총 매칭: {total_matched}개 (그린: {green_count}, 흰배경: {white_count})")
    print(f"미매칭: {total_missing}개")

    # 매핑 CSV 저장
    MAPPING_CSV.parent.mkdir(parents=True, exist_ok=True)
    with open(MAPPING_CSV, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["slug", "video_path", "source_type", "status"])
        for ex in exercises:
            if ex["slug"] in final_mapping:
                path, stype = final_mapping[ex["slug"]]
                w.writerow([ex["slug"], str(path), stype, "matched"])
            else:
                w.writerow([ex["slug"], "", "", "missing"])
    print(f"매핑 저장: {MAPPING_CSV}\n")

    # 변환 작업 수집
    jobs = []
    already = 0
    for slug, (src_path, source_type) in final_mapping.items():
        dst = OUTPUT_DIR / slug / "video.mp4"
        if dst.exists() and dst.stat().st_size > 0:
            already += 1
            continue
        jobs.append((slug, str(src_path), str(dst), source_type))

    green_jobs = sum(1 for j in jobs if j[3] == "green")
    white_jobs = sum(1 for j in jobs if j[3] == "white")
    print(f"변환 대상: {len(jobs)}개 (그린: {green_jobs}, 흰배경: {white_jobs})")
    print(f"이미 완료: {already}개")

    if args.dry_run:
        print("\n--- 드라이런 모드 (변환 안 함) ---")
        total_mb = sum(p.stat().st_size for p, _ in final_mapping.values()) / (1024 * 1024)
        print(f"입력 비디오 총 용량: {total_mb:.0f} MB")

        print(f"\n미매칭 운동 목록 (상위 30개):")
        missing_slugs = sorted([e["slug"] for e in exercises if e["slug"] not in final_mapping])
        for s in missing_slugs[:30]:
            print(f"  {s}")
        if len(missing_slugs) > 30:
            print(f"  ... 외 {len(missing_slugs) - 30}개")
        return

    if not jobs:
        print("\n모두 변환 완료!")
        _print_final_stats()
        return

    # 변환 실행
    LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
    log = open(LOG_FILE, "w")
    ok = fail = 0
    start = time.time()

    print(f"\n변환 시작 ({args.workers} workers)...\n")
    with ProcessPoolExecutor(max_workers=args.workers) as executor:
        futures = {executor.submit(convert_video, job): job[0] for job in jobs}
        for future in as_completed(futures):
            slug, success, err = future.result()
            if success:
                ok += 1
            else:
                fail += 1
                log.write(f"FAIL: {slug} — {err}\n")
            total_done = ok + fail
            if total_done % 10 == 0 or total_done == len(jobs):
                elapsed = time.time() - start
                rate = total_done / elapsed if elapsed > 0 else 0
                eta = (len(jobs) - total_done) / rate if rate > 0 else 0
                sys.stdout.write(
                    f"\r[{total_done}/{len(jobs)}] OK={ok} FAIL={fail} "
                    f"({rate:.1f}/s, ETA {eta:.0f}s)"
                )
                sys.stdout.flush()
                log.flush()

    log.close()
    elapsed = time.time() - start
    print(f"\n\n=== 변환 완료 ({elapsed:.0f}초) ===")
    print(f"성공: {ok}, 실패: {fail}")
    print(f"로그: {LOG_FILE}")
    _print_final_stats()


def _print_final_stats():
    total_videos = sum(
        1 for d in OUTPUT_DIR.iterdir()
        if d.is_dir() and (d / "video.mp4").exists()
    )
    total_thumbs = sum(
        1 for d in OUTPUT_DIR.iterdir()
        if d.is_dir() and (d / "thumb.jpg").exists()
    )
    print(f"\n빌드 폴더 최종:")
    print(f"  thumb.jpg: {total_thumbs}개")
    print(f"  video.mp4: {total_videos}개")
    print(f"  경로: {OUTPUT_DIR}/")


if __name__ == "__main__":
    main()
