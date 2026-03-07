#!/usr/bin/env python3

import json
import re
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_XLSX = ROOT / "1500+ exercise data.xlsx"
OUTPUT_JSON = ROOT / "src/main/resources/catalog/exercise-catalog.json"

NS = {
    "a": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
}

NAME_SUFFIX_RE = re.compile(r"[_ ]+(female|male)$", re.IGNORECASE)
NON_ALNUM_RE = re.compile(r"[^a-z0-9]+")

KO_NAME_REPLACEMENTS = [
    ("hammer strength", "해머 스트렝스"),
    ("smith machine", "스미스 머신"),
    ("resistance band", "저항 밴드"),
    ("loop resistance band", "루프 저항 밴드"),
    ("cable pulley machine", "케이블 머신"),
    ("lat pull down machine", "랫풀다운 머신"),
    ("pull up bar", "풀업 바"),
    ("exercise ball", "짐볼"),
    ("hyperextension bench", "백익스텐션 벤치"),
    ("jump rope", "줄넘기"),
    ("ab wheel", "AB 휠"),
    ("ab roller", "AB 롤러"),
    ("bench press", "벤치프레스"),
    ("shoulder press", "숄더프레스"),
    ("leg press", "레그프레스"),
    ("leg curl", "레그 컬"),
    ("leg extension", "레그 익스텐션"),
    ("lat pulldown", "랫풀다운"),
    ("pull up", "풀업"),
    ("chin up", "친업"),
    ("push up", "푸시업"),
    ("push-up", "푸시업"),
    ("deadlift", "데드리프트"),
    ("romanian", "루마니안"),
    ("good morning", "굿모닝"),
    ("back extension", "백 익스텐션"),
    ("hip thrust", "힙 쓰러스트"),
    ("glute bridge", "글루트 브리지"),
    ("lateral raise", "레터럴 레이즈"),
    ("front raise", "프론트 레이즈"),
    ("rear delt", "리어 델트"),
    ("face pull", "페이스 풀"),
    ("upright row", "업라이트 로우"),
    ("barbell", "바벨"),
    ("dumbbell", "덤벨"),
    ("kettlebell", "케틀벨"),
    ("cable", "케이블"),
    ("bodyweight", "맨몸"),
    ("body weight", "맨몸"),
    ("machine", "머신"),
    ("squat", "스쿼트"),
    ("lunge", "런지"),
    ("row", "로우"),
    ("curl", "컬"),
    ("tricep", "트라이셉"),
    ("triceps", "트라이셉"),
    ("bicep", "바이셉"),
    ("biceps", "바이셉"),
    ("crunch", "크런치"),
    ("sit up", "싯업"),
    ("sit-up", "싯업"),
    ("plank", "플랭크"),
    ("twist", "트위스트"),
    ("rotation", "로테이션"),
    ("rollout", "롤아웃"),
    ("carry", "캐리"),
    ("farmer", "파머"),
    ("jump", "점프"),
    ("running", "러닝"),
    ("walking", "워킹"),
    ("stretch", "스트레칭"),
    ("pose", "포즈"),
    ("press", "프레스"),
    ("fly", "플라이"),
    ("dip", "딥"),
    ("reverse", "리버스"),
    ("alternating", "얼터네이팅"),
    ("single arm", "싱글 암"),
    ("single leg", "싱글 레그"),
    ("both arms", "양팔"),
    ("both leg", "양다리"),
    ("standing", "스탠딩"),
    ("seated", "시티드"),
    ("lying", "라잉"),
    ("kneeling", "니링"),
    ("side", "사이드"),
    ("forward", "포워드"),
    ("backward", "백워드"),
    ("incline", "인클라인"),
    ("decline", "디클라인"),
    ("normal stance", "노멀 스탠스"),
    ("twisting", "트위스팅"),
    ("hyperextension", "하이퍼익스텐션"),
]

MUSCLE_RULES = [
    (("pectoralis", "chest"), {"CHEST"}),
    (("deltoid", "shoulder"), {"SHOULDERS"}),
    (("triceps",), {"TRICEPS"}),
    (("quadriceps", "quads", "rectus femoris"), {"QUADRICEPS"}),
    (("hamstring", "biceps femoris", "semitendinosus", "semimembranosus"), {"HAMSTRINGS"}),
    (("glute", "buttocks"), {"GLUTES"}),
    (("gastrocnemius", "soleus", "calves", "calf"), {"CALVES"}),
    (("latissimus", "lats"), {"BACK", "LATS"}),
    (("middle back", "upper back", "rhomboid"), {"BACK", "TRAPS"}),
    (("trapezius", "traps"), {"BACK", "TRAPS"}),
    (("teres major",), {"BACK"}),
    (("erector spinae", "lower back"), {"BACK"}),
    (("biceps brachii",), {"BICEPS"}),
    (("brachialis", "brachioradialis", "forearm"), {"FOREARMS"}),
    (("abdominal", "rectus abdominis"), {"ABS", "CORE"}),
    (("oblique", "core"), {"CORE"}),
    (("neck", "sternocleidomastoid", "scalenes"), {"NECK"}),
    (("hip flexor", "iliopsoas"), {"LEGS"}),
    (("adductor", "inner thigh"), {"LEGS"}),
]

BASIC_PATTERNS = {
    "HORIZONTAL_PRESS_BARBELL",
    "HORIZONTAL_PRESS_DUMBBELL",
    "VERTICAL_PRESS_BARBELL",
    "VERTICAL_PRESS_DUMBBELL",
    "SQUAT",
    "DEADLIFT",
    "HIP_HINGE",
    "BARBELL_ROW",
    "DUMBBELL_ROW",
    "LAT_PULLDOWN",
    "PULLUP_CHINUP",
    "LEG_PRESS",
    "LUNGE",
    "PUSHUP",
    "PLANK",
}

ADVANCED_KEYWORDS = (
    "snatch",
    "clean",
    "jerk",
    "muscle up",
    "handstand",
    "planche",
    "lever",
    "pistol squat",
    "turkish get up",
)

SPECIALIZED_KEYWORDS = (
    "stretch",
    "pose",
    "neck",
    "isometric",
    "mobility",
    "warm up",
    "warm-up",
)


def load_shared_strings(archive: zipfile.ZipFile) -> list[str]:
    if "xl/sharedStrings.xml" not in archive.namelist():
        return []

    root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
    values = []
    for node in root.findall("a:si", NS):
        values.append("".join(text.text or "" for text in node.iterfind(".//a:t", NS)))
    return values


def read_rows(path: Path) -> list[list[str | None]]:
    with zipfile.ZipFile(path) as archive:
        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
        relationship_id = workbook.find("a:sheets", NS)[0].attrib[
            "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
        ]
        relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        relationship_map = {node.attrib["Id"]: "xl/" + node.attrib["Target"] for node in relationships}
        worksheet = ET.fromstring(archive.read(relationship_map[relationship_id]))
        shared_strings = load_shared_strings(archive)
        rows = worksheet.find("a:sheetData", NS).findall("a:row", NS)

        def cell_value(cell):
            value_node = cell.find("a:v", NS)
            cell_type = cell.attrib.get("t")
            if cell_type == "s" and value_node is not None:
                return shared_strings[int(value_node.text)]
            if cell_type == "inlineStr":
                return "".join(text.text or "" for text in cell.iterfind(".//a:t", NS))
            return value_node.text if value_node is not None else None

        return [[cell_value(cell) for cell in row.findall("a:c", NS)] for row in rows]


def normalize_name(name: str) -> str:
    normalized = NAME_SUFFIX_RE.sub("", name or "")
    normalized = normalized.replace("_", " ")
    normalized = re.sub(r"\s+", " ", normalized)
    return normalized.strip()


def slugify(value: str) -> str:
    lowered = normalize_name(value).lower()
    lowered = NON_ALNUM_RE.sub("-", lowered)
    return lowered.strip("-")


def split_muscle_text(raw: str | None) -> list[str]:
    if not raw:
        return []

    items = []
    depth = 0
    current = []
    for char in raw:
        if char == "(":
            depth += 1
        elif char == ")" and depth > 0:
            depth -= 1

        if char == "," and depth == 0:
            item = "".join(current).strip()
            if item:
                items.append(item)
            current = []
            continue

        current.append(char)

    tail = "".join(current).strip()
    if tail:
        items.append(tail)

    return items


def map_muscle_groups(primary: str | None, secondary: str | None) -> list[str]:
    groups = set()
    for item in split_muscle_text(primary) + split_muscle_text(secondary):
        lowered = item.lower()
        for keywords, mapped_groups in MUSCLE_RULES:
            if any(keyword in lowered for keyword in keywords):
                groups.update(mapped_groups)

    if "ABS" in groups:
        groups.add("CORE")

    return sorted(groups)


def map_equipment(raw_equipment: str | None) -> tuple[str | None, str | None]:
    if raw_equipment is None:
        return None, None

    value = raw_equipment.strip()
    lowered = value.lower()
    if lowered in {"none", "none (bodyweight)", "bodyweight"}:
        return "BODYWEIGHT", value
    if "dumbbell" in lowered:
        return "DUMBBELL", value
    if "barbell" in lowered or "ez bar" in lowered or "smith" in lowered:
        return "BARBELL", value
    if "kettlebell" in lowered:
        return "KETTLEBELL", value
    if "band" in lowered:
        return "RESISTANCE_BAND", value
    if "cable" in lowered or "pulley" in lowered:
        return "CABLE", value
    if "machine" in lowered or "bench" in lowered:
        return "MACHINE", value
    if "mat" in lowered:
        return "BODYWEIGHT", value
    return "OTHER", value


def derive_category(name: str, muscle_groups: list[str]) -> str:
    lowered = name.lower()
    if re.search(r"\b(running|run|walking|walk|march|cardio)\b", lowered) or "jump rope" in lowered:
        return "CARDIO"
    if any(keyword in lowered for keyword in ("stretch", "pose", "mobility", "warm up", "warm-up")):
        return "FULL_BODY"

    scores = Counter()
    for group in muscle_groups:
        if group in {"CHEST"}:
            scores["CHEST"] += 3
        elif group in {"BACK", "LATS", "TRAPS"}:
            scores["BACK"] += 3
        elif group in {"QUADRICEPS", "HAMSTRINGS", "GLUTES", "CALVES", "LEGS"}:
            scores["LEGS"] += 3
        elif group in {"SHOULDERS"}:
            scores["SHOULDERS"] += 3
        elif group in {"BICEPS", "TRICEPS", "FOREARMS"}:
            scores["ARMS"] += 3
        elif group in {"ABS", "CORE"}:
            scores["CORE"] += 3

    if not scores:
        return "FULL_BODY"

    return scores.most_common(1)[0][0]


def classify_movement_pattern(name: str, equipment: str | None, category: str) -> str:
    lowered = name.lower()

    if any(keyword in lowered for keyword in ("stretch", "pose", "mobility", "yoga")):
        return "STRETCHING"
    if any(keyword in lowered for keyword in ("jump rope", "running", "run", "walk", "march", "side step")):
        return "CARDIO"
    if "jump" in lowered or "burpee" in lowered:
        return "PLYOMETRIC"
    if "bench press" in lowered:
        if "incline" in lowered:
            return "INCLINE_PRESS_DUMBBELL" if equipment == "DUMBBELL" else "INCLINE_PRESS_BARBELL"
        if "decline" in lowered:
            return "DECLINE_PRESS"
        if equipment == "DUMBBELL":
            return "HORIZONTAL_PRESS_DUMBBELL"
        if equipment == "MACHINE":
            return "HORIZONTAL_PRESS_MACHINE"
        return "HORIZONTAL_PRESS_BARBELL"
    if "shoulder press" in lowered or "overhead press" in lowered or "military press" in lowered:
        if equipment == "DUMBBELL":
            return "VERTICAL_PRESS_DUMBBELL"
        if equipment == "MACHINE":
            return "VERTICAL_PRESS_MACHINE"
        return "VERTICAL_PRESS_BARBELL"
    if "deadlift" in lowered:
        return "DEADLIFT"
    if any(keyword in lowered for keyword in ("romanian", "rdl", "good morning", "back extension", "hyperextension")):
        return "HIP_HINGE"
    if "leg press" in lowered:
        return "LEG_PRESS"
    if "leg curl" in lowered or "hamstring curl" in lowered:
        return "LEG_CURL"
    if "leg extension" in lowered:
        return "LEG_EXTENSION"
    if "squat" in lowered:
        if any(keyword in lowered for keyword in ("split", "bulgarian", "curtsy")):
            return "LUNGE"
        return "SQUAT"
    if "lunge" in lowered:
        return "LUNGE"
    if any(keyword in lowered for keyword in ("hip thrust", "glute bridge", "kickback", "donkey kick")):
        return "GLUTE_FOCUSED"
    if "calf" in lowered:
        return "CALF"
    if "pull up" in lowered or "chin up" in lowered or "muscle up" in lowered:
        return "PULLUP_CHINUP"
    if "pulldown" in lowered:
        return "LAT_PULLDOWN"
    if "face pull" in lowered:
        return "FACE_PULL"
    if "upright row" in lowered:
        return "UPRIGHT_ROW"
    if "row" in lowered:
        if equipment == "DUMBBELL" or "single arm row" in lowered:
            return "DUMBBELL_ROW"
        if equipment == "CABLE" or equipment == "MACHINE":
            return "CABLE_ROW"
        return "BARBELL_ROW"
    if "rear delt" in lowered or "reverse fly" in lowered:
        return "REAR_DELT"
    if "lateral raise" in lowered or "side raise" in lowered:
        return "LATERAL_RAISE"
    if "front raise" in lowered:
        return "FRONT_RAISE"
    if "shrug" in lowered:
        return "SHRUG"
    if "curl" in lowered:
        if "leg curl" in lowered or "hamstring curl" in lowered:
            return "LEG_CURL"
        if equipment in {"CABLE", "RESISTANCE_BAND"}:
            return "BICEP_CURL_CABLE"
        if equipment == "DUMBBELL" or "hammer curl" in lowered:
            return "BICEP_CURL_DUMBBELL"
        return "BICEP_CURL_BARBELL"
    if "pushdown" in lowered:
        return "TRICEP_PUSHDOWN"
    if "skull crusher" in lowered or "lying tricep" in lowered:
        return "TRICEP_LYING"
    if "tricep" in lowered or "triceps" in lowered or "extension" in lowered:
        return "TRICEP_OVERHEAD"
    if "dip" in lowered:
        return "DIPS"
    if "push up" in lowered or "push-up" in lowered or "chatarunga" in lowered:
        return "PUSHUP"
    if "fly" in lowered:
        return "FLY"
    if "crunch" in lowered or "sit up" in lowered or "sit-up" in lowered:
        return "CRUNCH"
    if "plank" in lowered:
        return "PLANK"
    if "leg raise" in lowered or "knee raise" in lowered:
        return "LEG_RAISE"
    if any(keyword in lowered for keyword in ("twist", "rotation", "wood chop")):
        return "ROTATION"
    if "ab wheel" in lowered or "rollout" in lowered:
        return "ROLLOUT"
    if "clean" in lowered:
        return "CLEAN"
    if "snatch" in lowered:
        return "SNATCH"
    if "carry" in lowered or "farmer" in lowered:
        return "CARRY"
    if category == "CARDIO":
        return "CARDIO"
    if category == "FULL_BODY":
        return "STRETCHING"
    return "OTHER"


def derive_basic_and_tier(name: str, pattern: str, category: str, equipment: str | None) -> tuple[bool, str]:
    lowered = name.lower()
    basic = pattern in BASIC_PATTERNS and not any(keyword in lowered for keyword in ("single arm", "single leg", "iso-lateral"))
    if any(keyword in lowered for keyword in SPECIALIZED_KEYWORDS) or pattern in {"STRETCHING", "CARDIO"} or category == "FULL_BODY":
        return False, "SPECIALIZED"
    if any(keyword in lowered for keyword in ADVANCED_KEYWORDS) or pattern in {"CLEAN", "SNATCH"}:
        return False, "ADVANCED"
    if basic:
        return True, "ESSENTIAL"
    if equipment == "OTHER" and category in {"FULL_BODY", "CARDIO"}:
        return False, "SPECIALIZED"
    return False, "STANDARD"


def derive_difficulty(name: str, pattern: str, equipment: str | None, tier: str) -> int:
    lowered = name.lower()
    if tier == "SPECIALIZED":
        if pattern == "STRETCHING":
            return 15
        if pattern == "CARDIO":
            return 25
        return 45
    if tier == "ADVANCED":
        return 75
    if pattern in {"CLEAN", "SNATCH"}:
        return 85
    if equipment == "MACHINE":
        return 25
    if equipment == "BODYWEIGHT":
        if any(keyword in lowered for keyword in ("handstand", "lever", "planche", "muscle up")):
            return 80
        return 35
    if equipment == "BARBELL":
        return 55
    if equipment in {"DUMBBELL", "KETTLEBELL"}:
        return 45
    if equipment in {"CABLE", "RESISTANCE_BAND"}:
        return 35
    return 40


def derive_popularity(pattern: str, tier: str, basic: bool) -> int:
    if basic:
        if pattern in {"SQUAT", "DEADLIFT", "HORIZONTAL_PRESS_BARBELL"}:
            return 95
        return 85
    if tier == "STANDARD":
        return 65
    if tier == "ADVANCED":
        return 45
    if pattern == "CARDIO":
        return 50
    return 20


def translate_name_to_ko(name: str) -> str:
    translated = normalize_name(name).lower()
    for source, target in sorted(KO_NAME_REPLACEMENTS, key=lambda item: len(item[0]), reverse=True):
        translated = translated.replace(source, target)
    translated = translated.replace("  ", " ").strip()
    translated = re.sub(r"\bdegree\b", "도", translated)
    return translated


def build_catalog(rows: list[list[str | None]]) -> list[dict]:
    merged = {}
    for values in rows[1:]:
        if len(values) != 7:
            continue
        source_category, raw_name, instructions, tips, primary, secondary, raw_equipment = values
        if not source_category or not raw_name or not instructions or not primary:
            continue

        canonical_name = normalize_name(raw_name)
        slug = slugify(canonical_name)
        if not slug:
            continue

        muscle_groups = map_muscle_groups(primary, secondary)
        category = derive_category(canonical_name, muscle_groups)
        equipment, equipment_detail = map_equipment(raw_equipment)
        movement_pattern = classify_movement_pattern(canonical_name, equipment, category)
        basic_exercise, tier = derive_basic_and_tier(canonical_name, movement_pattern, category, equipment)
        difficulty = derive_difficulty(canonical_name, movement_pattern, equipment, tier)
        popularity = derive_popularity(movement_pattern, tier, basic_exercise)

        record = merged.setdefault(
            slug,
            {
                "slug": slug,
                "defaultLocale": "en",
                "category": category,
                "equipment": equipment,
                "equipmentDetail": equipment_detail,
                "sourceCategory": source_category,
                "movementPattern": movement_pattern,
                "muscleGroups": set(muscle_groups),
                "recommendationTier": tier,
                "popularity": popularity,
                "difficulty": difficulty,
                "basicExercise": basic_exercise,
                "translations": {
                    "en": {
                        "locale": "en",
                        "name": canonical_name,
                        "instructions": instructions.strip() if instructions else None,
                        "tips": tips.strip() if tips else None,
                    },
                    "ko": {
                        "locale": "ko",
                        "name": translate_name_to_ko(canonical_name),
                        "instructions": None,
                        "tips": None,
                    },
                },
            },
        )

        record["muscleGroups"].update(muscle_groups)
        record["basicExercise"] = record["basicExercise"] or basic_exercise
        tier_priority = {"SPECIALIZED": 0, "ADVANCED": 1, "STANDARD": 2, "ESSENTIAL": 3}
        if tier_priority[tier] > tier_priority[record["recommendationTier"]]:
            record["recommendationTier"] = tier
        record["difficulty"] = max(record["difficulty"], difficulty)
        record["popularity"] = max(record["popularity"], popularity)

        if len(canonical_name) < len(record["translations"]["en"]["name"]):
            record["translations"]["en"]["name"] = canonical_name
            record["translations"]["ko"]["name"] = translate_name_to_ko(canonical_name)
        if instructions and len(instructions) > len(record["translations"]["en"].get("instructions") or ""):
            record["translations"]["en"]["instructions"] = instructions.strip()
        if tips and len(tips) > len(record["translations"]["en"].get("tips") or ""):
            record["translations"]["en"]["tips"] = tips.strip()

        if category != "FULL_BODY" and record["category"] == "FULL_BODY":
            record["category"] = category
        if record["equipment"] is None and equipment is not None:
            record["equipment"] = equipment
        if not record["movementPattern"] or record["movementPattern"] == "OTHER":
            record["movementPattern"] = movement_pattern

    catalog = []
    for record in merged.values():
        catalog.append(
            {
                "slug": record["slug"],
                "defaultLocale": record["defaultLocale"],
                "category": record["category"],
                "equipment": record["equipment"],
                "equipmentDetail": record["equipmentDetail"],
                "sourceCategory": record["sourceCategory"],
                "movementPattern": record["movementPattern"],
                "muscleGroups": sorted(record["muscleGroups"]),
                "recommendationTier": record["recommendationTier"],
                "popularity": record["popularity"],
                "difficulty": record["difficulty"],
                "basicExercise": record["basicExercise"],
                "translations": [
                    record["translations"]["en"],
                    record["translations"]["ko"],
                ],
            }
        )

    return sorted(catalog, key=lambda item: item["slug"])


def main() -> None:
    rows = read_rows(SOURCE_XLSX)
    catalog = build_catalog(rows)
    OUTPUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT_JSON.open("w", encoding="utf-8") as handle:
        json.dump(catalog, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    print(f"wrote {len(catalog)} exercises to {OUTPUT_JSON}")


if __name__ == "__main__":
    main()
