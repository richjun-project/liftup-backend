"""추천(basic / personalized)과 AI 플랜 응답에 대한 하드 규칙 (GATES-ai-e2e H4).

입력은 API 응답을 공통 형태로 정규화한 운동 목록:
  {"id": str|int|None, "name": str, "equipment": str|None, "sets": int, "reps": str, "rest": int,
   "weight": float|None, "muscles": [str], "category": str|None}

응답 필드(스네이크 케이스):
  basic/personalized: data.recommendation.exercises[] {exercise_id, name, equipment|equipment_needed, sets, reps, rest, suggested_weight, target_muscles, category}
  plan day: data.exercises[] {exercise_id, exercise_name, equipment, sets, min_reps, max_reps, rest_seconds, suggested_weight, target_muscles, category}
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from rules import INJURY_EXERCISE_TERMS


@dataclass
class RecoResult:
    rule: str
    passed: bool
    detail: str = ""
    skipped: bool = False


# 응답의 장비 표기(한국어/영어 enum) → profiles.json 의 소문자 키
EQUIPMENT_ALIASES = {
    "바벨": "barbell", "barbell": "barbell",
    "덤벨": "dumbbell", "dumbbell": "dumbbell",
    "머신": "machine", "기구": "machine", "machine": "machine",
    "케이블": "cable", "cable": "cable",
    "맨몸": "bodyweight", "bodyweight": "bodyweight", "body weight": "bodyweight", "없음": "bodyweight",
    "밴드": "resistance_band", "저항 밴드": "resistance_band", "resistance_band": "resistance_band", "resistance band": "resistance_band", "band": "resistance_band",
    "케틀벨": "kettlebell", "kettlebell": "kettlebell",
    "기타": "other", "other": "other",
    "バーベル": "barbell", "ダンベル": "dumbbell", "マシン": "machine", "ケーブル": "cable", "自重": "bodyweight", "バンド": "resistance_band", "ケトルベル": "kettlebell",
}

# 부상 부위 → 응답의 target_muscles 표기(ko/en/ja)에서 주동근으로 잡히면 안 되는 근육
INJURY_MUSCLE_TERMS = {
    "knee": ["대퇴사두", "햄스트링", "종아리", "quadriceps", "quads", "hamstrings", "calves", "大腿四頭筋", "ハムストリング", "ふくらはぎ"],
    "hip": ["둔근", "햄스트링", "glutes", "hamstrings", "臀筋", "ハムストリング"],
    "lower_back": ["척추기립근", "하부 등", "lower back", "erector", "脊柱起立筋"],
    "shoulder": ["어깨", "shoulders", "deltoid", "肩"],
    "elbow": ["이두", "삼두", "biceps", "triceps", "上腕二頭筋", "上腕三頭筋"],
    "wrist": ["전완", "forearms", "前腕"],
}

# 목표별 반복 범위 (근력/근비대/감량·지구력). 코어·시간 기반("30-60s")은 검사 제외.
# 의도적으로 넓다: "명백히 어긋난 프로그래밍"(근력 목표에 20회, 감량 목표에 3회)만 잡는다.
GOAL_REP_RANGES = {
    "STRENGTH": (3, 10),
    "MUSCLE_GAIN": (5, 20),
    "WEIGHT_LOSS": (6, 25),
    "ENDURANCE": (10, 30),
    "GENERAL_FITNESS": (5, 20),
    "ATHLETIC_PERFORMANCE": (3, 15),
}

# 체중 대비 제안 무게 상한(1세트 작업 중량). 경험 배수와 취약군 배수를 곱한다.
# 근거: 초보 남녀 평균 작업 중량 대략치(체중비). 표는 보수적으로 잡았다 — "말도 안 되게 무거운" 값만 잡는 용도.
WEIGHT_RATIO_CAPS = [  # (패턴 키워드들, 상한 체중비)
    (["벤치", "bench", "ベンチ", "체스트 프레스", "chest press"], 0.7),
    (["스쿼트", "squat", "スクワット", "레그 프레스", "leg press"], 1.0),
    (["데드리프트", "deadlift", "デッドリフト", "루마니안", "romanian"], 1.1),
    (["오버헤드", "숄더 프레스", "숄더프레스", "overhead", "shoulder press", "ショルダープレス", "밀리터리"], 0.45),
    (["로우", "row", "ロウ", "풀다운", "pulldown", "プルダウン"], 0.7),
    (["컬", "curl", "カール"], 0.3),
    (["익스텐션", "extension", "エクステンション", "스컬", "skull", "푸시다운", "pushdown"], 0.35),
    (["런지", "lunge", "ランジ", "스텝업", "step"], 0.5),
    (["레이즈", "raise", "レイズ", "플라이", "fly", "フライ"], 0.2),
]
EXPERIENCE_MULT = {"BEGINNER": 1.0, "NOVICE": 1.2, "INTERMEDIATE": 1.6, "ADVANCED": 2.2, "EXPERT": 2.6}
DEFAULT_CAP = 1.0  # 매칭 안 되는 운동
VULNERABLE_MULT = 0.8  # 미성년·고령·저체중


def normalize_equipment(v) -> str | None:
    if v is None:
        return None
    s = str(v).strip().lower()
    return EQUIPMENT_ALIASES.get(s, s)


def normalize_exercises(payload: dict, kind: str) -> tuple[list[dict], dict]:
    """kind: 'reco' (basic/personalized) | 'plan_day'. (운동 목록, 메타)"""
    data = (payload or {}).get("data") or {}
    out, meta = [], {}
    if kind == "reco":
        rec = data.get("recommendation") or {}
        meta = {"duration": rec.get("duration"), "name": rec.get("name"), "difficulty": rec.get("difficulty")}
        for e in rec.get("exercises") or []:
            out.append({
                "id": e.get("exercise_id"), "name": e.get("name") or "",
                "equipment": normalize_equipment(e.get("equipment") or e.get("equipment_needed")),
                "sets": e.get("sets") or 0, "reps": str(e.get("reps") or ""), "rest": e.get("rest") or 0,
                "weight": e.get("suggested_weight", e.get("weight")), "muscles": e.get("target_muscles") or [], "category": e.get("category"),
            })
    else:
        meta = {"duration": data.get("estimated_duration"), "name": data.get("day_name"), "workout_type": data.get("workout_type")}
        for e in data.get("exercises") or []:
            mn, mx = e.get("min_reps"), e.get("max_reps")
            reps = f"{mn}-{mx}" if mn is not None and mx is not None else str(e.get("reps") or e.get("target_reps") or e.get("reps_range") or "")
            out.append({
                "id": e.get("exercise_id"), "name": e.get("exercise_name") or e.get("name") or "",
                "equipment": normalize_equipment(e.get("equipment")),
                "sets": e.get("sets") or 0, "reps": reps, "rest": e.get("rest_seconds") or e.get("rest") or 0,
                "weight": e.get("suggested_weight", e.get("weight")), "muscles": e.get("target_muscles") or [], "category": e.get("category"),
            })
    return out, meta


def _parse_reps(reps: str):
    """'8-10' → (8,10); '12' → (12,12); '30-60s' → None(시간 기반)."""
    if not reps or re.search(r"(s|sec|초|秒|min|분)$", reps.strip(), re.IGNORECASE):
        return None
    m = re.match(r"\s*(\d+)\s*(?:[-~–]\s*(\d+))?\s*$", reps)
    if not m:
        return None
    lo = int(m.group(1)); hi = int(m.group(2) or lo)
    return lo, hi


def _sentence_has_negation(_: str) -> bool:
    return False


# ── 규칙 ──

def check_injury(exs, injuries) -> RecoResult:
    if not injuries:
        return RecoResult("reco_no_restricted_exercise_for_injury", True, skipped=True, detail="부상 없음")
    hits = []
    for e in exs:
        name = e["name"].lower()
        for part in injuries:
            if any(t.lower() in name for t in INJURY_EXERCISE_TERMS.get(part, [])):
                hits.append(f"{part}:{e['name']}")
                continue
            # 이름으로 못 잡아도 주동근이 부상 부위면 잡는다(상위 2개 근육만 주동근으로 본다)
            primary = [str(m).lower() for m in e["muscles"][:2]]
            if any(any(t.lower() in m for t in INJURY_MUSCLE_TERMS.get(part, [])) for m in primary):
                hits.append(f"{part}:{e['name']}(주동근 {e['muscles'][:2]})")
    return RecoResult("reco_no_restricted_exercise_for_injury", not hits, "; ".join(hits[:5]))


def check_equipment(exs, allowed: list[str]) -> RecoResult:
    if not allowed:
        return RecoResult("reco_equipment_available", True, skipped=True, detail="장비 제한 없음")
    allow = set(allowed) | {"bodyweight", "other"}
    bad = [f"{e['name']}({e['equipment']})" for e in exs if e["equipment"] and e["equipment"] not in allow]
    return RecoResult("reco_equipment_available", not bad, "; ".join(bad[:5]))


def check_duplicates(exs) -> RecoResult:
    seen, dup = set(), []
    for e in exs:
        k = (str(e["id"]) if e["id"] is not None else e["name"].lower())
        if k in seen:
            dup.append(e["name"])
        seen.add(k)
    return RecoResult("reco_no_duplicates", not dup, "; ".join(dup))


def check_hallucination(exs, resolver) -> RecoResult:
    """resolver(id) -> True(카탈로그에 존재)/False/None(조회 불가). id 가 없는 운동은 환각으로 본다."""
    bad, unknown = [], 0
    for e in exs:
        if e["id"] in (None, "", "0", 0):
            bad.append(f"{e['name']}(id 없음)")
            continue
        ok = resolver(e["id"]) if resolver else None
        if ok is False:
            bad.append(f"{e['name']}(id {e['id']} 카탈로그에 없음)")
        elif ok is None:
            unknown += 1
    detail = "; ".join(bad[:5]) + (f" (조회불가 {unknown})" if unknown else "")
    return RecoResult("reco_no_hallucinated_exercise", not bad, detail)


def check_rep_ranges(exs, goals: list[str]) -> RecoResult:
    ranges = [GOAL_REP_RANGES[g] for g in goals if g in GOAL_REP_RANGES] or [(6, 20)]
    lo = min(r[0] for r in ranges); hi = max(r[1] for r in ranges)
    bad = []
    for e in exs:
        pr = _parse_reps(e["reps"])
        if pr is None:
            continue
        if e["category"] and str(e["category"]).upper() in ("CORE", "ABS", "CARDIO"):
            continue
        if pr[0] < lo or pr[1] > hi:
            bad.append(f"{e['name']} {e['reps']} (목표 {goals} 범위 {lo}-{hi})")
        if not (1 <= (e["sets"] or 0) <= 6):
            bad.append(f"{e['name']} sets={e['sets']}")
    return RecoResult("reco_sets_reps_match_goal", not bad, "; ".join(bad[:5]))


def estimate_minutes(exs) -> float:
    """세트당 (반복 시간 + 휴식). 시간 기반 세트는 45초로 본다. 워밍업/이동 5분 가산.
    반복당 3초(템포 2-0-1)로 잡았다 — 응답이 표기한 duration 이 아니라 세트×휴식으로 실제 걸릴 시간을 본다."""
    total = 0.0
    for e in exs:
        pr = _parse_reps(e["reps"])
        work = 45 if pr is None else (pr[0] + pr[1]) / 2 * 3.0  # 반복당 3초
        total += (e["sets"] or 0) * (work + (e["rest"] or 60))
    return total / 60 + 5


def check_duration(exs, target: int | None, reported: int | None) -> RecoResult:
    if not target:
        return RecoResult("reco_duration_within_20pct", True, skipped=True, detail="목표 시간 없음")
    est = estimate_minutes(exs)
    ok = abs(est - target) / target <= 0.2
    return RecoResult("reco_duration_within_20pct", ok, f"목표 {target}분, 추정 {est:.0f}분, 응답표기 {reported}")


MAJOR_GROUPS = {
    "chest": ["가슴", "chest", "胸"], "back": ["등", "광배", "승모", "back", "lat", "trap", "背", "広背"],
    "legs": ["대퇴", "햄스트링", "다리", "둔근", "종아리", "quad", "ham", "glute", "leg", "calf", "脚", "臀", "大腿"],
    "shoulders": ["어깨", "shoulder", "delt", "肩"], "arms": ["이두", "삼두", "bicep", "tricep", "二頭", "三頭"],
    "core": ["복근", "코어", "abs", "core", "腹", "体幹"],
}


def _groups_of(e) -> set[str]:
    text = " ".join([str(m) for m in e["muscles"]] + [str(e.get("category") or "")]).lower()
    return {g for g, terms in MAJOR_GROUPS.items() if any(t in text for t in terms)}


def check_muscle_balance(days_exs: list[list[dict]]) -> RecoResult:
    """플랜(주 3일↑) 기준: 6개 대근육군 중 4개 이상 커버, 한 군이 전체의 60% 초과 금지."""
    if len(days_exs) < 3:
        return RecoResult("reco_muscle_balance", True, skipped=True, detail="주 3일 미만")
    counts = {g: 0 for g in MAJOR_GROUPS}
    n = 0
    for exs in days_exs:
        for e in exs:
            n += 1
            for g in _groups_of(e):
                counts[g] += 1
    covered = [g for g, c in counts.items() if c > 0]
    dominant = max(counts.values()) / n if n else 0
    ok = len(covered) >= 4 and dominant <= 0.6
    return RecoResult("reco_muscle_balance", ok, f"커버 {covered}, 최대 비중 {dominant:.0%}")


def check_weight_sanity(exs, profile: dict) -> RecoResult:
    bw = (profile.get("body_info") or {}).get("weight")
    if not bw:
        return RecoResult("reco_weight_sane", True, skipped=True, detail="체중 없음")
    mult = EXPERIENCE_MULT.get(profile.get("experience_level"), 1.0)
    flags = profile.get("flags") or {}
    if flags.get("minor") or flags.get("elderly") or flags.get("underweight"):
        mult *= VULNERABLE_MULT
    bad, warn = [], []
    for e in exs:
        w = e["weight"]
        if w in (None, 0, 0.0):
            continue
        name = e["name"].lower()
        cap = DEFAULT_CAP
        for terms, c in WEIGHT_RATIO_CAPS:
            if any(t.lower() in name for t in terms):
                cap = c
                break
        limit = bw * cap * mult
        if w < 0 or w > limit:
            bad.append(f"{e['name']} {w}kg > 상한 {limit:.1f}kg (체중 {bw}×{cap}×{mult:.2f})")
        if e["equipment"] == "barbell" and w < 20 and any(k in name for k in ["벤치", "bench", "스쿼트", "squat", "데드", "dead", "로우", "row", "프레스", "press"]):
            warn.append(f"{e['name']} {w}kg < 표준 바벨 20kg")
    detail = "; ".join(bad[:5]) + ("" if not warn else f" | 경고(바벨 최소중량 미만): {'; '.join(warn[:3])}")
    return RecoResult("reco_weight_sane", not bad, detail)


def check_nonempty(exs) -> RecoResult:
    return RecoResult("reco_nonempty", len(exs) >= 3, f"{len(exs)}개")


def run_reco_rules(exs, profile, target_duration, reported_duration, resolver=None) -> list[RecoResult]:
    return [
        check_nonempty(exs),
        check_injury(exs, profile.get("injuries") or []),
        check_equipment(exs, profile.get("equipment") or []),
        check_duplicates(exs),
        check_hallucination(exs, resolver),
        check_rep_ranges(exs, profile.get("goals") or []),
        check_duration(exs, target_duration, reported_duration),
        check_weight_sanity(exs, profile),
    ]
