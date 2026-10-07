# -*- coding: utf-8 -*-
"""
운동 카탈로그 대정비 (엔터프라이즈 검색·추천 1.1.2).

기준 커밋(bcde543)의 exercise-catalog.json 을 읽어
  1) 중복 운동 병합 (duplicate-map.json 생성)
  2) 메타데이터·이름 수정, 근육 태그 정리(주동근 먼저, ESSENTIAL 3개 이하)
  3) 인기도를 신호로 재산정(티어 안에서 값이 한 군데 몰리지 않게)
  4) 대표 기구·유산소·모빌리티 신규 운동 추가 (new_exercises_*.py)
  5) exercise-media.json (CDN HEAD 결과 기반) 생성
을 수행해 src/main/resources/catalog/ 아래 파일을 다시 쓴다. 항상 기준 커밋에서 시작하므로 여러 번 실행해도 같은 결과다.

usage: python3 scripts/catalog/overhaul_catalog.py [--base bcde543] [--cdn-head <cdn-head.json>]
"""
import argparse
import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
ROOT = HERE.parent.parent
CAT_DIR = ROOT / "src/main/resources/catalog"
MIGRATION = ROOT / "src/main/resources/db/migration/catalog/V20261008100__catalog_dedup_round4.sql"

import new_exercises_legs, new_exercises_upper, new_exercises_mobility, new_exercises_cardio  # noqa: E402

NEW_ENTRIES = (new_exercises_legs.ENTRIES + new_exercises_upper.ENTRIES
               + new_exercises_mobility.ENTRIES + new_exercises_cardio.ENTRIES)

# ── 1) 중복: 사라지는 slug -> 남는 slug (남는 slug 는 반드시 운영 DB 에 이미 있는 기존 운동이어야 한다) ──────────────
DUPLICATES = {
    # 정규화하면 이름이 같은 쌍 (카탈로그 린트의 이름 충돌)
    "lat-pull-down-normal-grip": "cable-pulldown",
    "dumbbell-fly-incline-bench": "dumbbell-incline-fly",
    "dumbbell-incline-biceps-simultaneous-curl": "incline-dumbbell-curl",
    "dumbbell-incline-simultaneous-curl": "incline-dumbbell-curl",
    "wrist-extension-dumbbell": "dumbbell-reverse-wrist-curl",
    # 같은 동작의 표기 변형
    "alternating-superman": "alternate-superman",
    "standing-arnold-press-dumbbell": "arnold-press-dumbbell",
    "barbell-standing-wide-grip-biceps-curls": "barbell-wide-grip-biceps-curl",
    "barbell-bicep-curl-wide-grip": "barbell-wide-grip-biceps-curl",
    "barbell-standing-shoulder-press": "barbell-standing-military-press",
    "barbell-seated-military-press": "seated-shoulder-press-barbell",
    "bodyweight-calf-raises": "bodyweight-standing-calf-raise",
    "military-press-bodyweight": "bodyweight-standing-military-press",
    "dumbbell-seated-alternate-shoulder-press": "dumbbell-alternate-shoulder-press",
    "standing-dumbbell-alternate-shoulder-press": "dumbbell-alternate-shoulder-press",
    "front-raises-dumbbell-seated": "dumbbell-seated-front-raise",
    "standing-front-raise-dumbbell": "dumbbell-front-raise",
    "dumbbell-single-arm-floor-press": "dumbbell-one-arm-floor-press",
    "dumbbell-seated-preacher-curl": "dumbbell-preacher-curl",
    "dumbbell-standing-simultaneous-biceps-curl": "dumbbell-simultaneous-biceps-curl",
    "dumbbell-standing-wrist-curl": "wrist-curl-dumbbell",
    "seated-resistance-band-cable-rows": "resistance-band-cable-row",
    "seated-v-up": "v-up",
    "45-degree-bicycle-twisting-crunch": "45-degree-bicycle-twisting",
    "alternate-arm-leg-plank-hold": "alternate-arm-leg-plank",
    "bent-over-barbell-row": "barbell-bent-over-row-pronated-grip",
    "barbell-reverse-grip-bent-over-row": "barbell-bent-over-row-supinated-grip",
    "barbell-underhand-bent-over-row": "barbell-bent-over-row-supinated-grip",
    "dumbbells-bent-over-row-pronated-grip": "dumbbell-bent-over-row",
    "dumbbell-chest-press-incline-bench": "dumbbell-incline-bench-press",
    "dumbbell-chest-press-flat": "dumbbell-bench-press",
    "dumbbell-chest-press-decline": "dumbbell-decline-bench-press",
    "dumbbell-fly-flat-bench-slow": "dumbbell-fly-flat-bench",
    "machine-fly": "pec-deck-fly-machine",
    "pull-apart-cable-resistance-band": "resistance-band-pull-apart",
    "seated-cable-row-v-bar-machine": "cable-seated-row-with-v-bar",
    "decline-bench-oblique-crunches": "decline-bench-oblique-crunches-dumbbells",
    "dumbbell-incline-alternate-curl": "dumbbell-incline-alternate-bicep-curl",
    "alternate-bicep-curl-standing-dumbbells": "dumbbell-alternate-biceps-curl",
    "dumbbell-alternate-seated-hammer-curl": "alternate-hammer-curl-seated-dumbbells",
    "bent-over-row-resistance-band": "band-bent-over-row",
    "barbell-narrow-stance-full-squat": "barbell-narrow-stance-squat",
    "dumbbell-bulgarian-box-split-squat": "dumbbell-bulgarian-split-squat",
    "single-leg-romanian-deadlift-dumbbell": "dumbbell-single-leg-deadlift",
    "dumbbell-incline-lying-front-raise": "dumbbell-incline-front-raise",
    "cable-biceps-curl-ez-bar": "biceps-curl-cable",
    "shoulder-abduction": "lateral-raise-bodyweight",
    "ez-bar-cable-curl-wide-grip-crossover-machine": "ez-bar-cable-curl-wide-grip",
    "lateral-raises-on-cable-machine": "cable-lateral-raises",
}
# 브랜드 기구의 한쪽/교대 모드 변형은 양팔(양다리) 기본형으로 합친다 (아래에서 자동 탐색)
MODE_SUFFIXES = ["-alternate-arm", "-alternate-arms", "-single-arm", "-alternate-legs", "-single-leg"]
BOTH_SUFFIXES = ["-both-arm", "-both-arms", "-both-leg", "-both-legs", ""]

# ── 2) 메타데이터 수정 (slug -> 필드) ────────────────────────────────────────────────────────
META = {
    # 삼두 킥백은 팔 운동이다 (둔근 킥백 패턴으로 분류돼 하체 날에 추천되던 오류)
    "dumbbell-kickback": dict(category="ARMS", movementPattern="TRICEP_PUSHDOWN", muscleGroups=["TRICEPS", "SHOULDERS"]),
    "dumbbell-one-arm-kickback": dict(category="ARMS", movementPattern="TRICEP_PUSHDOWN", muscleGroups=["TRICEPS", "SHOULDERS"]),
    "dumbbell-seated-kickback": dict(category="ARMS", movementPattern="TRICEP_PUSHDOWN", muscleGroups=["TRICEPS", "SHOULDERS"]),
    "dumbbell-standing-both-arms-kickback": dict(category="ARMS", movementPattern="TRICEP_PUSHDOWN", muscleGroups=["TRICEPS", "SHOULDERS"]),
    "cable-neutral-grip-kickback": dict(category="ARMS", movementPattern="TRICEP_PUSHDOWN", muscleGroups=["TRICEPS", "SHOULDERS"]),
    "tricep-cable-kickback-on-crossover-machine": dict(category="ARMS", movementPattern="TRICEP_PUSHDOWN", muscleGroups=["TRICEPS", "SHOULDERS"]),
    "bench-triceps-dip-leg-curls": dict(category="ARMS", movementPattern="DIPS", muscleGroups=["TRICEPS", "CHEST", "SHOULDERS"]),
    "arnold-press-dumbbell": dict(category="SHOULDERS", movementPattern="VERTICAL_PRESS_DUMBBELL", muscleGroups=["SHOULDERS", "TRICEPS"],
                                  recommendationTier="STANDARD", popularity=75),
    "dumbbell-arnold-press-seated": dict(category="SHOULDERS", movementPattern="VERTICAL_PRESS_DUMBBELL", muscleGroups=["SHOULDERS", "TRICEPS"],
                                         recommendationTier="STANDARD", popularity=66),
    "dumbbell-standing-curl-arnold-press": dict(category="SHOULDERS", movementPattern="VERTICAL_PRESS_DUMBBELL"),
    "reverse-machine-fly": dict(category="SHOULDERS", movementPattern="REAR_DELT", muscleGroups=["SHOULDERS", "TRAPS"],
                                recommendationTier="STANDARD", popularity=72),
    "seated-hip-abductor-machine": dict(category="LEGS", movementPattern="OTHER", muscleGroups=["GLUTES", "LEGS"],
                                        recommendationTier="STANDARD", popularity=72, difficulty=5),
    "resistance-band-lying-abduction": dict(category="LEGS", muscleGroups=["GLUTES", "LEGS"]),
    "resistance-band-lying-hyperextension-abduction": dict(category="LEGS", muscleGroups=["GLUTES", "HAMSTRINGS"]),
    "resistance-band-standing-balance-hip-abduction": dict(category="LEGS", muscleGroups=["GLUTES", "LEGS"]),
    "adductor-stretch": dict(category="LEGS"),
    "standing-adductor-stretch": dict(category="LEGS"),
    "triceps-light-stretch": dict(category="ARMS"),

    "adductor-stretch-side-standing": dict(category="LEGS"),
    "adductor-dynamic-stretch": dict(category="LEGS"),
    "overhand-tricep-stretching-single-arm": dict(category="ARMS"),
    "biceps-curl-to-shoulder-press-resistance-band": dict(category="ARMS"),
    "dumbbell-bicep-curl-to-shoulder-press": dict(category="ARMS"),
    # 유산소 기구는 CARDIO 카테고리로
    "stationary-exercise-bike": dict(category="CARDIO", equipment="MACHINE", movementPattern="CARDIO",
                                     muscleGroups=["QUADRICEPS", "GLUTES", "LEGS"], recommendationTier="STANDARD", popularity=85, difficulty=10),
    "gym-rowing-machine-normal-speed": dict(category="CARDIO", equipment="MACHINE", movementPattern="CARDIO",
                                            muscleGroups=["LATS", "QUADRICEPS", "BACK"], recommendationTier="STANDARD", popularity=78, difficulty=40),
    "high-knees": dict(category="CARDIO", movementPattern="CARDIO", recommendationTier="STANDARD", popularity=60),
    "butt-kicks": dict(category="CARDIO", movementPattern="CARDIO", recommendationTier="STANDARD", popularity=54),
    "skipping": dict(category="CARDIO", movementPattern="CARDIO", recommendationTier="STANDARD", popularity=50),
    "fast-feet": dict(category="CARDIO", movementPattern="CARDIO", recommendationTier="STANDARD", popularity=48),
    "briskly-walking": dict(recommendationTier="STANDARD", popularity=70, difficulty=5),
    "dumbbell-farmer-walks": dict(recommendationTier="STANDARD", popularity=60),
    "jump-rope-basic-jump": dict(recommendationTier="STANDARD", popularity=66),
    "jump-rope-alternating-foot": dict(recommendationTier="STANDARD", popularity=58),
    "jump-rope-double-bounce": dict(recommendationTier="STANDARD", popularity=50),
    # 장비 오분류
    "barbell-bench-lateral-step-up": dict(equipment="BARBELL"),
    "45-degree-bicycle-twisting": dict(equipment="BODYWEIGHT"),
    "standing-gastrocnemius-stretch": dict(equipment="BODYWEIGHT"),
    "standing-leg-elevated-hamstring-stretch": dict(equipment="BODYWEIGHT"),
    "band-incline-t-raise": dict(equipment="RESISTANCE_BAND", movementPattern="REAR_DELT"),
    "ez-bar-cable-curl-wide-grip": dict(equipment="CABLE", movementPattern="BICEP_CURL_CABLE"),
    "dumbbell-single-arm-overhead-lunge": dict(equipment="DUMBBELL"),
    "floor-cable-resistance-band-press": dict(equipment="RESISTANCE_BAND"),
    "kettlebell-swing": dict(category="LEGS", movementPattern="HIP_HINGE", muscleGroups=["GLUTES", "HAMSTRINGS", "CORE"],
                             recommendationTier="STANDARD", popularity=80, difficulty=45, isPlanEligible=True),
    "chest-dip-machine": dict(recommendationTier="STANDARD", popularity=72),
    # ESSENTIAL 슬롯 정리: 밴드·브랜드 기구는 STANDARD 로, 기본 종목을 승격
    "band-close-grip-row": dict(recommendationTier="STANDARD", popularity=58),
    "chest-press-front-resistance-band": dict(recommendationTier="STANDARD", popularity=60),
    "resistance-band-squat": dict(recommendationTier="STANDARD", popularity=58),
    "barbell-rollout": dict(recommendationTier="STANDARD", popularity=66),
    "barbell-lying-triceps-skull-crusher": dict(recommendationTier="STANDARD", popularity=80),
    "iso-lateral-shoulder-press-both-arms": dict(recommendationTier="STANDARD", popularity=74),
    "barbell-front-squats": dict(recommendationTier="STANDARD", popularity=84),
    "cable-bar-lateral-pulldown": dict(recommendationTier="STANDARD", popularity=76),
    "barbell-shrugs": dict(recommendationTier="STANDARD", popularity=78),
    "dumbbell-front-raise": dict(recommendationTier="STANDARD", popularity=74),
    "barbell-incline-bench-press": dict(popularity=94),
    "bodyweight-squat": dict(recommendationTier="ESSENTIAL", popularity=92),
    "chin-up": dict(recommendationTier="ESSENTIAL", popularity=84),
    "dumbbell-romanian-deadlift": dict(recommendationTier="ESSENTIAL", popularity=88),
    # 풀다운 변형은 패턴을 바로잡고 대표 순으로
    "lat-pull-down-wide-grip": dict(movementPattern="LAT_PULLDOWN", recommendationTier="STANDARD", popularity=82),
    "reverse-grip-lat-pull-down": dict(movementPattern="LAT_PULLDOWN", recommendationTier="STANDARD", popularity=64),
    "lat-pull-down-close-grip": dict(movementPattern="LAT_PULLDOWN", recommendationTier="STANDARD", popularity=60),
    "v-bar-lat-pull-down": dict(movementPattern="LAT_PULLDOWN", recommendationTier="STANDARD", popularity=66),
    "behind-neck-lat-pull-down-machine": dict(movementPattern="LAT_PULLDOWN", popularity=30),
    # 스미스 머신 프레스/로우의 패턴
    "smith-machine-press-flat": dict(movementPattern="HORIZONTAL_PRESS_BARBELL", recommendationTier="STANDARD", popularity=62),
    "smith-machine-press-incline": dict(movementPattern="INCLINE_PRESS_BARBELL", recommendationTier="STANDARD", popularity=60),
    "smith-machine-press-decline": dict(movementPattern="DECLINE_PRESS", popularity=34),
    "barbell-smith-chest-press": dict(movementPattern="HORIZONTAL_PRESS_BARBELL"),
    "inverted-rows-on-smith-machine": dict(movementPattern="INVERTED_ROW"),
    # 한 번 더 플랜 후보 풀을 넓힌다: 홈트 하체(맨몸)
    "side-lunges-bodyweight": dict(recommendationTier="STANDARD", isPlanEligible=True),
    "cossack-squat": dict(isPlanEligible=True),
    "jump-squats-bodyweight": dict(isPlanEligible=True),
    "step-up-on-chair-bodyweight": dict(recommendationTier="STANDARD", movementPattern="LUNGE", isPlanEligible=True),
    "donkey-kicks-bodyweight": dict(recommendationTier="STANDARD", isPlanEligible=True),
    "fire-hydrant-bodyweight": dict(recommendationTier="STANDARD", isPlanEligible=True),
    "curtsy-lunge-bodyweight": dict(recommendationTier="STANDARD", isPlanEligible=True),
    "bodyweight-pulse-squat": dict(isPlanEligible=False),
    "goblet-kettlebell-squats": dict(recommendationTier="STANDARD", popularity=72, isPlanEligible=True),
    "single-kettlebell-deadlift": dict(movementPattern="DEADLIFT", isPlanEligible=True),
}

# ── 3) 이름 수정 (slug -> locale -> 이름) ──────────────────────────────────────────────────────
NAMES = {
    "barbell-full-squat": dict(ko="바벨 백 스쿼트", ja="バーベル・バックスクワット"),
    "cable-pulldown": dict(ko="랫 풀다운 (케이블)", ja="ラットプルダウン（ケーブル）"),
    "cable-bar-lateral-pulldown": dict(ko="랫 풀다운 (바)", ja="ラットプルダウン（バー）"),
    "lying-leg-curl-machine": dict(ko="라잉 레그 컬 머신"),
    "leg-press-machine-normal-stance": dict(ko="레그 프레스 머신", ja="レッグプレス（マシン）"),
    "pull-up-normal-grip": dict(ko="풀업 (턱걸이)", ja="懸垂（プルアップ）"),
    "plank-on-elbows": dict(ko="엘보 플랭크"),
    "cable-low-seated-row": dict(ko="시티드 케이블 로우 (로우 풀리)"),
    "dumbbell-incline-twisted-fly": dict(ko="덤벨 인클라인 트위스티드 플라이"),
    "inverted-rows-on-smith-machine": dict(ko="스미스 머신 인버티드 로우"),
    "leg-raises-straight-legs": dict(ja="レッグレイズ（ストレートレッグ）"),
    "dumbbell-bicep-curl-to-shoulder-press": dict(ja="ダンベル・バイセップスカール→ショルダープレス"),
    "lateral-raise-to-shoulder-press-resistance-band-cable": dict(ja="バンド・サイドレイズ→ショルダープレス"),
    "dumbbell-half-biceps-curl-for-21s": dict(ja="ダンベル・ハーフ・バイセップスカール（21レップ法）"),
    "barbell-sumo-deadlift": dict(ja="バーベル・スモーデッドリフト"),
    "stationary-exercise-bike": dict(en="Stationary bike", ko="실내 자전거", ja="エアロバイク"),
    "gym-rowing-machine-normal-speed": dict(en="Rowing machine", ko="로잉 머신", ja="ローイングマシン"),
    "iso-lateral-shoulder-press-both-arms": dict(ko="숄더 프레스 머신 (아이소 래터럴)"),
    "lat-pull-down-wide-grip": dict(ko="와이드 그립 랫 풀다운"),
    "lat-pull-down-close-grip": dict(ko="클로즈 그립 랫 풀다운"),
    "reverse-grip-lat-pull-down": dict(ko="리버스 그립 랫 풀다운"),
    "briskly-walking": dict(ko="빠르게 걷기 (파워워킹)"),
    "resistance-band-shoulder-adduction": dict(en="Resistance band side arm pulldown", ko="저항 밴드 사이드 암 풀다운", ja="レジスタンスバンド・サイドアームプルダウン"),
    "seated-hip-abductor-machine": dict(en="Seated hip abductor machine", ko="힙 어브덕션 머신", ja="ヒップアブダクション（マシン）"),
    "bodyweight-standing-calf-raise": dict(ko="맨몸 카프 레이즈"),
    "pec-deck-fly-machine": dict(ko="펙 덱 플라이 머신"),
    # ko 의 "머신" 접두는 한국 헬스장 관행(머신을 맨 뒤)에 맞춘다
    "abdominal-crunches-machine": dict(ko="복근 크런치 머신"),
    "back-extension-machine": dict(ko="백 익스텐션 머신"),
    "behind-neck-lat-pull-down-machine": dict(ko="비하인드 넥 랫 풀다운"),
    "biceps-curl-cable-wide-grip-crossover-machine": dict(ko="케이블 이두 컬 (와이드 그립)"),
    "calf-raise-leg-press-machine": dict(ko="레그 프레스 머신 카프 레이즈"),
    "calf-raise-on-hack-squat-machine": dict(ko="핵 스쿼트 머신 카프 레이즈"),
    "chest-dip-machine": dict(ko="체스트 딥 머신"),
    "hack-squat-machine": dict(ko="핵 스쿼트 머신"),
    "kneeling-leg-curl-machine": dict(ko="니링 레그 컬 머신"),
    "machine-chest-press-decline": dict(ko="디클라인 체스트 프레스 머신"),
    "machine-chest-press-incline": dict(ko="인클라인 체스트 프레스 머신"),
    "machine-lateral-raise": dict(ko="레터럴 레이즈 머신"),
    "seated-calf-machine": dict(ko="시티드 카프 레이즈 머신"),
    "seated-leg-curl-machine": dict(ko="시티드 레그 컬 머신"),
    "single-leg-calf-raise-leg-press-machine": dict(ko="레그 프레스 머신 싱글 레그 카프 레이즈"),
    "single-leg-incline-45-degree-leg-press-machine": dict(ko="싱글 레그 45도 레그 프레스 머신"),
    "standing-calf-raises-smith-machine": dict(ko="스미스 머신 스탠딩 카프 레이즈"),
    "tricep-cable-kickback-on-crossover-machine": dict(ko="케이블 삼두 킥백"),
    "tricep-extension-machine": dict(ko="삼두 익스텐션 머신"),
    "triceps-dip-machine": dict(ko="삼두 딥 머신"),
    "triceps-rope-extension-on-crossover-machine": dict(ko="케이블 삼두 로프 익스텐션"),
}

# ESSENTIAL 인기도: 같은 값이 한꺼번에 몰리지 않도록 대표성 순으로 수동 지정(빅3·기본 풀업푸시업이 최상단)
ESSENTIAL_POPULARITY = {
    "abdominal-crunches": 90, "assisted-pull-up": 88, "barbell-bench-press": 98, "barbell-bent-over-row-pronated-grip": 95,
    "barbell-biceps-curl": 90, "barbell-deadlift": 98, "barbell-full-squat": 98, "barbell-hip-thrust": 92,
    "barbell-incline-bench-press": 94, "barbell-lunge": 88, "barbell-romanian-deadlift": 92, "barbell-standing-military-press": 92,
    "bent-over-rear-delt-fly-dumbbell": 84, "biceps-curl-cable": 88, "cable-overhead-extension-rope": 86, "cable-pulldown": 98,
    "cable-pushdown": 90, "cable-seated-row": 92, "dumbbell-bench-press": 96, "dumbbell-bent-over-row": 90, "dumbbell-curls": 94,
    "dumbbell-fly-flat-bench": 86, "dumbbell-goblet-squat": 92, "dumbbell-hammer-curl": 88, "dumbbell-incline-bench-press": 92,
    "dumbbell-seated-shoulder-press": 96, "dumbbell-standing-overhead-press": 88, "hack-squat-machine": 88, "high-plank": 94,
    "lateral-raises-dumbbell": 94, "leg-press-machine-normal-stance": 98, "lying-leg-curl-machine": 91, "lying-leg-raise": 82,
    "pec-deck-fly-machine": 92, "pull-up-normal-grip": 94, "push-ups-bodyweight": 96, "russian-twist": 80,
    "seated-shoulder-press-barbell": 84, "side-plank": 80,
}

# ── 4) 근육 태그 정리 ──────────────────────────────────────────────────────────────────────────
PATTERN_ORDER = {
    "DEADLIFT": ["HAMSTRINGS", "GLUTES", "BACK", "QUADRICEPS", "TRAPS", "LEGS", "FOREARMS"],
    "HIP_HINGE": ["HAMSTRINGS", "GLUTES", "BACK", "QUADRICEPS", "LEGS"],
    "BARBELL_ROW": ["LATS", "BACK", "BICEPS", "TRAPS", "SHOULDERS", "FOREARMS"],
    "DUMBBELL_ROW": ["LATS", "BACK", "BICEPS", "TRAPS", "SHOULDERS", "FOREARMS"],
    "CABLE_ROW": ["LATS", "BACK", "BICEPS", "TRAPS", "SHOULDERS", "FOREARMS"],
    "INVERTED_ROW": ["LATS", "BACK", "BICEPS", "TRAPS", "SHOULDERS", "FOREARMS"],
    "PULLUP_CHINUP": ["LATS", "BICEPS", "BACK", "TRAPS", "SHOULDERS", "FOREARMS"],
    "LAT_PULLDOWN": ["LATS", "BICEPS", "BACK", "TRAPS", "SHOULDERS", "FOREARMS"],
    "SQUAT": ["QUADRICEPS", "GLUTES", "HAMSTRINGS", "CALVES", "LEGS"],
    "LUNGE": ["QUADRICEPS", "GLUTES", "HAMSTRINGS", "CALVES", "LEGS"],
    "LEG_PRESS": ["QUADRICEPS", "GLUTES", "HAMSTRINGS", "CALVES", "LEGS"],
    "LEG_EXTENSION": ["QUADRICEPS"],
    "LEG_CURL": ["HAMSTRINGS", "CALVES"],
    "CALF": ["CALVES"],
    "GLUTE_FOCUSED": ["GLUTES", "HAMSTRINGS", "QUADRICEPS", "LEGS"],
    "HORIZONTAL_PRESS_BARBELL": ["CHEST", "TRICEPS", "SHOULDERS"],
    "HORIZONTAL_PRESS_DUMBBELL": ["CHEST", "TRICEPS", "SHOULDERS"],
    "HORIZONTAL_PRESS_MACHINE": ["CHEST", "TRICEPS", "SHOULDERS"],
    "INCLINE_PRESS_BARBELL": ["CHEST", "SHOULDERS", "TRICEPS"],
    "INCLINE_PRESS_DUMBBELL": ["CHEST", "SHOULDERS", "TRICEPS"],
    "DECLINE_PRESS": ["CHEST", "TRICEPS", "SHOULDERS"],
    "PUSHUP": ["CHEST", "TRICEPS", "SHOULDERS", "CORE", "ABS"],
    "DIPS": ["TRICEPS", "CHEST", "SHOULDERS"],
    "FLY": ["CHEST", "SHOULDERS"],
    "VERTICAL_PRESS_BARBELL": ["SHOULDERS", "TRICEPS", "TRAPS"],
    "VERTICAL_PRESS_DUMBBELL": ["SHOULDERS", "TRICEPS", "TRAPS"],
    "VERTICAL_PRESS_MACHINE": ["SHOULDERS", "TRICEPS", "TRAPS"],
    "LATERAL_RAISE": ["SHOULDERS"],
    "FRONT_RAISE": ["SHOULDERS"],
    "REAR_DELT": ["SHOULDERS", "TRAPS"],
    "FACE_PULL": ["SHOULDERS", "TRAPS", "BACK"],
    "UPRIGHT_ROW": ["SHOULDERS", "TRAPS", "BICEPS"],
    "SHRUG": ["TRAPS"],
    "BICEP_CURL_BARBELL": ["BICEPS", "FOREARMS"],
    "BICEP_CURL_DUMBBELL": ["BICEPS", "FOREARMS"],
    "BICEP_CURL_CABLE": ["BICEPS", "FOREARMS"],
    "TRICEP_OVERHEAD": ["TRICEPS", "SHOULDERS", "FOREARMS"],
    "TRICEP_LYING": ["TRICEPS", "FOREARMS"],
    "TRICEP_PUSHDOWN": ["TRICEPS", "SHOULDERS", "FOREARMS"],
    "CRUNCH": ["ABS", "CORE"],
    "LEG_RAISE": ["ABS", "CORE", "LEGS"],
    "PLANK": ["ABS", "CORE", "SHOULDERS"],
    "ROTATION": ["ABS", "CORE"],
    "ROLLOUT": ["ABS", "CORE", "SHOULDERS"],
}
CATEGORY_ORDER = {
    "CHEST": ["CHEST", "TRICEPS", "SHOULDERS"],
    "BACK": ["LATS", "BACK", "BICEPS", "TRAPS", "SHOULDERS", "FOREARMS"],
    "LEGS": ["QUADRICEPS", "GLUTES", "HAMSTRINGS", "CALVES", "LEGS"],
    "SHOULDERS": ["SHOULDERS", "TRICEPS", "TRAPS"],
    "ARMS": ["BICEPS", "TRICEPS", "FOREARMS"],
    "CORE": ["ABS", "CORE"],
}
# 이 패턴에서는 아래 근육 태그가 노이즈다(보조근으로도 의미가 약해 검색 순도·필터를 흐린다)
NOISE = {
    "LATERAL_RAISE": {"BACK", "TRAPS", "CHEST", "FOREARMS", "ABS", "CORE", "LATS", "TRICEPS", "BICEPS"},
    "FRONT_RAISE": {"BACK", "TRAPS", "CHEST", "FOREARMS", "ABS", "CORE", "LATS", "TRICEPS", "BICEPS"},
    "SHRUG": {"BACK", "SHOULDERS", "NECK", "CHEST", "LATS"},
    "GLUTE_FOCUSED": {"BACK", "ABS", "CORE", "SHOULDERS", "CHEST", "BICEPS", "TRICEPS"},
    "FLY": {"BICEPS", "FOREARMS", "TRICEPS", "ABS", "CORE"},
    "LEG_CURL": {"GLUTES", "BACK", "ABS", "CORE", "QUADRICEPS", "LEGS"},
    "SQUAT": {"BACK", "ABS", "CORE", "SHOULDERS", "TRICEPS", "CHEST"},
    "LUNGE": {"BACK", "ABS", "CORE"},
    "LEG_PRESS": {"BACK", "ABS", "CORE"},
    "BICEP_CURL_BARBELL": {"SHOULDERS", "BACK", "ABS", "CORE", "TRICEPS"},
    "BICEP_CURL_DUMBBELL": {"SHOULDERS", "BACK", "ABS", "CORE", "TRICEPS"},
    "BICEP_CURL_CABLE": {"SHOULDERS", "BACK", "ABS", "CORE", "TRICEPS"},
    "REAR_DELT": {"BACK", "FOREARMS", "ABS", "CORE", "BICEPS", "LATS"},
    "CRUNCH": {"LEGS", "QUADRICEPS", "BACK"},
}


def curate_muscles(e, cap):
    cur = list(e["muscleGroups"])
    pattern = e.get("movementPattern")
    name = e["translations"][0]["name"].lower()
    noise = set(NOISE.get(pattern, set()))
    # 컬 + 프레스/레이즈 복합 동작은 어깨 태그를 남긴다
    if pattern and pattern.startswith("BICEP_CURL") and re.search(r"press|raise|extension", name):
        noise -= {"SHOULDERS", "TRICEPS"}
    if "rear" in name and pattern in ("LATERAL_RAISE",):
        noise = NOISE["REAR_DELT"]
    kept = [m for m in cur if m not in noise]
    if not kept:
        kept = cur
    # 구체적인 하체 근육이 있으면 막연한 LEGS 태그는 뒤로
    order = PATTERN_ORDER.get(pattern) or CATEGORY_ORDER.get(e["category"]) or []
    if "rear" in name and pattern == "LATERAL_RAISE":
        order = PATTERN_ORDER["REAR_DELT"]
    ranked = sorted(kept, key=lambda m: (order.index(m) if m in order else 99, m))
    if e["category"] != "CARDIO":
        ranked = ranked[:cap]
    return ranked


# ── 5) 인기도 ────────────────────────────────────────────────────────────────────────────────────
EQ_BONUS = {"BARBELL": 6, "DUMBBELL": 6, "MACHINE": 5, "CABLE": 5, "BODYWEIGHT": 4, "KETTLEBELL": 0, "RESISTANCE_BAND": -4, "OTHER": -3, None: 0}
CAT_BONUS = {"CHEST": 3, "BACK": 3, "LEGS": 3, "SHOULDERS": 3, "ARMS": 2, "CORE": 1, "FULL_BODY": 0, "CARDIO": 0}
COMMON_PATTERNS = {"SQUAT", "LUNGE", "DEADLIFT", "PUSHUP", "PULLUP_CHINUP", "LAT_PULLDOWN", "CABLE_ROW", "DUMBBELL_ROW", "BARBELL_ROW",
                   "LATERAL_RAISE", "VERTICAL_PRESS_BARBELL", "VERTICAL_PRESS_DUMBBELL", "VERTICAL_PRESS_MACHINE",
                   "HORIZONTAL_PRESS_BARBELL", "HORIZONTAL_PRESS_DUMBBELL", "HORIZONTAL_PRESS_MACHINE",
                   "INCLINE_PRESS_BARBELL", "INCLINE_PRESS_DUMBBELL", "BICEP_CURL_BARBELL", "BICEP_CURL_DUMBBELL", "BICEP_CURL_CABLE",
                   "TRICEP_PUSHDOWN", "LEG_CURL", "LEG_EXTENSION", "LEG_PRESS", "CALF", "FLY", "PLANK", "CRUNCH", "GLUTE_FOCUSED",
                   "HIP_HINGE", "DIPS", "REAR_DELT", "FACE_PULL", "SHRUG", "TRICEP_OVERHEAD", "TRICEP_LYING"}
RARE_PATTERNS = {"OTHER": -2, "STRETCHING": -3, "ROTATION": -1, "CLEAN": -3, "SNATCH": -4, "COMPOUND_COMPLEX": -2}
VARIANT_WORDS = re.compile(
    r"single|one[- ]arm|one[- ]leg|alternat|slow|pause|hold|isometric|pulse|reverse grip|wide|close grip|narrow|twist|behind|"
    r"kneeling|lying|decline|stability|bosu|exercise ball|chair|towel|crossover|version|assisted|plate|on knees|"
    r"with |off |from |to |over |hammer strength|iso-lateral|iso lateral|mts|resistance band|pov|360", re.I)
PRIOR_BONUS = {80: 12, 75: 9, 70: 8, 65: 5, 60: 3}


def score_popularity(e):
    name = e["translations"][0]["name"]
    tier = e["recommendationTier"]
    base = 50 if tier == "STANDARD" else 25
    s = base + EQ_BONUS.get(e["equipment"], 0) + CAT_BONUS.get(e["category"], 0)
    pat = e.get("movementPattern")
    s += 4 if pat in COMMON_PATTERNS else RARE_PATTERNS.get(pat, 0)
    if e["isPlanEligible"]:
        s += 5
    hits = len(VARIANT_WORDS.findall(name))
    s -= min(hits, 4) * 2
    words = len(name.split())
    s -= min(max(words - 3, 0) * 0.8, 5)
    s -= len(name) / 10.0  # 이름이 짧을수록 대표 동작인 경우가 많다(세부 변형은 수식어가 붙는다)
    s += PRIOR_BONUS.get(e["_prior"], 0) if tier == "STANDARD" else 0
    if tier == "STANDARD":
        return max(40, min(round(s), 79))
    return max(8, min(round(s), 38))


# ── 메인 ───────────────────────────────────────────────────────────────────────────────────────
def load_base(base):
    raw = subprocess.run(["git", "show", f"{base}:src/main/resources/catalog/exercise-catalog.json"], cwd=ROOT,
                         capture_output=True, text=True, check=True).stdout
    return json.loads(raw)


def tname(e, loc):
    return next(t["name"] for t in e["translations"] if t["locale"] == loc)


def set_name(e, loc, name):
    for t in e["translations"]:
        if t["locale"] == loc:
            t["name"] = name
            return
    e["translations"].append(dict(locale=loc, name=name, instructions=None, tips=None))


def build_new_record(spec):
    plan = spec["isPlanEligible"]
    rec = dict(
        slug=spec["slug"], defaultLocale="en", category=spec["category"], equipment=spec["equipment"],
        equipmentDetail=spec["equipmentDetail"], sourceCategory=spec["sourceCategory"], movementPattern=spec["movementPattern"],
        muscleGroups=list(spec["muscleGroups"]), recommendationTier=spec["recommendationTier"], popularity=spec["popularity"],
        difficulty=spec["difficulty"], basicExercise=False, translations=[], isPlanEligible=plan,
        planCategory=spec["category"].lower() if plan else None)
    for loc in ("en", "ko", "ja"):
        steps, tips = spec["text"][loc]
        rec["translations"].append(dict(
            locale=loc, name=spec["names"][loc],
            instructions="\n".join(f"{i}. {s}" for i, s in enumerate(steps, 1)),
            tips="\n".join(f"{i}. {s}" for i, s in enumerate(tips, 1))))
    return rec


MIGRATION_HEAD = """-- LiftUp AI: 운동 카탈로그 4차 정리 — 중복 운동 {n}개를 사용자 기록과 함께 표준형으로 합친 뒤 삭제한다.
--
-- 사라지는 slug -> 남는 slug 는 src/main/resources/catalog/duplicate-map.json 과 같다(scripts/catalog/overhaul_catalog.py 가 둘을 함께 만든다).
-- 정규화하면 이름이 같은 쌍, 같은 동작의 표기/자세 변형, 브랜드 기구의 한쪽·교대 모드 변형이 대상이다. 남는 쪽은 모두 이미 운영에 있는 운동이다.
--
-- V20260925001 과 같은 방식: 사용자의 운동 기록·PR·플랜·대체 기록이 가리키는 변형 id 를 표준형 id 로 옮긴 뒤 지운다.
-- 슬러그로 매핑하므로 DB 에 없는 슬러그 쌍은 조용히 건너뛴다. exercises.id 를 참조하는 테이블은 엔티티 FK 전수 기준이며,
-- CatalogSyncIntegrationTest 가 스키마의 FK 목록과 이 파일을 대조해 새 참조 테이블이 생기면 실패한다.
-- 새 운동 삽입·메타데이터·이름 갱신은 이 마이그레이션이 아니라 기동 시 카탈로그 동기화가 한다.
--
-- 이식성: H2(MODE=MySQL)와 MySQL 8 에서 같은 SQL 이 돌도록 UPDATE ... JOIN 대신 상관 서브쿼리와 임시 스크래치 테이블을 쓴다
-- (MySQL 의 TEMPORARY 테이블은 한 문장에서 두 번 참조할 수 없어 일반 테이블을 쓰고 끝에 지운다).
-- 주의: MySQL DDL 은 implicit commit 이라 문장 단위로 확정된다. 중간 실패 시 flyway_schema_history 의 failed row 를 지우고 다시 실행하면 된다
-- (맨 앞에서 스크래치 테이블을 먼저 지우고, 모든 갱신은 재실행해도 결과가 같다).

DROP TABLE IF EXISTS catalog_dedup_slugs;
DROP TABLE IF EXISTS catalog_dedup_map;
DROP TABLE IF EXISTS catalog_dedup_ueo_ids;
DROP TABLE IF EXISTS catalog_dedup_es_ids;

CREATE TABLE catalog_dedup_slugs (
    duplicate_slug VARCHAR(191) NOT NULL PRIMARY KEY,
    canonical_slug VARCHAR(191) NOT NULL
);

INSERT INTO catalog_dedup_slugs (duplicate_slug, canonical_slug) VALUES
"""

MIGRATION_TAIL = """
CREATE TABLE catalog_dedup_map (
    duplicate_id BIGINT NOT NULL PRIMARY KEY,
    canonical_id BIGINT NOT NULL
);

INSERT INTO catalog_dedup_map (duplicate_id, canonical_id)
SELECT dup.id, canon.id
FROM catalog_dedup_slugs s
JOIN exercises dup ON dup.slug = s.duplicate_slug
JOIN exercises canon ON canon.slug = s.canonical_slug;

-- ── FK 참조 이동: 변형 id -> 표준형 id ─────────────────────────────────────
{repoint}

-- user_exercise_overrides (UNIQUE: enrollment_id + original_exercise_id) —
-- 표준형 row 가 이미 있거나 같은 등록에서 두 변형이 같은 표준형으로 모이면, 이동 전에 겹치는 변형 row 를 먼저 지운다.
CREATE TABLE catalog_dedup_ueo_ids (id BIGINT NOT NULL PRIMARY KEY);

INSERT INTO catalog_dedup_ueo_ids (id)
SELECT ueo.id
FROM user_exercise_overrides ueo
JOIN catalog_dedup_map m ON m.duplicate_id = ueo.original_exercise_id
JOIN user_exercise_overrides ueo2
    ON ueo2.enrollment_id = ueo.enrollment_id
    AND ueo2.original_exercise_id = m.canonical_id;

INSERT INTO catalog_dedup_ueo_ids (id)
SELECT ueo.id
FROM user_exercise_overrides ueo
JOIN catalog_dedup_map m ON m.duplicate_id = ueo.original_exercise_id
JOIN user_exercise_overrides ueo2
    ON ueo2.enrollment_id = ueo.enrollment_id
    AND ueo2.id < ueo.id
JOIN catalog_dedup_map m2
    ON m2.duplicate_id = ueo2.original_exercise_id
    AND m2.canonical_id = m.canonical_id
WHERE ueo.id NOT IN (SELECT id FROM catalog_dedup_ueo_ids);

DELETE FROM user_exercise_overrides
WHERE id IN (SELECT id FROM catalog_dedup_ueo_ids);

{ueo_repoint}

-- ── self-reference / 중복 row 정리 ─────────────────────────────────────────

DELETE FROM exercise_substitutions WHERE original_exercise_id = substitute_exercise_id;

CREATE TABLE catalog_dedup_es_ids (id BIGINT NOT NULL PRIMARY KEY);

INSERT INTO catalog_dedup_es_ids (id)
SELECT es.id
FROM exercise_substitutions es
JOIN exercise_substitutions es2
    ON es2.original_exercise_id = es.original_exercise_id
    AND es2.substitute_exercise_id = es.substitute_exercise_id
    AND es2.id < es.id;

DELETE FROM exercise_substitutions
WHERE id IN (SELECT id FROM catalog_dedup_es_ids);

DELETE FROM user_exercise_overrides
WHERE original_exercise_id = substitute_exercise_id;

UPDATE injury_exercise_restrictions
SET suggested_substitute_id = NULL
WHERE suggested_substitute_id IS NOT NULL
  AND suggested_substitute_id = restricted_exercise_id;

-- ── 변형 운동 자체 + 부속 row 삭제 ─────────────────────────────────────────

DELETE FROM exercise_translations
WHERE exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

DELETE FROM exercise_muscle_groups
WHERE exercise_id IN (SELECT duplicate_id FROM catalog_dedup_map);

DELETE FROM exercises
WHERE id IN (SELECT duplicate_id FROM catalog_dedup_map);

DROP TABLE catalog_dedup_es_ids;
DROP TABLE catalog_dedup_ueo_ids;
DROP TABLE catalog_dedup_map;
DROP TABLE catalog_dedup_slugs;
"""

# exercises.id 를 가리키는 (테이블, 컬럼) — 엔티티 FK 전수. user_exercise_overrides 는 유니크 때문에 따로 처리한다
FK_COLUMNS = [
    ("workout_exercises", "exercise_id"),
    ("personal_records", "exercise_id"),
    ("exercise_templates", "exercise_id"),
    ("template_day_exercises", "exercise_id"),
    ("user_plan_day_exercises", "exercise_id"),
    ("program_day_exercises", "exercise_id"),
    ("exercise_substitutions", "original_exercise_id"),
    ("exercise_substitutions", "substitute_exercise_id"),
    ("injury_exercise_restrictions", "restricted_exercise_id"),
    ("injury_exercise_restrictions", "suggested_substitute_id"),
]
UEO_COLUMNS = [("user_exercise_overrides", "original_exercise_id"), ("user_exercise_overrides", "substitute_exercise_id")]


def repoint_sql(cols):
    out = []
    for table, col in cols:
        out.append(
            f"UPDATE {table}\n"
            f"SET {col} = (SELECT m.canonical_id FROM catalog_dedup_map m WHERE m.duplicate_id = {table}.{col})\n"
            f"WHERE {col} IN (SELECT duplicate_id FROM catalog_dedup_map);\n")
    return "\n".join(out)


def write_migration(dup):
    rows = ",\n".join(f"    ('{k}', '{v}')" for k, v in sorted(dup.items()))
    body = (MIGRATION_HEAD.format(n=len(dup)) + rows + ";\n"
            + MIGRATION_TAIL.format(repoint=repoint_sql(FK_COLUMNS), ueo_repoint=repoint_sql(UEO_COLUMNS)))
    MIGRATION.parent.mkdir(parents=True, exist_ok=True)
    MIGRATION.write_text(body, encoding="utf8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="bcde543")
    ap.add_argument("--cdn-head", default=str(ROOT / "scripts/catalog/cdn-head.json"))
    args = ap.parse_args()

    base = load_base(args.base)
    by = {e["slug"]: e for e in base}
    for e in base:
        e["_prior"] = e["popularity"]

    # 모드 변형 자동 병합 (브랜드 기구)
    dup = dict(DUPLICATES)
    for slug in list(by):
        if not re.search(r"hammer-strength|iso-lateral|mts", slug):
            continue
        for suf in MODE_SUFFIXES:
            if slug.endswith(suf):
                stem = slug[: -len(suf)]
                for b in BOTH_SUFFIXES:
                    if stem + b in by and stem + b != slug:
                        dup[slug] = stem + b
                        break
                break
    # 연쇄 정리 + 검증
    for k in dup:
        assert k in by, f"중복 제거 대상이 기준 카탈로그에 없음: {k}"
    for k, v in dup.items():
        assert v in by, f"남는 slug 가 기준 카탈로그에 없음(신규로 합치면 마이그레이션이 대상 id 를 못 찾는다): {k} -> {v}"
        assert v not in dup, f"연쇄 병합: {k} -> {v} -> {dup[v]}"
    print(f"duplicates: {len(dup)}")
    for e in base:
        if e["slug"] in dup:
            keep = by[dup[e["slug"]]]
            # 사라지는 쪽이 더 높은 티어/플랜 후보면 남는 쪽에 이어준다
            if e["isPlanEligible"] and not keep["isPlanEligible"]:
                keep["isPlanEligible"] = True
                keep["planCategory"] = keep["planCategory"] or keep["category"].lower()
    cat = [e for e in base if e["slug"] not in dup]
    by = {e["slug"]: e for e in cat}

    # 신규 중 기존 slug 와 겹치면 기존 항목을 업그레이드(이름·설명·메타를 덮어씀)
    new_specs = []
    for spec in NEW_ENTRIES:
        if spec["slug"] in by:
            e = by[spec["slug"]]
            rec = build_new_record(spec)
            for k in ("category", "equipment", "equipmentDetail", "movementPattern", "muscleGroups", "recommendationTier",
                      "popularity", "difficulty", "isPlanEligible", "planCategory", "translations"):
                e[k] = rec[k]
            e["_prior"] = rec["popularity"]
            e["_curated"] = True
        else:
            new_specs.append(spec)

    # 메타데이터 수정
    for slug, fields in META.items():
        e = by.get(slug)
        if e is None:
            print(f"  (skip meta, slug gone) {slug}")
            continue
        for k, v in fields.items():
            e[k] = list(v) if k == "muscleGroups" else v
        if "popularity" in fields:
            e["_curated"] = True
        if fields.get("isPlanEligible") and not e.get("planCategory"):
            e["planCategory"] = e["category"].lower()
    # 이름 수정
    for slug, names in NAMES.items():
        e = by.get(slug)
        if e is None:
            print(f"  (skip names, slug gone) {slug}")
            continue
        for loc, nm in names.items():
            set_name(e, loc, nm)

    # 일반 규칙: 장비·패턴 보정
    for e in cat:
        n = tname(e, "en").lower()
        if "smith" in n and e["equipment"] in ("BARBELL", "RESISTANCE_BAND"):
            e["equipment"] = "MACHINE"
        if e["equipment"] in ("CABLE", "MACHINE") and e.get("movementPattern") == "BICEP_CURL_BARBELL":
            e["movementPattern"] = "BICEP_CURL_CABLE"
        if e["equipment"] == "DUMBBELL" and e.get("movementPattern") == "HORIZONTAL_PRESS_MACHINE":
            e["movementPattern"] = "INCLINE_PRESS_DUMBBELL" if "incline" in n else "HORIZONTAL_PRESS_DUMBBELL"
        # 일본어 표기 통일 (ロウ→ロー 134:32, バイセップカール→バイセップスカール 8:27). 여자·크로 같은 단어는 건드리지 않는다
        ja = tname(e, "ja")
        ja2 = re.sub(r"バイセップカール", "バイセップスカール", ja)
        if not re.search(r"クロウ|スモウ", ja2):
            ja2 = re.sub(r"(?<=[ァ-ヴー])ロウ(?=$|[（）()・\s　])", "ロー", ja2)
        if ja2 != ja:
            set_name(e, "ja", ja2)
        # 한국어 "벤트 오버" 띄어쓰기 통일
        ko = tname(e, "ko")
        ko2 = ko.replace("벤트 오버", "벤트오버")
        if ko2 != ko:
            set_name(e, "ko", ko2)
    # 신규 기존 이름과의 충돌 방지를 위해 신규를 합치기 전에 신규 레코드 생성
    new_records = [build_new_record(s) for s in new_specs]
    for r in new_records:
        r["_curated"] = True
        r["_prior"] = r["popularity"]

    # 근육 태그 정리 + 인기도 재산정 (신규·수동 지정은 건드리지 않는다)
    for e in cat:
        if not e.get("_curated_muscles"):
            cap = 3 if e["recommendationTier"] == "ESSENTIAL" else 5
            e["muscleGroups"] = curate_muscles(e, cap)
    for e in cat:
        if e["recommendationTier"] == "ESSENTIAL":
            continue
        if e.get("_curated"):
            continue
        e["popularity"] = score_popularity(e)
    for e in cat:
        if e["recommendationTier"] == "ESSENTIAL" and e["slug"] in ESSENTIAL_POPULARITY:
            e["popularity"] = ESSENTIAL_POPULARITY[e["slug"]]
    # ESSENTIAL 인기도는 기존 값 사용(상단 정리 후 남은 것). 더 이상 필요 없는 임시 키 제거
    # ESSENTIAL 중 수동 지정 안 된 값은 90-98 유지
    allx = cat + new_records
    for e in allx:
        for k in ("_prior", "_curated", "_curated_muscles"):
            e.pop(k, None)
    out = sorted(allx, key=lambda r: r["slug"])

    # 근육 정리 후 ESSENTIAL 평균 확인용 출력
    ess = [e for e in out if e["recommendationTier"] == "ESSENTIAL"]
    print("essential", len(ess), "avg muscles", sum(len(e["muscleGroups"]) for e in ess) / len(ess))

    (CAT_DIR / "exercise-catalog.json").write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf8")
    (CAT_DIR / "duplicate-map.json").write_text(json.dumps(dict(sorted(dup.items())), ensure_ascii=False, indent=2) + "\n", encoding="utf8")

    write_migration(dup)
    # 미디어 존재 목록: CDN HEAD 결과(기존) + 신규는 false
    head = json.load(open(args.cdn_head))
    media = {}
    for e in out:
        h = head.get(e["slug"])
        media[e["slug"]] = dict(thumb=bool(h and h.get("thumb.jpg") == 200), video=bool(h and h.get("video.mp4") == 200))
    (CAT_DIR / "exercise-media.json").write_text(json.dumps(media, ensure_ascii=False, indent=1) + "\n", encoding="utf8")
    print("catalog", len(out), "new", len(new_records), "media thumb", sum(m["thumb"] for m in media.values()))
    c = Counter(e["popularity"] for e in out if e["recommendationTier"] == "STANDARD")
    print("std pop top", c.most_common(5), "of", sum(c.values()))


if __name__ == "__main__":
    main()
