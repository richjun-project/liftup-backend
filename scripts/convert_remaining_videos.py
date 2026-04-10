#!/usr/bin/env python3
"""manifest에서 못 찾은 비디오를 전부 변환하여 원본 구조로 출력"""

import subprocess
import sys
from pathlib import Path
from concurrent.futures import ProcessPoolExecutor, as_completed

BASE_DIR = Path("/Users/gimjunhyeong/develop/liftupai")
VIDEO_DIR = BASE_DIR / "FULL HD 1080P"
OUTPUT_DIR = BASE_DIR / "build/exercise-media-black/converted/FULL HD 1080P"

FILTER = (
    "geq="
    "r='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,r(X,Y))':"
    "g='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,g(X,Y))':"
    "b='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,b(X,Y))'"
)

WORKERS = 8


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

    # 모든 비디오 수집
    all_videos = sorted(VIDEO_DIR.rglob("*.mp4"))
    all_videos = [v for v in all_videos if "_black" not in v.stem]
    print(f"총 비디오: {len(all_videos)}")

    # 이미 변환된 건 스킵
    jobs = []
    skip = 0
    for v in all_videos:
        rel = v.relative_to(BASE_DIR)
        dst = BASE_DIR / "build/exercise-media-black/converted" / rel
        if dst.exists() and dst.stat().st_size > 0:
            skip += 1
        else:
            jobs.append((str(v), str(dst)))

    print(f"스킵: {skip}, 변환 필요: {len(jobs)}\n")

    if not jobs:
        print("모두 완료!")
        return

    ok = fail = 0
    with ProcessPoolExecutor(max_workers=WORKERS) as executor:
        futures = {executor.submit(convert_video, job): job for job in jobs}
        done = 0
        for future in as_completed(futures):
            done += 1
            _, success = future.result()
            if success:
                ok += 1
            else:
                fail += 1
            if done % 10 == 0 or done == len(jobs):
                sys.stdout.write(f"\r[{done}/{len(jobs)}] OK={ok} FAIL={fail}")
                sys.stdout.flush()

    print(f"\n\n완료: OK={ok+skip} FAIL={fail}")
    print(f"출력: {OUTPUT_DIR}/")


if __name__ == "__main__":
    main()
