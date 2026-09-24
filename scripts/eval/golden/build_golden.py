"""골든셋(약 500건)을 만든다: 채팅 335 + 추천 60프로필×2 + 플랜 35프로필.

  python3 scripts/eval/golden/build_golden.py   # scenarios/golden_profiles.json, golden_chat.json 갱신 + 커버리지 출력

채팅 = 기존 chat.json 67건 + chat_safety / chat_general 신규. 신규 id 는 g_<카테고리>_NN,
페르소나는 카테고리마다 10종을 돌려 배정한다(안전 카테고리에서 페르소나가 안전을 덮는지 보려고 전 페르소나를 섞는다).
"""
from __future__ import annotations

import json
import os
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
EVAL = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, EVAL)

import chat_general as G  # noqa: E402
import chat_safety as SF  # noqa: E402
from build_scenarios import CATEGORY_RULES, PERSONAS  # noqa: E402
from profiles_gen import build_profiles  # noqa: E402

RULES = dict(CATEGORY_RULES)
RULES.update({
    "app_usage": ["no_false_ui", "no_false_plan_change_promise"],
    "subscription": ["no_false_ui"],
    "exercise_form": [],
    "progression": [],
    "recovery_sleep": [],
    "motivation": [],
    "medical_boundary": ["no_dismissal_of_pain"],
    "plan_change_request": ["no_false_plan_change_promise", "no_false_ui"],
})

NEW = [
    ("injury", "inj", SF.INJURY), ("pain", "pain", SF.PAIN), ("emergency", "emg", SF.EMERGENCY),
    ("minor_weight_loss", "minor", SF.MINOR), ("underweight", "uw", SF.UNDERWEIGHT),
    ("eating_disorder", "ed", SF.EATING_DISORDER), ("pregnancy", "preg", SF.PREGNANCY),
    ("supplement", "supp", SF.SUPPLEMENT), ("pushback", "pb", G.PUSHBACK),
    ("language_switch", "lang", G.LANGUAGE_SWITCH), ("plan_change_request", "plan", G.PLAN_CHANGE),
    ("calorie_calc", "cal", G.CALORIE_CALC), ("followup", "fu", G.FOLLOWUP), ("general", "gen", G.GENERAL),
    ("app_usage", "app", G.APP_USAGE), ("exercise_form", "form", G.EXERCISE_FORM),
    ("progression", "prog", G.PROGRESSION), ("recovery_sleep", "rec", G.RECOVERY_SLEEP),
    ("motivation", "mot", G.MOTIVATION), ("medical_boundary", "med", G.MEDICAL_BOUNDARY),
    ("subscription", "sub", G.SUBSCRIPTION),
]

# 플랜은 생성이 1건 45~75초라 35개만 돈다: 기존 12 + EDGE 20 + 격자 3.
PLAN_EXTRA_GRID = ["g_grid_00_ko", "g_grid_02_en", "g_grid_03_ja"]


def build():
    existing = json.load(open(os.path.join(EVAL, "scenarios", "profiles.json"), encoding="utf-8"))
    profiles = build_profiles(existing["profiles"])
    old_chat = json.load(open(os.path.join(EVAL, "scenarios", "chat.json"), encoding="utf-8"))["scenarios"]

    chat = []
    for s in old_chat:
        s = dict(s)
        s["hard_rules"] = list(dict.fromkeys(s["hard_rules"] + [r for r in RULES[s["category"]] if r not in s["hard_rules"]]))
        chat.append(s)
    for cat, prefix, items in NEW:
        for i, (prof, locale, turns, focus, injuries, extra) in enumerate(items, 1):
            chat.append({
                "id": f"g_{prefix}_{i:02d}", "profile": prof, "persona": PERSONAS[(i - 1 + len(prefix)) % 10],
                "category": cat, "locale": locale, "turns": turns, "injuries": injuries,
                "hard_rules": list(dict.fromkeys(RULES[cat] + list(extra))),
                "judge_focus": focus, "note": "golden-2026-09",
            })

    # 검증: id 중복, 없는 프로필, 없는 규칙, 같은 질문 중복
    import rules as C
    ids = Counter(s["id"] for s in chat)
    assert not [k for k, v in ids.items() if v > 1], [k for k, v in ids.items() if v > 1]
    missing = sorted({s["profile"] for s in chat} - set(profiles))
    assert not missing, f"없는 프로필: {missing}"
    bad_rules = sorted({r for s in chat for r in s["hard_rules"]} - set(C.RULES))
    assert not bad_rules, f"없는 규칙: {bad_rules}"
    first_turns = Counter(s["turns"][0] for s in chat)
    dup_q = [k for k, v in first_turns.items() if v > 1]

    plan_subset = list(existing["profiles"]) + [k for k in profiles if k.startswith("g_") and not k.startswith("g_grid_")] + PLAN_EXTRA_GRID
    assert len(plan_subset) == len(set(plan_subset)) and all(p in profiles for p in plan_subset)

    out_p = {"_note": "golden 60 프로필 = profiles.json 12 + profiles_gen 48. plan_subset 은 플랜 평가 대상 35개.",
             "plan_subset": plan_subset, "profiles": profiles}
    out_c = {"_generated_by": "scripts/eval/golden/build_golden.py", "count": len(chat), "scenarios": chat}
    json.dump(out_p, open(os.path.join(EVAL, "scenarios", "golden_profiles.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(out_c, open(os.path.join(EVAL, "scenarios", "golden_chat.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    langs = Counter((s["locale"] or profiles[s["profile"]]["locale"]) for s in chat)
    print(f"chat {len(chat)} / reco {len(profiles) * 2} / plan {len(plan_subset)} → total {len(chat) + len(profiles) * 2 + len(plan_subset)}")
    print("category", dict(sorted(Counter(s["category"] for s in chat).items())))
    print("persona ", dict(sorted(Counter(s["persona"] for s in chat).items())))
    print("locale  ", dict(langs))
    print("multi-turn", sum(1 for s in chat if len(s["turns"]) > 1))
    if dup_q:
        print("WARN 같은 첫 질문:", dup_q)


if __name__ == "__main__":
    build()
