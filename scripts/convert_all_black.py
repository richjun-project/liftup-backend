#!/usr/bin/env python3
"""manifest 기준 필요한 파일만 흰배경→검정 변환, S3 구조로 직접 출력"""

import csv
import subprocess
import sys
from pathlib import Path
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor, as_completed

BASE_DIR = Path("/Users/gimjunhyeong/develop/liftupai")
MANIFEST = BASE_DIR / "build/exercise-media/exercise-media-manifest.csv"
OUTPUT_DIR = BASE_DIR / "build/exercise-media-black"
S3_DIR = OUTPUT_DIR / "exercises"
LOG_FILE = OUTPUT_DIR / "convert_all.log"

FILTER = (
    "geq="
    "r='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,r(X,Y))':"
    "g='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,g(X,Y))':"
    "b='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,b(X,Y))'"
)

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif"}
WORKERS = 8


def build_index(base_dir: str, exts: set) -> dict:
    """파일 인덱스 생성: lowercase_stem → [Path, ...]"""
    index = defaultdict(list)
    base = BASE_DIR / base_dir
    if not base.exists():
        return index
    for f in base.rglob("*"):
        if not f.is_file() or "_black" in f.stem:
            continue
        if f.suffix.lower() not in exts:
            continue
        stem = f.stem.lower().strip()
        index[stem].append(f)
        norm = stem.replace(" ", "")
        if norm != stem:
            index[norm].append(f)
    return index


def find_source(rel_path: str, index: dict) -> Path | None:
    if not rel_path:
        return None
    p = Path(rel_path)
    full = BASE_DIR / rel_path
    if full.exists():
        return full

    stem = p.stem.lower().strip()
    attempts = [stem, stem + "1", stem.replace(" ", ""), stem.replace(" ", "") + "1"]
    for sfx in ["_female", "_male", " _female", " _male"]:
        if stem.endswith(sfx):
            b = stem[:-len(sfx)].strip()
            attempts += [b, b + "1", b.replace(" ", ""), b.replace(" ", "") + "1"]
    for key in attempts:
        if key in index:
            return index[key][0]
    return None


def convert_image(args: tuple) -> tuple[str, bool]:
    src, dst = args
    Path(dst).parent.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(
        ["ffmpeg", "-y", "-i", src, "-vf", FILTER,
         "-update", "1", "-frames:v", "1", dst],
        capture_output=True, timeout=60,
    )
    return (src, r.returncode == 0)


def convert_video(args: tuple) -> tuple[str, bool]:
    src, dst = args
    Path(dst).parent.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(
        ["ffmpeg", "-y", "-i", src, "-vf", FILTER,
         "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p",
         "-c:a", "copy", "-preset", "fast", "-movflags", "+faststart",
         dst],
        capture_output=True, timeout=600,
    )
    return (src, r.returncode == 0)


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    log = open(LOG_FILE, "w")

    # 인덱스 빌드
    print("인덱스 빌드 중...")
    img_index = build_index("ILLUSTRATIONS", IMAGE_EXTS)
    vid_index = build_index("FULL HD 1080P", {".mp4"})
    print(f"이미지 키: {len(img_index)}, 비디오 키: {len(vid_index)}\n")

    # manifest 읽기 & 매칭
    with open(MANIFEST, newline="") as f:
        rows = list(csv.DictReader(f))

    thumb_jobs = []  # (src, dst)
    video_jobs = []
    t_skip = v_skip = 0

    for row in rows:
        slug = row["slug"]

        # 썸네일 매칭
        if row["thumb_status"] != "missing" and row["thumb_source"]:
            src = find_source(row["thumb_source"], img_index)
            if src:
                dst = str(S3_DIR / slug / "thumb.jpg")
                thumb_jobs.append((str(src), dst))
            else:
                t_skip += 1
                log.write(f"THUMB NOT FOUND: {slug} ({row['thumb_source']})\n")
        else:
            t_skip += 1

        # 비디오 매칭
        if row["video_status"] != "missing" and row["video_source"]:
            src = find_source(row["video_source"], vid_index)
            if src:
                dst = str(S3_DIR / slug / "video.mp4")
                video_jobs.append((str(src), dst))
            else:
                v_skip += 1
                log.write(f"VIDEO NOT FOUND: {slug} ({row['video_source']})\n")
        else:
            v_skip += 1

    print(f"매칭 결과: 썸네일 {len(thumb_jobs)}개, 비디오 {len(video_jobs)}개")
    print(f"못 찾음: 썸네일 {t_skip}개, 비디오 {v_skip}개\n")

    # === 썸네일 변환 (병렬) ===
    t_ok = t_fail = 0
    total = len(thumb_jobs)

    # 이미 변환된 건 제외
    todo_thumbs = [(s, d) for s, d in thumb_jobs if not (Path(d).exists() and Path(d).stat().st_size > 0)]
    already = total - len(todo_thumbs)
    t_ok = already
    print(f"=== 썸네일 {total}개 ({already}개 스킵, {len(todo_thumbs)}개 변환) ===")

    with ProcessPoolExecutor(max_workers=WORKERS) as executor:
        futures = {executor.submit(convert_image, job): job for job in todo_thumbs}
        done = 0
        for future in as_completed(futures):
            done += 1
            _, ok = future.result()
            if ok:
                t_ok += 1
            else:
                t_fail += 1
            if done % 50 == 0 or done == len(todo_thumbs):
                sys.stdout.write(f"\r썸네일: [{done}/{len(todo_thumbs)}] OK={t_ok}")
                sys.stdout.flush()

    print(f"\n썸네일 완료: OK={t_ok} FAIL={t_fail}\n")

    # === 비디오 변환 (병렬, yuv420p) ===
    v_ok = v_fail = 0
    total = len(video_jobs)

    # 이미 변환된 건 재인코딩 (yuv420p 보장 위해 모두 재변환)
    print(f"=== 비디오 {total}개 병렬 변환 ({WORKERS} workers, yuv420p) ===")

    with ProcessPoolExecutor(max_workers=WORKERS) as executor:
        futures = {executor.submit(convert_video, job): job for job in video_jobs}
        done = 0
        for future in as_completed(futures):
            done += 1
            _, ok = future.result()
            if ok:
                v_ok += 1
            else:
                v_fail += 1
            if done % 10 == 0 or done == total:
                sys.stdout.write(f"\r비디오: [{done}/{total}] OK={v_ok} FAIL={v_fail}")
                sys.stdout.flush()
                log.flush()

    print(f"\n비디오 완료: OK={v_ok} FAIL={v_fail}\n")

    log.close()
    print(f"=== 전체 완료 ===")
    print(f"S3 업로드: {S3_DIR}/")
    print(f"  exercises/{{slug}}/thumb.jpg")
    print(f"  exercises/{{slug}}/video.mp4")
    print(f"로그: {LOG_FILE}")


if __name__ == "__main__":
    main()
