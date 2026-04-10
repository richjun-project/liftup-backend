#!/usr/bin/env python3
"""
upload-tree의 1318개 비디오를 흰배경→검정 변환하여
build/exercise-media-black/exercises/{slug}/video.mp4 에 출력

사용법:
  python3 scripts/convert_upload_tree_videos.py --dry-run   # 확인만
  python3 scripts/convert_upload_tree_videos.py              # 실행 (8 workers)
  python3 scripts/convert_upload_tree_videos.py --workers 4  # workers 조절
"""

import argparse
import subprocess
import sys
import time
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

BASE_DIR = Path("/Users/gimjunhyeong/Develop/liftupai")
INPUT_DIR = BASE_DIR / "build/upload-tree/exercises"
OUTPUT_DIR = BASE_DIR / "build/exercise-media-black/exercises"

WORKERS = 8

# 흰배경(RGB합 680+) → 검정
FILTER_WHITE = (
    "geq="
    "r='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,r(X,Y))':"
    "g='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,g(X,Y))':"
    "b='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,b(X,Y))'"
)


def convert_video(args: tuple) -> tuple[str, bool, str]:
    slug, src, dst = args
    Path(dst).parent.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(
        [
            "ffmpeg", "-y", "-i", src,
            "-vf", FILTER_WHITE,
            "-c:v", "libx264", "-profile:v", "main",
            "-pix_fmt", "yuv420p", "-preset", "fast",
            "-movflags", "+faststart", "-c:a", "copy",
            dst,
        ],
        capture_output=True, timeout=600,
    )
    if r.returncode != 0:
        return (slug, False, r.stderr.decode()[-200:])
    return (slug, True, "")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--workers", type=int, default=WORKERS)
    args = parser.parse_args()

    # 작업 수집
    jobs = []
    already = 0
    no_source = 0

    for slug_dir in sorted(INPUT_DIR.iterdir()):
        if not slug_dir.is_dir():
            continue
        slug = slug_dir.name
        src = slug_dir / "video.mp4"
        dst = OUTPUT_DIR / slug / "video.mp4"

        if not src.exists():
            no_source += 1
            continue
        if dst.exists() and dst.stat().st_size > 0:
            already += 1
            continue
        jobs.append((slug, str(src), str(dst)))

    total = already + len(jobs) + no_source
    print(f"=== 비디오 변환 (흰배경→검정) ===")
    print(f"총: {total}개")
    print(f"이미 완료: {already}개")
    print(f"변환 필요: {len(jobs)}개")
    print(f"소스 없음: {no_source}개")

    if args.dry_run:
        print("\n--- 드라이런 모드 ---")
        total_mb = sum(Path(j[1]).stat().st_size for j in jobs) / (1024 * 1024)
        print(f"입력 용량: {total_mb:.0f} MB")
        est_min = len(jobs) * 30 / args.workers / 60
        print(f"예상 시간: ~{est_min:.0f}분 ({args.workers} workers)")
        return

    if not jobs:
        print("\n모두 완료!")
        return

    # 변환
    log_path = OUTPUT_DIR.parent / "convert_upload_videos.log"
    log = open(log_path, "w")
    ok = fail = 0
    start = time.time()

    print(f"\n변환 시작 ({args.workers} workers)...\n")
    with ProcessPoolExecutor(max_workers=args.workers) as executor:
        futures = {executor.submit(convert_video, j): j[0] for j in jobs}
        for future in as_completed(futures):
            slug, success, err = future.result()
            if success:
                ok += 1
            else:
                fail += 1
                log.write(f"FAIL: {slug} — {err}\n")
            done = ok + fail
            if done % 10 == 0 or done == len(jobs):
                elapsed = time.time() - start
                rate = done / elapsed if elapsed > 0 else 0
                eta = (len(jobs) - done) / rate if rate > 0 else 0
                sys.stdout.write(
                    f"\r[{done}/{len(jobs)}] OK={ok} FAIL={fail} "
                    f"({rate:.1f}/s, ETA {eta / 60:.0f}m)"
                )
                sys.stdout.flush()

    log.close()
    elapsed = time.time() - start

    # 최종
    final_videos = sum(
        1 for d in OUTPUT_DIR.iterdir()
        if d.is_dir() and (d / "video.mp4").exists()
    )
    final_thumbs = sum(
        1 for d in OUTPUT_DIR.iterdir()
        if d.is_dir() and (d / "thumb.jpg").exists()
    )

    print(f"\n\n=== 완료 ({elapsed / 60:.0f}분) ===")
    print(f"성공: {ok}, 실패: {fail}")
    print(f"\n빌드 폴더 최종:")
    print(f"  thumb.jpg: {final_thumbs}개")
    print(f"  video.mp4: {final_videos}개")
    print(f"  경로: {OUTPUT_DIR}/")


if __name__ == "__main__":
    main()
