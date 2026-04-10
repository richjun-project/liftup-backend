#!/usr/bin/env python3
"""
변환된 검정 배경 이미지를 S3에 업로드

사용법:
  # 드라이런 (업로드 안 함, 목록만 확인)
  python3 scripts/upload_black_thumbs_to_s3.py --dry-run

  # 실제 업로드
  python3 scripts/upload_black_thumbs_to_s3.py

  # 비디오도 함께 업로드
  python3 scripts/upload_black_thumbs_to_s3.py --include-videos
"""

import argparse
import os
import sys
import time
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

import boto3
from botocore.config import Config

# ─── 설정 ───────────────────────────────────────────
BASE_DIR = Path("/Users/gimjunhyeong/Develop/liftupai")
BUILD_DIR = BASE_DIR / "build/exercise-media-black/exercises"

BUCKET = "dearglobe"
S3_PREFIX = "liftupai/exercises"
REGION = "ap-northeast-2"

AWS_ACCESS_KEY = os.environ.get("AWS_ACCESS_KEY_ID", "")
AWS_SECRET_KEY = os.environ.get("AWS_SECRET_ACCESS_KEY", "")

WORKERS = 10
CONTENT_TYPES = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".gif": "image/gif",
    ".mp4": "video/mp4",
}


def create_s3_client():
    return boto3.client(
        "s3",
        region_name=REGION,
        aws_access_key_id=AWS_ACCESS_KEY,
        aws_secret_access_key=AWS_SECRET_KEY,
        config=Config(max_pool_connections=WORKERS + 5),
    )


def collect_files(include_videos: bool) -> list[tuple[Path, str]]:
    """(로컬 경로, S3 키) 목록 수집"""
    files = []
    if not BUILD_DIR.exists():
        print(f"오류: {BUILD_DIR} 없음")
        sys.exit(1)

    for slug_dir in sorted(BUILD_DIR.iterdir()):
        if not slug_dir.is_dir():
            continue
        slug = slug_dir.name

        thumb = slug_dir / "thumb.jpg"
        if thumb.exists():
            s3_key = f"{S3_PREFIX}/{slug}/thumb.jpg"
            files.append((thumb, s3_key))

        if include_videos:
            video = slug_dir / "video.mp4"
            if video.exists():
                s3_key = f"{S3_PREFIX}/{slug}/video.mp4"
                files.append((video, s3_key))

    return files


def upload_file(s3_client, local_path: Path, s3_key: str) -> tuple[str, bool, str]:
    """파일 하나 업로드. (s3_key, 성공 여부, 에러 메시지) 반환"""
    ext = local_path.suffix.lower()
    content_type = CONTENT_TYPES.get(ext, "application/octet-stream")
    try:
        s3_client.put_object(
            Bucket=BUCKET,
            Key=s3_key,
            Body=local_path.read_bytes(),
            ContentType=content_type,
            CacheControl="public, max-age=31536000",
        )
        return (s3_key, True, "")
    except Exception as e:
        return (s3_key, False, str(e))


def main():
    parser = argparse.ArgumentParser(description="검정 배경 이미지 S3 업로드")
    parser.add_argument("--dry-run", action="store_true", help="업로드 안 하고 목록만 출력")
    parser.add_argument("--include-videos", action="store_true", help="비디오도 함께 업로드")
    parser.add_argument("--workers", type=int, default=WORKERS, help="병렬 업로드 스레드 수")
    args = parser.parse_args()

    files = collect_files(args.include_videos)
    thumbs = [f for f in files if f[0].name == "thumb.jpg"]
    videos = [f for f in files if f[0].name == "video.mp4"]

    print(f"=== S3 업로드 준비 ===")
    print(f"버킷: {BUCKET}")
    print(f"프리픽스: {S3_PREFIX}/")
    print(f"썸네일: {len(thumbs)}개")
    if args.include_videos:
        print(f"비디오: {len(videos)}개")
    print(f"총: {len(files)}개")
    print()

    if args.dry_run:
        print("--- 드라이런 모드 (업로드 안 함) ---")
        for local, key in files[:20]:
            size_kb = local.stat().st_size / 1024
            print(f"  {key} ({size_kb:.0f} KB)")
        if len(files) > 20:
            print(f"  ... 외 {len(files) - 20}개")
        total_mb = sum(f[0].stat().st_size for f in files) / (1024 * 1024)
        print(f"\n총 용량: {total_mb:.1f} MB")
        return

    # 업로드 시작
    s3 = create_s3_client()
    ok = fail = 0
    failed_keys = []
    start = time.time()

    print(f"업로드 시작 ({args.workers} threads)...")
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {
            executor.submit(upload_file, s3, local, key): key
            for local, key in files
        }
        for future in as_completed(futures):
            key, success, err = future.result()
            if success:
                ok += 1
            else:
                fail += 1
                failed_keys.append((key, err))
            total_done = ok + fail
            if total_done % 50 == 0 or total_done == len(files):
                elapsed = time.time() - start
                rate = total_done / elapsed if elapsed > 0 else 0
                sys.stdout.write(
                    f"\r[{total_done}/{len(files)}] OK={ok} FAIL={fail} ({rate:.1f}/s)"
                )
                sys.stdout.flush()

    elapsed = time.time() - start
    print(f"\n\n=== 업로드 완료 ({elapsed:.1f}초) ===")
    print(f"성공: {ok}개")
    print(f"실패: {fail}개")

    if failed_keys:
        print(f"\n실패 목록:")
        for key, err in failed_keys[:20]:
            print(f"  {key}: {err}")
        if len(failed_keys) > 20:
            print(f"  ... 외 {len(failed_keys) - 20}개")


if __name__ == "__main__":
    main()
