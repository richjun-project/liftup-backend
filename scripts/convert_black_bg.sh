#!/bin/bash
# 흰색 배경을 검정으로 변환하여 S3 업로드용 구조로 출력
# 출력: build/exercise-media-black/exercises/{slug}/thumb.jpg, video.mp4

set -e

BASE_DIR="/Users/gimjunhyeong/develop/liftupai"
MANIFEST="$BASE_DIR/build/exercise-media/exercise-media-manifest.csv"
OUTPUT_DIR="$BASE_DIR/build/exercise-media-black"
LOG_FILE="$OUTPUT_DIR/convert.log"

# 흰색(RGB합 680+) → 검정 변환 필터
FILTER="geq=r='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,r(X,Y))':g='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,g(X,Y))':b='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,b(X,Y))'"

mkdir -p "$OUTPUT_DIR"
echo "$(date): Starting conversion" > "$LOG_FILE"

# 소스 파일 찾기 (exact → 1 suffix → 확장자 변형)
find_source() {
  local path="$1"

  # 1) 정확한 경로
  [ -f "$BASE_DIR/$path" ] && echo "$BASE_DIR/$path" && return 0

  # 2) 확장자 앞에 1 추가: name_female.jpg → name_female1.jpg
  local dir=$(dirname "$path")
  local base=$(basename "$path")
  local ext="${base##*.}"
  local name="${base%.*}"
  local with1="$dir/${name}1.${ext}"
  [ -f "$BASE_DIR/$with1" ] && echo "$BASE_DIR/$with1" && return 0

  # 3) 확장자 변형 (jpg↔jpeg↔png)
  for alt_ext in jpg jpeg png; do
    local alt="$dir/${name}.${alt_ext}"
    [ -f "$BASE_DIR/$alt" ] && echo "$BASE_DIR/$alt" && return 0
    local alt1="$dir/${name}1.${alt_ext}"
    [ -f "$BASE_DIR/$alt1" ] && echo "$BASE_DIR/$alt1" && return 0
  done

  return 1
}

thumb_ok=0; thumb_fail=0; thumb_skip=0
video_ok=0; video_fail=0; video_skip=0
total=0

tail -n +2 "$MANIFEST" | while IFS=',' read -r slug display_name category thumb_status thumb_score thumb_source video_status video_score video_source target_prefix target_thumb_key target_video_key; do
  total=$((total + 1))
  mkdir -p "$OUTPUT_DIR/$target_prefix"

  # === 썸네일 ===
  if [ "$thumb_status" = "missing" ] || [ -z "$thumb_source" ]; then
    thumb_skip=$((thumb_skip + 1))
  else
    src=$(find_source "$thumb_source")
    dst="$OUTPUT_DIR/$target_thumb_key"
    if [ -n "$src" ]; then
      if ffmpeg -y -i "$src" -vf "$FILTER" -update 1 -frames:v 1 "$dst" 2>/dev/null; then
        thumb_ok=$((thumb_ok + 1))
      else
        thumb_fail=$((thumb_fail + 1))
        echo "THUMB FAIL: $slug ($src)" >> "$LOG_FILE"
      fi
    else
      thumb_skip=$((thumb_skip + 1))
      echo "THUMB NOT FOUND: $slug ($thumb_source)" >> "$LOG_FILE"
    fi
  fi

  # === 비디오 ===
  if [ "$video_status" = "missing" ] || [ -z "$video_source" ]; then
    video_skip=$((video_skip + 1))
  else
    src=$(find_source "$video_source")
    dst="$OUTPUT_DIR/$target_video_key"
    if [ -n "$src" ]; then
      if ffmpeg -y -i "$src" -vf "$FILTER" -c:a copy -preset fast "$dst" 2>/dev/null; then
        video_ok=$((video_ok + 1))
      else
        video_fail=$((video_fail + 1))
        echo "VIDEO FAIL: $slug ($src)" >> "$LOG_FILE"
      fi
    else
      video_skip=$((video_skip + 1))
      echo "VIDEO NOT FOUND: $slug ($video_source)" >> "$LOG_FILE"
    fi
  fi

  # 진행 상황 (25개마다)
  if [ $((total % 25)) -eq 0 ]; then
    echo "$(date): [$total/1318] Thumb=$thumb_ok Video=$video_ok" | tee -a "$LOG_FILE"
  fi
done

echo ""
echo "=== 변환 완료 ==="
echo "Thumb: OK=$thumb_ok FAIL=$thumb_fail SKIP=$thumb_skip"
echo "Video: OK=$video_ok FAIL=$video_fail SKIP=$video_skip"
echo "출력: $OUTPUT_DIR/exercises/"
echo "로그: $LOG_FILE"
