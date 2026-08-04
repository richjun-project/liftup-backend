#!/usr/bin/env python3
"""
LiftUp AI 인스타그램 콘텐츠 생성기.

하루 3슬롯 구조:
  A (아침 08:00)  트레이너 동기부여 — 캐릭터 이미지 + 페르소나 말투 한마디
  B (점심 12:30)  운동 지식 카드 — 운동 카탈로그 기반 팁
  C (저녁 19:00)  앱/개발 스토리 — 기능 소개·개발 비하인드

캡션·카피는 Gemini(백엔드와 같은 키)로 생성하고, 이미지는 트레이너 PNG 에
텍스트를 오버레이한 1080×1350(4:5) 카드로 만든다. 결과는 queue/ 에
{date}_{slot}.json + .jpg 로 쌓이고, post_to_instagram.py 가 하나씩 게시한다.

사용법:
  python3 generate_posts.py --days 7          # 오늘부터 7일치(21개) 생성
  python3 generate_posts.py --days 1 --start 2026-08-06
"""
import argparse
import json
import os
import random
import re
import sys
import textwrap
from datetime import date, timedelta
from pathlib import Path

import requests
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent
BACKEND = ROOT.parent.parent          # ~/develop/liftupai
FRONTEND = Path.home() / "AndroidStudioProjects" / "liftupai"
QUEUE = ROOT / "queue"

FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
W, H = 1080, 1350                     # 4:5 — 피드 점유 최대
BRAND_BLUE = (10, 132, 255)
BRAND_PURPLE = (94, 92, 230)
DARK = (12, 14, 20)

TRAINERS = {
    "spartan":        {"name": "스파르타",     "color": (244, 67, 54)},
    "burnout":        {"name": "번아웃 김PT",  "color": (120, 120, 120)},
    "game_master":    {"name": "게임 마스터",  "color": (156, 39, 176)},
    "hip_hop":        {"name": "힙합 트레이너", "color": (255, 152, 0)},
    "influencer":     {"name": "인플루언서",   "color": (233, 30, 99)},
    "la_korean":      {"name": "LA 교포",      "color": (33, 150, 243)},
    "office_master":  {"name": "오피스 마스터", "color": (0, 150, 136)},
    "retired_teacher": {"name": "은퇴 선생님",  "color": (121, 85, 72)},
    "soldier":        {"name": "군인",         "color": (76, 175, 80)},
    "busan_veteran":  {"name": "부산 고인물",   "color": (63, 81, 181)},
}

HASHTAGS = "#리프트업AI #LiftUpAI #AI트레이너 #헬스 #운동루틴 #헬스타그램 #오운완 #운동기록 #홈트 #헬린이"


# ── Gemini ───────────────────────────────────────────────────────────────────

def gemini_key() -> str:
    env = (BACKEND / ".env").read_text()
    m = re.search(r"^GEMINI_API_KEY=(.+)$", env, re.M)
    if not m:
        sys.exit("GEMINI_API_KEY not found in backend .env")
    return m.group(1).strip()


def gemini(prompt: str, key: str) -> str:
    r = requests.post(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent",
        params={"key": key},
        json={"contents": [{"parts": [{"text": prompt}]}],
              "generationConfig": {"temperature": 0.9}},
        timeout=60,
    )
    r.raise_for_status()
    return r.json()["candidates"][0]["content"]["parts"][0]["text"].strip()


def gemini_json(prompt: str, key: str) -> dict:
    raw = gemini(prompt + "\n\nJSON only. No markdown fences.", key)
    raw = re.sub(r"^```(json)?|```$", "", raw.strip(), flags=re.M).strip()
    return json.loads(raw)


# ── 소재 로더 ─────────────────────────────────────────────────────────────────

def load_personas() -> dict:
    """persona_scripts.md 에서 트레이너별 대표 대사·말투 발췌"""
    text = (FRONTEND / "assets" / "trainers" / "persona_scripts.md").read_text()
    sections = re.split(r"^## \d+\. ", text, flags=re.M)[1:]
    out = {}
    for sec in sections:
        m = re.search(r"\*\*ID:\*\* `(\w+)`", sec)
        if not m:
            continue
        tid = m.group(1)
        quotes = re.findall(r'^- "(.+)"$', sec, re.M)
        tone = ""
        tm = re.search(r"### 말투 & 성격\n(.*?)\n###", sec, re.S)
        if tm:
            tone = tm.group(1).strip()[:400]
        out[tid] = {"quotes": quotes[:6], "tone": tone}
    return out


def load_exercises() -> list:
    cat = json.loads((BACKEND / "src/main/resources/catalog/exercise-catalog.json").read_text())
    pool = [e for e in cat if e.get("recommendationTier") == "ESSENTIAL"]

    def ko(e):
        for t in e["translations"]:
            if t["locale"] == "ko":
                return t
        return e["translations"][0]

    return [{
        "slug": e["slug"], "name": ko(e)["name"],
        "category": e["category"], "equipment": e.get("equipment"),
        "tips": (ko(e).get("tips") or "")[:600],
        "instructions": (ko(e).get("instructions") or "")[:600],
    } for e in pool]


DEV_TOPICS = [
    "AI가 하루 운동에 어시스트 풀업만 6종류를 넣었던 버그를 고친 이야기 (ORDER BY 누락)",
    "운동 1295개의 부위 분류를 전수 조사해서 216개 오분류를 고친 이야기",
    "초보자에게 바벨 운동이 하나도 추천되지 않던 필터 버그",
    "AI 트레이너 10명의 성격을 만들 때 고민한 것들",
    "운동 안 하면 AI가 알림을 보내는 기능의 설계 고민",
    "체지방 감량 목표면 고립운동을 2세트로 주는 이유 (운동과학)",
    "혼자 만드는 앱이라 유저 한 명 한 명의 버그 제보가 소중한 이유",
    "카카오 로그인에서 이메일 동의 안 하면 앱 전체가 403 났던 대형 사고",
    "AI가 지어낸 가짜 운동을 걸러내는 환각 방지 장치",
    "운동 추천에 벡터 검색을 쓰는 이유와 한계",
    "주기화(디로드 주간)를 앱이 자동으로 잡아주는 원리",
    "무게 추천이 매번 2.5kg 단위로 떨어지는 이유",
    "여성 사용자의 증량 폭을 다르게 잡는 운동과학적 근거",
    "앱 이름을 LiftUp AI 로 지은 이유",
]


# ── 이미지 렌더 ───────────────────────────────────────────────────────────────

def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT, size, index=5 if bold else 2)


def gradient_bg(c1, c2) -> Image.Image:
    img = Image.new("RGB", (W, H))
    for y in range(H):
        t = y / H
        img.paste(tuple(int(a + (b - a) * t) for a, b in zip(c1, c2)), (0, y, W, y + 1))
    return img


def draw_wrapped(d: ImageDraw.ImageDraw, text: str, xy, fnt, fill, max_chars, line_gap=14, anchor_center=False):
    x, y = xy
    for raw_line in text.split("\n"):
        for line in textwrap.wrap(raw_line, max_chars) or [""]:
            if anchor_center:
                d.text((W // 2, y), line, font=fnt, fill=fill, anchor="ma")
            else:
                d.text((x, y), line, font=fnt, fill=fill)
            y += fnt.size + line_gap
    return y


def add_footer(img: Image.Image):
    d = ImageDraw.Draw(img)
    logo = Image.open(FRONTEND / "assets/images/logo_icon.png").convert("RGBA").resize((64, 64))
    img.paste(logo, (40, H - 104), logo)
    d.text((120, H - 92), "LiftUp AI", font=font(34, bold=True), fill=(255, 255, 255))
    d.text((W - 40, H - 88), "AI 퍼스널 트레이너", font=font(26), fill=(180, 185, 200), anchor="ra")


def render_trainer_card(trainer_id: str, quote: str, sub: str) -> Image.Image:
    color = TRAINERS[trainer_id]["color"]
    img = gradient_bg(DARK, tuple(max(0, c - 160) for c in color))
    d = ImageDraw.Draw(img)
    # 트레이너 이름 뱃지
    name = TRAINERS[trainer_id]["name"]
    d.rounded_rectangle((40, 60, 40 + 44 + len(name) * 40, 130), 18, fill=color)
    d.text((62, 74), name, font=font(40, bold=True), fill=(255, 255, 255))
    # 인용부호 + 대사 (상단 텍스트 존: ~560px 까지만 사용해 캐릭터와 분리)
    d.text((36, 150), "“", font=font(150, bold=True), fill=color)
    y = draw_wrapped(d, quote, (44, 290), font(62, bold=True), (255, 255, 255), 14, line_gap=20)
    # 캐릭터 (하단 우측) — 텍스트가 길면 그만큼 내려서 겹침 방지
    ch_top = max(H - 820, y + 40)
    ch_size = min(820, H - ch_top - 40)
    ch = Image.open(FRONTEND / f"assets/trainers/upload/{trainer_id}.png").convert("RGBA")
    ch = ch.resize((ch_size, ch_size))
    img.paste(ch, (W - ch_size + 60, ch_top), ch)
    # 서브 문구는 왼쪽 절반 폭만 사용 (캐릭터와 안 겹치게)
    draw_wrapped(d, sub, (44, ch_top + 90), font(34), (205, 210, 220), 13)
    add_footer(img)
    return img


def render_knowledge_card(title: str, lines: list, tag: str) -> Image.Image:
    img = gradient_bg((16, 18, 28), (24, 34, 66))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((40, 70, 40 + 44 + len(tag) * 34, 134), 16, fill=BRAND_BLUE)
    d.text((62, 84), tag, font=font(34, bold=True), fill=(255, 255, 255))
    y = draw_wrapped(d, title, (44, 190), font(72, bold=True), (255, 255, 255), 12, line_gap=20)
    y += 40
    for i, line in enumerate(lines, 1):
        d.ellipse((44, y + 8, 44 + 46, y + 54), fill=BRAND_PURPLE)
        d.text((44 + 23, y + 12), str(i), font=font(30, bold=True), fill=(255, 255, 255), anchor="ma")
        y = draw_wrapped(d, line, (116, y), font(40), (225, 228, 235), 22, line_gap=16) + 26
    add_footer(img)
    return img


def render_dev_card(title: str, body: str) -> Image.Image:
    img = gradient_bg((10, 12, 16), (30, 22, 52))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((40, 70, 380, 134), 16, fill=BRAND_PURPLE)
    d.text((62, 84), "개발 비하인드", font=font(34, bold=True), fill=(255, 255, 255))
    # 터미널 느낌 헤더
    d.rounded_rectangle((40, 180, W - 40, 300), 20, fill=(24, 26, 34))
    for i, c in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
        d.ellipse((70 + i * 44, 210, 98 + i * 44, 238), fill=c)
    d.text((70, 250), "~/liftup-ai $", font=font(28), fill=(120, 220, 150))
    y = draw_wrapped(d, title, (44, 350), font(62, bold=True), (255, 255, 255), 14, line_gap=20)
    draw_wrapped(d, body, (44, y + 36), font(38), (210, 214, 224), 24, line_gap=18)
    add_footer(img)
    return img


# ── 슬롯 생성 ─────────────────────────────────────────────────────────────────

def make_slot_a(key, personas, rng) -> tuple:
    tid = rng.choice(list(TRAINERS))
    p = personas.get(tid, {"quotes": [], "tone": ""})
    data = gemini_json(f"""헬스 앱 'LiftUp AI'의 AI 트레이너 캐릭터 '{TRAINERS[tid]['name']}' 인스타 포스트.

말투/성격: {p['tone']}
기존 대표 대사: {p['quotes']}

이 캐릭터 말투로:
1. quote: 아침에 보고 운동 가고 싶어지는 새로운 한마디 (25자 이내, 기존 대사 재활용 금지)
2. sub: 한 줄 부연 (30자 이내, 같은 말투)
3. caption: 인스타 캡션 2~3문장. 캐릭터 말투 유지, 마지막에 앱에서 이 트레이너와 대화할 수 있다는 언급을 자연스럽게.

{{"quote": "...", "sub": "...", "caption": "..."}}""", key)
    img = render_trainer_card(tid, data["quote"], data["sub"])
    caption = f"{data['caption']}\n\n{HASHTAGS} #{TRAINERS[tid]['name'].replace(' ', '')}"
    return img, caption, f"trainer:{tid}"


def make_slot_b(key, exercises, rng) -> tuple:
    ex = rng.choice(exercises)
    data = gemini_json(f"""헬스 인스타 정보 카드 콘텐츠.

운동: {ex['name']} (부위 {ex['category']}, 장비 {ex['equipment']})
수행법: {ex['instructions']}
팁: {ex['tips']}

1. title: 카드 제목 (호기심 유발형, 20자 이내. 예: "랫풀다운, 팔로 당기고 있죠?")
2. points: 핵심 포인트 3개 (각 34자 이내, 초보자 눈높이)
3. caption: 캡션 3~4문장 + 마지막에 "LiftUp AI가 세트·무게까지 잡아드려요" 뉘앙스 한 줄.

{{"title": "...", "points": ["...","...","..."], "caption": "..."}}""", key)
    img = render_knowledge_card(data["title"], data["points"], ex["name"][:14])
    return img, f"{data['caption']}\n\n{HASHTAGS} #운동팁 #자세교정", f"exercise:{ex['slug']}"


def make_slot_c(key, rng, used_topics) -> tuple:
    topic = rng.choice([t for t in DEV_TOPICS if t not in used_topics] or DEV_TOPICS)
    used_topics.add(topic)
    data = gemini_json(f"""1인 개발자가 만드는 헬스 앱 'LiftUp AI'의 개발 비하인드 인스타 포스트.

주제: {topic}

톤: 솔직하고 투박한 1인 개발자. 과장 금지, 전문용어는 한 줄 안에서 풀어서.
1. title: 카드 제목 (25자 이내, 흥미 유발)
2. body: 카드 본문 3~4문장 (140자 이내)
3. caption: 캡션 4~5문장. 스토리텔링. 마지막에 "피드백 주시면 다음 업데이트에 반영해요" 뉘앙스.

{{"title": "...", "body": "...", "caption": "..."}}""", key)
    img = render_dev_card(data["title"], data["body"])
    return img, f"{data['caption']}\n\n{HASHTAGS} #1인개발 #개발일지 #인디앱", f"dev:{topic[:30]}"


# ── 메인 ─────────────────────────────────────────────────────────────────────

SLOT_TIMES = {"a": "08:00", "b": "12:30", "c": "19:00"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=1)
    ap.add_argument("--start", default=date.today().isoformat())
    ap.add_argument("--seed", type=int, default=None)
    args = ap.parse_args()

    key = gemini_key()
    personas = load_personas()
    exercises = load_exercises()
    rng = random.Random(args.seed)
    used_topics = set()
    start = date.fromisoformat(args.start)

    QUEUE.mkdir(exist_ok=True)
    made = 0
    for day in range(args.days):
        d = start + timedelta(days=day)
        for slot in ("a", "b", "c"):
            stem = f"{d.isoformat()}_{slot}"
            if (QUEUE / f"{stem}.json").exists():
                print(f"skip {stem} (exists)")
                continue
            try:
                if slot == "a":
                    img, caption, src = make_slot_a(key, personas, rng)
                elif slot == "b":
                    img, caption, src = make_slot_b(key, exercises, rng)
                else:
                    img, caption, src = make_slot_c(key, rng, used_topics)
            except Exception as e:
                print(f"FAIL {stem}: {e}")
                continue
            img.convert("RGB").save(QUEUE / f"{stem}.jpg", quality=92)
            (QUEUE / f"{stem}.json").write_text(json.dumps({
                "date": d.isoformat(), "slot": slot, "time": SLOT_TIMES[slot],
                "caption": caption, "source": src, "image": f"{stem}.jpg",
            }, ensure_ascii=False, indent=2))
            made += 1
            print(f"made {stem}  [{src}]")
    print(f"\n{made} posts queued in {QUEUE}")


if __name__ == "__main__":
    main()
