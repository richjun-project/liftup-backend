#!/usr/bin/env python3
"""매니페스트에서 누락된 27개 썸네일을 수동 매핑으로 변환"""

import subprocess
import sys
from pathlib import Path
from concurrent.futures import ProcessPoolExecutor, as_completed

BASE_DIR = Path("/Users/gimjunhyeong/Develop/liftupai")
OUTPUT_DIR = BASE_DIR / "build/exercise-media-black/exercises"

FILTER = (
    "geq="
    "r='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,r(X,Y))':"
    "g='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,g(X,Y))':"
    "b='if(gt(r(X,Y)+g(X,Y)+b(X,Y),680),0,b(X,Y))'"
)

# slug → 소스 이미지 (수동 매핑)
MANUAL_MAP = {
    "45-degree-hyperextension-arms-to-chest":
        "ILLUSTRATIONS/Back/45 degree hyperextension (arms in front of chest)_female1.jpg",
    "back-extension-machine":
        "ILLUSTRATIONS/Back/lying back extension 1.jpg",
    "barbell-pullover-to-triceps-extension":
        "ILLUSTRATIONS/Chest/Barbell pullover to press1.jpg",
    "bench-decline-ab-sit-ups":
        "ILLUSTRATIONS/Abdominals/bench decline ab crunch1.jpeg",
    "cable-pulldown-pro-lat-bar":
        "ILLUSTRATIONS/Back/Cable Pulldown1.jpg",
    "cable-reverse-fly":
        "ILLUSTRATIONS/Shoulders/cable reverse fly on crossover1.jpg",
    "cable-seated-row-with-v-bar":
        "ILLUSTRATIONS/Back/seated cable row V bar machine 1.jpg",
    "chest-dip-on-dip-station":
        "ILLUSTRATIONS/Chest/Chest Dip (on dip pull-up cage)1.jpg",
    "decline-bench-oblique-crunches":
        "ILLUSTRATIONS/Abdominals/oblique crunch1.jpeg",
    "decline-bench-oblique-crunches-bodyweight":
        "ILLUSTRATIONS/Abdominals/oblique crunch1.jpeg",
    "decline-bench-oblique-crunches-dumbbells":
        "ILLUSTRATIONS/Abdominals/Alternate Oblique Crunch_female1.jpeg",
    "dips-between-chairs":
        "ILLUSTRATIONS/Triceps/Dips between Chair1.jpeg",
    "dumbbell-curls-alternating":
        "ILLUSTRATIONS/Biceps/Dumbbell Incline Alternate Curl1.jpeg",
    "dumbbell-plyo-squat":
        "ILLUSTRATIONS/Legs/Dumbbell Jumping Squat1.jpg",
    "dumbbell-single-arm-leaning-lateral-raise":
        "ILLUSTRATIONS/Shoulders/Leaning Dumbbell lateral raise1.jpg",
    "dumbbell-single-arm-shoulder-press":
        "ILLUSTRATIONS/Shoulders/seated dumbbell one arm shoulder press1.jpg",
    "dumbbells-plank-to-alternating-row":
        "ILLUSTRATIONS/Back/Dumbbell Renegade Row1.jpg",
    "dumbbells-triceps-kick-back-both-arms":
        "ILLUSTRATIONS/Triceps/dumbells triceps kick back both arms1.jpeg",
    "front-raises-dumbbell-seated":
        "ILLUSTRATIONS/Shoulders/Dumbbell Seated Front Raise1.jpg",
    "good-mornings-barbell":
        "ILLUSTRATIONS/Back/Barbell Good Morning1.jpg",
    "pec-deck-fly-machine":
        "ILLUSTRATIONS/Chest/peck deck fly machine1.jpg",
    "plank-on-elbows":
        "ILLUSTRATIONS/Abdominals/Elbow-Up and Down Dynamic Plank.jpeg",
    "plate-loaded-chest-press-incline":
        "ILLUSTRATIONS/Chest/machine chest press incline1.jpg",
    "pull-up-wide-grip-front-view":
        "ILLUSTRATIONS/Back/pull up wide grip 1.jpg",
    "reverse-cable-fly-on-crossover":
        "ILLUSTRATIONS/Shoulders/cable reverse fly on crossover1.jpg",
    "single-arm-standing-low-cable-row":
        "ILLUSTRATIONS/Back/single arm cable row left 1.jpg",
    # 매칭 없음 — 가장 유사한 것으로 대체
    "barbell-deadlift-360-degrees":
        "ILLUSTRATIONS/Back/Barbell Reverse Deadlift1.jpg",
}


def convert_image(args: tuple) -> tuple[str, bool, str]:
    slug, src, dst = args
    Path(dst).parent.mkdir(parents=True, exist_ok=True)
    r = subprocess.run(
        ["ffmpeg", "-y", "-i", src, "-vf", FILTER,
         "-update", "1", "-frames:v", "1", dst],
        capture_output=True, timeout=60,
    )
    if r.returncode != 0:
        return (slug, False, r.stderr.decode()[-200:])
    return (slug, True, "")


def main():
    jobs = []
    skip = 0

    for slug, rel_path in MANUAL_MAP.items():
        src = BASE_DIR / rel_path
        dst = OUTPUT_DIR / slug / "thumb.jpg"

        if dst.exists() and dst.stat().st_size > 0:
            skip += 1
            continue

        if not src.exists():
            print(f"소스 없음: {slug} → {src}")
            continue

        jobs.append((slug, str(src), str(dst)))

    print(f"총 {len(MANUAL_MAP)}개 매핑")
    print(f"이미 완료: {skip}개")
    print(f"변환 필요: {len(jobs)}개\n")

    if not jobs:
        print("모두 완료!")
        return

    ok = fail = 0
    with ProcessPoolExecutor(max_workers=4) as executor:
        futures = {executor.submit(convert_image, job): job[0] for job in jobs}
        for future in as_completed(futures):
            slug, success, err = future.result()
            if success:
                ok += 1
                print(f"  OK: {slug}")
            else:
                fail += 1
                print(f"  FAIL: {slug} — {err}")

    print(f"\n=== 완료 ===")
    print(f"성공: {ok}, 실패: {fail}")

    # 최종 카운트
    total = sum(1 for d in OUTPUT_DIR.iterdir()
                if d.is_dir() and (d / "thumb.jpg").exists())
    print(f"전체 썸네일: {total}개")


if __name__ == "__main__":
    main()
