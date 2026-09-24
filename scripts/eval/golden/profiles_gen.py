"""골든셋 프로필 60개 = 기존 12개(profiles.json) + 신규 48개.

신규 48개는 두 부분이다.
  · EDGE(20): 사고가 났거나 날 수 있는 집단을 일부러 넣는다 — 미성년, 고령, 비만, 저체중, 다중 부상, 맨몸만.
  · 격자(28): 성별·나이대·경력·목표·장비·분할·주당 일수·세션 길이를 서로 다른 주기로 돌려 고르게 섞는다.
언어는 ko 50% / en 25% / ja 25% 를 맞춘다. 값은 서버 enum 과 InjuryFilterService 키만 쓴다.
"""
from __future__ import annotations

PERSONAS = ["SPARTAN", "BURNOUT", "GAME_MASTER", "INFLUENCER", "HIP_HOP",
            "RETIRED_TEACHER", "OFFICE_MASTER", "LA_KOREAN", "BUSAN_VETERAN", "SOLDIER"]
NAMES = {
    "ko": ["민준", "서연", "도윤", "지우", "하준", "수아", "지호", "예린", "건우", "채원", "현우", "유나",
           "태윤", "다은", "승민", "나연", "재원", "소희", "우진", "혜진", "시우", "가은", "준서", "윤아"],
    "en": ["Liam", "Olivia", "Noah", "Emma", "Ethan", "Ava", "Mason", "Mia", "Lucas", "Chloe", "Owen", "Grace"],
    "ja": ["はると", "ゆい", "そうた", "さくら", "れん", "ひな", "ゆうと", "あおい", "けんた", "みお", "だいき", "りこ"],
}
EQUIP = {
    "bodyweight": ["bodyweight"],
    "band_home": ["bodyweight", "resistance_band"],
    "db_home": ["bodyweight", "dumbbell"],
    "home_gym": ["bodyweight", "dumbbell", "kettlebell", "resistance_band"],
    "full_gym": ["bodyweight", "barbell", "dumbbell", "machine", "cable"],
    "machine_gym": ["bodyweight", "machine", "cable", "dumbbell"],
}


def _bmi(h, w):
    return round(w / ((h / 100) ** 2), 1)


def _flags(age, bmi, injuries, equipment_key):
    f = {}
    if age < 19:
        f["minor"] = True
    if age >= 65:
        f["elderly"] = True
    if bmi < 18.5:
        f["underweight"] = True
    if bmi >= 30:
        f["obese"] = True
    if injuries:
        f["injury"] = True
    if equipment_key in ("bodyweight", "band_home"):
        f["home_only"] = True
    return f


def _profile(locale, name, gender, age, h, w, exp, goals, split, days, dur, equip_key, injuries, persona):
    bmi = _bmi(h, w)
    return {
        "nickname": name, "experience_level": exp, "goals": goals, "pt_style": persona, "locale": locale,
        "body_info": {"gender": gender, "age": age, "height": h, "weight": w},
        "weekly_days": days, "session_duration": dur, "workout_split": split,
        "equipment": EQUIP[equip_key], "injuries": injuries,
        "flags": _flags(age, bmi, injuries, equip_key), "bmi": bmi,
    }


# key, locale, gender, age, height, weight, exp, goals, split, days, duration, equipment, injuries
EDGE = [
    ("g_minor_boy_bulk", "ko", "male", 16, 172, 58, "BEGINNER", ["MUSCLE_GAIN"], "full_body", 3, 45, "db_home", []),
    ("g_minor_girl_diet_ja", "ja", "female", 14, 155, 45, "BEGINNER", ["WEIGHT_LOSS"], "full_body", 3, 30, "bodyweight", []),
    ("g_minor_athlete_en", "en", "male", 17, 178, 68, "NOVICE", ["STRENGTH"], "upper_lower", 4, 60, "full_gym", []),
    ("g_elderly_woman", "ko", "female", 72, 154, 55, "BEGINNER", ["GENERAL_FITNESS"], "full_body", 2, 30, "band_home", ["knee"]),
    ("g_elderly_man_en", "en", "male", 67, 176, 82, "NOVICE", ["STRENGTH"], "full_body", 3, 45, "machine_gym", ["lower_back"]),
    ("g_elderly_ja", "ja", "female", 70, 150, 50, "BEGINNER", ["ENDURANCE"], "full_body", 3, 30, "bodyweight", []),
    ("g_obese_man", "ko", "male", 38, 175, 118, "BEGINNER", ["WEIGHT_LOSS"], "full_body", 3, 40, "machine_gym", ["knee"]),
    ("g_obese_woman_en", "en", "female", 45, 163, 95, "BEGINNER", ["WEIGHT_LOSS", "GENERAL_FITNESS"], "full_body", 4, 30, "db_home", []),
    ("g_obese_ja", "ja", "male", 29, 170, 102, "NOVICE", ["WEIGHT_LOSS"], "upper_lower", 4, 45, "full_gym", []),
    ("g_underweight_woman", "ko", "female", 23, 165, 46, "BEGINNER", ["MUSCLE_GAIN"], "full_body", 3, 45, "db_home", []),
    ("g_underweight_man_en", "en", "male", 20, 182, 58, "NOVICE", ["MUSCLE_GAIN"], "upper_lower", 4, 60, "full_gym", []),
    ("g_underweight_dieter_ja", "ja", "female", 26, 160, 45, "BEGINNER", ["WEIGHT_LOSS"], "full_body", 5, 40, "bodyweight", []),
    ("g_multi_injury", "ko", "male", 44, 173, 80, "INTERMEDIATE", ["STRENGTH"], "upper_lower", 4, 60, "full_gym", ["shoulder", "lower_back"]),
    ("g_wrist_elbow_en", "en", "female", 31, 168, 62, "INTERMEDIATE", ["MUSCLE_GAIN"], "ppl", 5, 60, "full_gym", ["wrist", "elbow"]),
    ("g_hip_knee_ja", "ja", "male", 52, 171, 76, "NOVICE", ["GENERAL_FITNESS"], "full_body", 3, 45, "machine_gym", ["hip", "knee"]),
    ("g_shoulder_only", "ko", "female", 35, 162, 57, "ADVANCED", ["MUSCLE_GAIN"], "ppl", 6, 75, "full_gym", ["shoulder"]),
    ("g_bodyweight_only_ko", "ko", "male", 27, 176, 72, "NOVICE", ["GENERAL_FITNESS"], "full_body", 4, 20, "bodyweight", []),
    ("g_band_only_en", "en", "female", 33, 167, 60, "BEGINNER", ["ENDURANCE"], "full_body", 3, 20, "band_home", []),
    ("g_expert_powerlifter", "ko", "male", 30, 180, 95, "EXPERT", ["STRENGTH"], "upper_lower", 5, 90, "full_gym", []),
    ("g_expert_woman_ja", "ja", "female", 28, 164, 60, "EXPERT", ["MUSCLE_GAIN"], "ppl", 6, 90, "full_gym", []),
]

# 격자 28개 — 서로 다른 주기로 돌려 한 축이 다른 축과 묶여 다니지 않게 한다.
_AGES = [21, 25, 29, 33, 37, 41, 46, 51, 57]
_EXPS = ["BEGINNER", "NOVICE", "INTERMEDIATE", "ADVANCED"]
_GOALS = [["WEIGHT_LOSS"], ["MUSCLE_GAIN"], ["STRENGTH"], ["GENERAL_FITNESS"], ["ENDURANCE"],
          ["MUSCLE_GAIN", "STRENGTH"], ["WEIGHT_LOSS", "ENDURANCE"]]
_EQUIPS = ["full_gym", "db_home", "home_gym", "machine_gym", "full_gym", "bodyweight"]
_SPLITS = ["full_body", "upper_lower", "ppl"]
_DAYS = [2, 3, 4, 5, 6]
_DURS = [20, 30, 45, 60, 75]
_INJ = [[], [], [], ["knee"], [], ["lower_back"], [], ["shoulder"], [], ["wrist"], [], ["hip"], [], ["elbow"]]
_LOCALES = ["ko", "ko", "en", "ja"]


def build_profiles(existing: dict) -> dict:
    out = dict(existing)
    name_i = {"ko": 0, "en": 0, "ja": 0}

    def next_name(loc):
        n = NAMES[loc][name_i[loc] % len(NAMES[loc])]
        name_i[loc] += 1
        return n

    for i, (key, loc, g, age, h, w, exp, goals, split, days, dur, eq, inj) in enumerate(EDGE):
        out[key] = _profile(loc, next_name(loc), g, age, h, w, exp, goals, split, days, dur, eq, inj, PERSONAS[i % 10])

    for i in range(28):
        loc = _LOCALES[i % 4]
        g = "female" if i % 2 else "male"
        age = _AGES[i % 9]
        h = (158 + (i * 7) % 14) if g == "female" else (168 + (i * 5) % 18)
        bmi_target = [21.5, 23.5, 25.5, 27.5, 22.5][i % 5]
        w = round(bmi_target * (h / 100) ** 2)
        exp = _EXPS[(i // 2) % 4]
        goals = _GOALS[i % 7]
        eq = _EQUIPS[(i + 1) % 6]
        split = _SPLITS[(i // 3) % 3]
        days = _DAYS[(i + 2) % 5]
        if split == "ppl" and days < 3:
            days = 3
        dur = _DURS[(i * 3) % 5]
        inj = _INJ[i % 14]
        key = f"g_grid_{i:02d}_{loc}"
        out[key] = _profile(loc, next_name(loc), g, age, h, w, exp, goals, split, days, dur, eq, inj, PERSONAS[(i + 3) % 10])
    return out
