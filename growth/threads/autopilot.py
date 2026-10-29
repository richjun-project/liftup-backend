# -*- coding: utf-8 -*-
"""리프트업 AI SNS 오토파일럿 — 매일 과거 반응을 학습해 새 콘텐츠를 생성·게시한다.

동작 (EC2 cron, KST 11:00):
  1. history.jsonl에서 과거 게시물(내용 + 실험 변수 + 조회수/반응)을 읽는다
  2. Gemini에게 성과 분석과 함께 '오늘의 게시물'을 새로 생성시킨다
     - 매일 실험 변수 1개를 바꿔가며(후킹 스타일/주제/포맷/CTA) 무엇이 터지는지 탐색
  3. Pillow로 카드 이미지를 렌더링해 uploads/marketing/daily/에 저장
  4. Threads 게시 (인스타는 2026-08-30부터 중단, ENABLE_INSTAGRAM=1로 재개 가능)
  5. 게시 기록을 history.jsonl에 남긴다 (다음날 collect_metrics.py가 지표를 채움)

Gemini 실패 시 content.json 로테이션으로 폴백 (post_social.py 로직 재사용).

필요 환경변수: IG_ACCESS_TOKEN, THREADS_ACCESS_TOKEN, GEMINI_API_KEY, CARD_BASE_URL

일본어 실험(2026-10-29~): `python3 autopilot.py --lang ja` — 같은 계정에 일본어 텍스트 글을 올린다.
실제 다운로드가 일본(ChatGPT 유입)에서만 나오고 한국어 글은 평균 조회 ~70 이라, 일본 Threads 에서 도달을 잰다.
기록은 history_ja.jsonl(한국어 학습과 섞지 않음), 댓글 링크는 App Store JP(ct=threads-ja 로 다운로드 집계).
`--dry-run` 은 생성·팩트체크까지만 하고 게시·기록하지 않는다.
"""
import json
import os
import sys
import time
import datetime
import textwrap
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
LANG = sys.argv[sys.argv.index("--lang") + 1] if "--lang" in sys.argv else "ko"
DRY_RUN = "--dry-run" in sys.argv
HISTORY = os.path.join(HERE, "history.jsonl" if LANG == "ko" else f"history_{LANG}.jsonl")
UPLOAD_DIR = os.path.expanduser("~/liftup-backend/uploads/marketing/daily")
IG_GRAPH = "https://graph.instagram.com/v21.0"
TH_GRAPH = "https://graph.threads.net/v1.0"
GEMINI_MODEL = "gemini-3.7-flash"

BRAND = {
    "name": "리프트업 AI",
    "landing": "https://liftupai.net",
    # 2026-10-29: App Store 설명 새 판과 같은 사실로 — 기록을 확인하고 답하고, 말하면 플랜·알림이 실제로 바뀐다
    "desc": "내 운동 기록을 먼저 확인하고 답하는 AI 퍼스널 트레이너 앱 — 말하면 플랜의 운동·알림 시간이 실제로 바뀌고, "
            "알려준 부상·취향을 기억한다. 말투가 다른 AI 트레이너 10명(무료 4명)",
}
BRAND_JA = {
    "name": "LIFTUP AIパーソナルトレーナー",
    "landing": "https://apps.apple.com/jp/app/id6752910787?pt=126107754&ct=threads-ja&mt=8",
    "desc": "トレーニング記録を確認してから答えるAIパーソナルトレーナーアプリ。"
            "「レッグプレスの代わりに別の種目にして」と頼めばプランの種目が実際に入れ替わり、"
            "伝えたケガや好みを覚えて次の提案に反映する。話し方の違うAIトレーナー10人（無料で4人）",
}
KST = datetime.timezone(datetime.timedelta(hours=9))

# 인스타그램 게시 중단 (2026-08-30): 20개 게시에 조회·도달·팔로워 모두 0으로
# 알고리즘 도달이 전혀 나오지 않아 Threads에 집중한다.
# 되살리려면 EC2 marketing/.env 에 ENABLE_INSTAGRAM=1 을 넣으면 된다.
INSTAGRAM_ENABLED = os.environ.get("ENABLE_INSTAGRAM", "0") == "1"


def env(name):
    v = os.environ.get(name)
    if not v:
        sys.exit(f"환경변수 {name} 필요")
    return v


def call(base, path, params, token, method="POST"):
    if method == "GET":
        q = urllib.parse.urlencode({**params, "access_token": token})
        req = urllib.request.Request(f"{base}/{path}?{q}")
    else:
        data = urllib.parse.urlencode({**params, "access_token": token}).encode()
        req = urllib.request.Request(f"{base}/{path}", data=data, method="POST")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


# ---------- history ----------

def load_history():
    if not os.path.exists(HISTORY):
        return []
    with open(HISTORY, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


def append_history(rec):
    with open(HISTORY, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


# ---------- Gemini ----------

PRODUCT_SLIDES = {
    "trainer": ("성격이 다른 AI 트레이너 캐릭터 소개 카드 (이름·컨셉·해시태그가 보이는 앱 화면)", 3),
    "routine": ("AI가 짜주는 오늘의 운동 루틴 화면", 3),
    "exercise": ("운동 자세/종목 가이드 화면", 4),
}


def product_slide_for(date_str):
    """6일마다 앱 쇼케이스 데이(10-22 3일→6일: 앱기능 글 평균 조회 31 vs 공감 194) — 실제 앱 화면 슬라이드를 로테이션으로 반환. 아니면 None."""
    ordinal = datetime.date.fromisoformat(date_str).toordinal()
    if ordinal % 6 != 0:
        return None
    kinds = list(PRODUCT_SLIDES.keys())
    kind = kinds[(ordinal // 3) % len(kinds)]
    desc, max_slide = PRODUCT_SLIDES[kind]
    day = (ordinal // 9) % 3 + 1
    slide = (ordinal // 27) % max_slide + 1
    return {"file": f"product/day{day}_{kind}_slide{slide}.png", "kind": kind, "desc": desc}


def gemini_generate(history, feedback=None, product=None):
    recent = history[-21:]
    perf_lines = []
    for h in recent:
        m = h.get("metrics", {})
        perf_lines.append(json.dumps({
            "date": h["date"], "title": h["content"]["title"],
            "incident": h.get("incident"),
            "hook_style": h.get("experiment", {}).get("hook_style"),
            "topic": h.get("experiment", {}).get("topic"),
            "threads_format": h.get("experiment", {}).get("threads_format"),
            "ig_views": m.get("ig", {}).get("views"), "ig_likes": m.get("ig", {}).get("likes"),
            "ig_reach": m.get("ig", {}).get("reach"), "ig_saved": m.get("ig", {}).get("saved"),
            "th_views": m.get("threads", {}).get("views"), "th_likes": m.get("threads", {}).get("likes"),
            "th_replies": m.get("threads", {}).get("replies"),
        }, ensure_ascii=False))

    prompt = f"""너는 {BRAND['name']}의 SNS 그로스 마케터다.
앱 소개: {BRAND['desc']}. 랜딩: {BRAND['landing']}. 타깃: 한국 20~30대 헬스/홈트족.

## 지난 게시물 성과 기록 (JSON lines, 지표 null이면 아직 미집계)
{chr(10).join(perf_lines) if perf_lines else '(아직 기록 없음 — 첫 실험이다)'}

## 임무
위 기록을 분석해 무엇이 반응이 좋았는지 판단하고, 오늘 올릴 게시물 1개를 '새로' 만들어라.
- 매일 실험 변수를 하나씩 바꿔서 탐색하라: hook_style(질문형/공감형/충격형/숫자형/스토리형),
  topic(운동팁/식단/회복/동기부여/앱기능/헬스밈/실패공감), threads_format(image/text),
  cta(프로필링크/댓글링크/질문유도/없음)
- 성과가 좋은 조합은 더 자주, 나쁜 조합은 변형해서 재실험. 최근 7일과 같은 title 금지.
- 단, 최근 3일과 완전히 같은 (hook_style, topic) 조합은 금지 — 잘 되는 조합도 변수 하나는 바꿔 탐색하라.
- Threads 플랫폼 규칙 (중요):
  * threads_text에 URL/링크 절대 금지 — 본문에 외부 링크가 있으면 노출이 급감한다. 링크는 댓글이 담당한다.
  * 밈/공감/대화체가 잘 터진다. 질문으로 끝내 답글을 유도하라.
  * 본문 게시 후 '스레드에 추가'(댓글 체인)로 부가 내용을 이어붙이면 본문 조회수가 오른다.
    threads_reply에 본문을 잇는 댓글 내용(부가 팁 요약/리스트, 자연스러운 앱 언급)을 써라. 링크는 시스템이 붙이니 쓰지 마라.
- 현재 인스타그램 게시는 중단됨(도달 0). Threads 성과만 보고 판단하라. ig_caption은 형식상 채우되 짧게.
- 과장·허위 효능 금지. 의학적 단정 금지.
- 정보성 주장 규칙 (중요 — 위반 시 반려된다):
  * 구식 상식 금지: 운동과학은 계속 바뀐다. "한 끼 단백질 흡수 20~30g 한계", "공복 유산소가 지방을 더 태운다",
    "근육통=성장" 같은 반박된 옛날 정보를 사실처럼 쓰지 마라.
  * 구체적 수치("2배 이득", "80%가 실패")는 널리 합의된 경우만. 애매하면 수치 대신 방향성으로 말해라.
  * 단정 대신 "~로 알려져 있어요", "최근 연구들은 ~쪽이에요" 톤. 논쟁 중인 주제는 논쟁 중임을 인정하는 게 오히려 신뢰를 얻는다.

{f'## 이전 시도에 대한 피드백 (반드시 반영)\n{feedback}\n' if feedback else ''}
{f'''## 오늘은 앱 쇼케이스 데이 (반드시 따를 것)
- topic은 "앱기능"으로 고정. threads_format은 "image"로 고정.
- 첨부될 이미지는 텍스트 카드가 아니라 실제 앱 화면이다: {product["desc"]}
- 본문은 이 화면이 해주는 경험을 보여주는 스토리로 써라 (예: "내 AI 트레이너가 이렇게 잔소리함 ㅋㅋ").
  기능 나열이 아니라 사용 장면. 팁 공유 톤이 아니라 자랑/공유 톤.
''' if product else ''}
## 출력 (JSON만, 다른 텍스트 금지)
{{
 "analysis": "지난 성과에서 배운 것 한두 문장",
 "experiment": {{"hook_style": "...", "topic": "...", "threads_format": "image|text", "cta": "..."}},
 "card": {{"eyebrow": "카드 상단 소제목(10자 내)", "title": "카드 제목(8~18자, 후킹)", "body": "카드 본문(40~70자)"}},
 "ig_caption": "인스타 캡션 전체(본문+CTA+해시태그 8~12개, 이모지 적당히)",
 "threads_text": "Threads 본문(대화체, 400자 이내, 링크 금지, 질문으로 마무리)",
 "threads_reply": "본문에 이어붙일 댓글(부가 팁/정리 + 자연스러운 앱 한 줄, 링크 금지, 200자 이내)"
}}"""

    if LANG == "ja":
        prompt = ja_prompt(perf_lines, feedback)

    body = json.dumps({
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"response_mime_type": "application/json", "temperature": 0.9},
    }).encode()
    url = (f"https://generativelanguage.googleapis.com/v1beta/models/"
           f"{GEMINI_MODEL}:generateContent?key={env('GEMINI_API_KEY')}")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=90) as r:
        resp = json.loads(r.read())
    text = resp["candidates"][0]["content"]["parts"][0]["text"]
    out = json.loads(text)
    for k in ("experiment", "card", "ig_caption", "threads_text", "threads_reply"):
        assert k in out, f"Gemini 출력에 {k} 없음"
    # 링크 금지 규칙 강제 (본문 노출 급감 방지)
    for k in ("threads_text", "threads_reply"):
        out[k] = out[k].replace("https://", "").replace("http://", "").replace("liftupai.net", "").rstrip()
    return out


def ja_prompt(perf_lines, feedback):
    """일본어 실험 글. 한국어에서 조회가 잘 나온 실패공감·헬스밈 결을 일본 筋トレ あるある 로 옮기고, 텍스트 글만 쓴다."""
    return f"""あなたは{BRAND_JA['name']}の日本向けThreads担当のグロースマーケターだ。
アプリ紹介: {BRAND_JA['desc']}。
ターゲット: 日本の20〜30代、ジム通い・宅トレをしている人、パーソナルジムに興味はあるが高くて迷っている人。

## 過去の投稿の成果（JSON lines、指標がnullならまだ未集計）
{chr(10).join(perf_lines) if perf_lines else '(まだ記録なし — 最初の実験)'}

## 任務
成果を分析し、今日の投稿を1つ「新しく」作れ。
- 実験変数を毎回1つずつ変えて探索する: hook_style(質問型/共感型/数字型/ストーリー型),
  topic(筋トレあるある/続かない悩み/ジム初心者/食事/回復/パーソナルジム/アプリ), cta(質問誘導/なし)
- 韓国語アカウントで反応が良かったのは「失敗への共感」と「筋トレあるある」、悪かったのは機能紹介。アプリの話は返信（スレッド）の一言だけにする。
- 最近7日と同じtitle禁止。最近3日と同じ(hook_style, topic)の組み合わせ禁止。
- 日本のThreadsで自然に読める口語の日本語で書く。翻訳調・韓国語の直訳・不自然な絵文字の多用は禁止。改行を使って読みやすく。
- threads_textにURL・リンク禁止（本文にリンクがあると表示が減る）。最後は質問で終えて返信を促す。
- threads_replyは本文の続き（補足のコツや整理）＋アプリの自然な一言。リンクはシステムが付けるので書かない。
- アプリについて書いてよい事実はこれだけ: 答える前にトレーニング記録（最近のトレーニング・種目ごとの重量推移・回復状態）を確認する /
  「〇〇の代わりに別の種目にして」と頼むとプランの種目が実際に入れ替わる / 「通知を19時に」でトレーニング通知の時間が変わる /
  伝えたケガや避けたい種目を覚えて次の提案に反映する / 話し方の違うAIトレーナーを選べる。
  「重量や回数を自動で調整」「その場でメニューを全部組み直す」「その日の体調を判断する」など、これ以外の機能は書かない。
- 誇張・虚偽の効能禁止、医学的な断定禁止。古い常識（1食のタンパク質吸収上限20〜30g、空腹有酸素が脂肪をより燃やす、筋肉痛＝成長、部分痩せ等）を事実のように書かない。
- 具体的な数値は広く合意されたものだけ。迷ったら方向性で言う。料金・価格には触れない。
- 投稿はテキストのみ（画像なし）。cardとig_captionは形式上短く埋める。

{f'## 前回の試みへのフィードバック（必ず反映）{chr(10)}{feedback}{chr(10)}' if feedback else ''}
## 出力（JSONのみ、他のテキスト禁止）
{{
 "analysis": "過去の成果から学んだこと1〜2文（韓国語で）",
 "experiment": {{"hook_style": "...", "topic": "...", "threads_format": "text", "cta": "..."}},
 "card": {{"eyebrow": "小見出し(10字以内)", "title": "投稿のタイトル(8〜18字)", "body": "要約(40〜70字)"}},
 "ig_caption": "短い要約",
 "threads_text": "Threads本文（口語、400字以内、リンク禁止、質問で終える）",
 "threads_reply": "本文に続く返信（補足＋アプリの自然な一言、リンク禁止、200字以内）"
}}"""


def gemini_verify(gen):
    """게시 전 팩트체크 — 독립된 회의적 검증자가 심사한다. 통과 못 하면 게시하지 않는다."""
    content = json.dumps({
        "card": gen["card"], "ig_caption": gen["ig_caption"],
        "threads_text": gen["threads_text"], "threads_reply": gen["threads_reply"],
    }, ensure_ascii=False)
    prompt = f"""너는 운동과학·영양학에 정통한 회의적인 팩트체커다. 아래는 피트니스 앱 SNS 계정에 올라갈 게시물이다.
헬스 커뮤니티는 틀린 정보에 매우 민감하고, 구식 상식을 올리면 댓글로 조리돌림당한다. 그 사고를 막는 게 네 임무다.

## 심사 기준 (하나라도 걸리면 반려)
1. 사실 오류 또는 최신 연구와 배치되는 구식 상식 (예: 한 끼 단백질 흡수 상한 신화, 공복유산소 우월론, 부위별 지방감량 등)
2. 근거 없는 구체적 수치·배수 주장 ("2배 이득", "80%가 그만둔다" 등 출처 불명 수치)
3. 의학적 단정, 과장된 효능, 부상 위험이 있는 잘못된 자세 지도
4. 논쟁 중인 주제를 한쪽 입장으로 단정
{'5. (일본어 게시물) 일본 Threads 사용자가 읽기에 번역투·한국어 직역·어색한 표현이 있거나, 가격·요금을 언급함' if LANG == 'ja' else ''}
격려/동기부여/앱 소개처럼 사실 주장이 없는 문장은 심사 대상이 아니다. 지나치게 깐깐하게 굴어 모든 걸 반려하지는 마라 — '커뮤니티에서 지적당할 것인가'가 기준이다.

## 게시물
{content}

## 출력 (JSON만)
{{"approved": true|false, "issues": ["반려 사유(구체적으로, 무엇이 왜 틀렸고 어떻게 고칠지)"], "risk": "low|medium|high"}}"""
    body = json.dumps({
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"response_mime_type": "application/json", "temperature": 0.0},
    }).encode()
    url = (f"https://generativelanguage.googleapis.com/v1beta/models/"
           f"{GEMINI_MODEL}:generateContent?key={env('GEMINI_API_KEY')}")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=90) as r:
        resp = json.loads(r.read())
    out = json.loads(resp["candidates"][0]["content"]["parts"][0]["text"])
    assert "approved" in out
    return out


def gemini_generate_verified(history, max_attempts=3, product=None):
    """생성 → 팩트체크 → 반려 시 지적사항을 반영해 재생성. 통과분만 반환, 전부 반려면 None."""
    feedback = None
    for attempt in range(1, max_attempts + 1):
        gen = gemini_generate(history, feedback=feedback, product=product)
        if too_repetitive(gen, history):
            print(f"[{attempt}회차] 최근 3일과 동일 조합 — 재생성")
            feedback = "직전 시도는 최근 3일과 같은 실험 조합이라 반려됨. 다른 hook_style 또는 topic으로."
            continue
        verdict = gemini_verify(gen)
        if verdict.get("approved"):
            gen["_verify"] = {"attempts": attempt, "risk": verdict.get("risk")}
            print(f"[검증 통과] {attempt}회차, risk={verdict.get('risk')}")
            return gen
        issues = "; ".join(verdict.get("issues", []))
        print(f"[검증 반려 {attempt}회차] {issues}")
        feedback = f"직전 시도가 팩트체크에서 반려됨. 반려 사유를 완전히 해소하고 새로 써라: {issues}"
    return None


def too_repetitive(gen, history):
    """최근 3일과 (hook_style, topic)이 모두 같으면 재생성 대상."""
    recent = [h.get("experiment", {}) for h in history[-3:]]
    if len(recent) < 3:
        return False
    e = gen["experiment"]
    return all(r.get("hook_style") == e.get("hook_style") and r.get("topic") == e.get("topic")
               for r in recent)


# ---------- card render (Pillow) ----------

def render_card(card, out_path):
    from PIL import Image, ImageDraw, ImageFilter, ImageFont

    W = H = 1080
    img = Image.new("RGB", (W, H), (11, 15, 26))

    glow = Image.new("RGB", (W, H), (11, 15, 26))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([W - 500, -300, W + 200, 400], fill=(13, 60, 130))
    gd.ellipse([-260, H - 420, 380, H + 260], fill=(52, 40, 120))
    glow = glow.filter(ImageFilter.GaussianBlur(180))
    img = Image.blend(img, glow, 0.55)
    d = ImageDraw.Draw(img)

    def font(size, bold=True):
        base = "/usr/share/fonts/truetype/nanum/"
        path = base + ("NanumGothicBold.ttf" if bold else "NanumGothic.ttf")
        if not os.path.exists(path):
            path = base + "NanumGothic.ttf"
        return ImageFont.truetype(path, size)

    x = 110
    d.text((x, 120), "LIFTUP AI · 오늘의 코칭", font=font(26), fill=(142, 150, 168))

    bar_y = 210
    for i in range(96):
        t = i / 96
        r = int(10 + (94 - 10) * t)
        g = int(132 + (92 - 132) * t)
        b = int(255 + (230 - 255) * t)
        d.rectangle([x + i, bar_y, x + i + 1, bar_y + 8], fill=(r, g, b))

    d.text((x, bar_y + 44), card["eyebrow"], font=font(30), fill=(142, 150, 168))

    title = card["title"]
    tsize = 78 if len(title) <= 12 else (64 if len(title) <= 18 else 54)
    tf = font(tsize)
    y = bar_y + 120
    for line in textwrap.wrap(title, width=max(6, int(14 * 64 / tsize))):
        d.text((x, y), line, font=tf, fill=(242, 244, 248))
        y += int(tsize * 1.4)

    y += 30
    bf = font(38, bold=False)
    for line in textwrap.wrap(card["body"], width=22):
        d.text((x, y), line, font=bf, fill=(185, 193, 208))
        y += 62

    fy = H - 150
    d.rectangle([x, fy, W - 110, fy + 2], fill=(50, 58, 74))
    d.text((x, fy + 32), "LIFTUP AI", font=font(32), fill=(77, 163, 255))
    d.text((W - 110, fy + 36), "AI 퍼스널 트레이너 · liftupai.net", font=font(24, bold=False),
           fill=(142, 150, 168), anchor="ra")

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    img.save(out_path, "PNG")


# ---------- posting ----------

def ig_post(image_url, caption):
    tok = env("IG_ACCESS_TOKEN")
    uid = call(IG_GRAPH, "me", {"fields": "id"}, tok, "GET")["id"]
    c = call(IG_GRAPH, f"{uid}/media", {"image_url": image_url, "caption": caption}, tok)
    for _ in range(18):
        s = call(IG_GRAPH, c["id"], {"fields": "status_code"}, tok, "GET").get("status_code")
        if s == "FINISHED":
            break
        if s == "ERROR":
            raise RuntimeError(f"IG 컨테이너 실패 {c['id']}")
        time.sleep(5)
    return call(IG_GRAPH, f"{uid}/media_publish", {"creation_id": c["id"]}, tok)["id"]


def th_post(text, image_url=None, reply_to=None):
    tok = env("THREADS_ACCESS_TOKEN")
    uid = call(TH_GRAPH, "me", {"fields": "id"}, tok, "GET")["id"]
    params = {"text": text}
    if image_url:
        params.update({"media_type": "IMAGE", "image_url": image_url})
    else:
        params["media_type"] = "TEXT"
    if reply_to:
        params["reply_to_id"] = reply_to
    c = call(TH_GRAPH, f"{uid}/threads", params, tok)
    time.sleep(12)
    return call(TH_GRAPH, f"{uid}/threads_publish", {"creation_id": c["id"]}, tok)["id"]


# ---------- fallback ----------

def fallback_post(today):
    """Gemini 실패 시 content.json 로테이션."""
    import subprocess
    print("[fallback] content.json 로테이션으로 게시")
    r = subprocess.run([sys.executable, os.path.join(HERE, "post_social.py"), "daily"])
    if r.returncode != 0:
        sys.exit("fallback 게시도 실패")
    append_history({"date": today, "mode": "fallback", "content": {"title": "(rotation)"},
                    "experiment": {}, "ids": {}})


def main():
    today = datetime.datetime.now(KST).strftime("%Y-%m-%d")
    history = load_history()
    if any(h["date"] == today for h in history):
        print(f"{today} 이미 게시됨 — 종료")
        return

    product = product_slide_for(today) if LANG == "ko" else None
    try:
        gen = gemini_generate_verified(history, product=product)
    except Exception as e:
        print(f"Gemini 생성 실패: {e}")
        if LANG == "ko" and not DRY_RUN:
            fallback_post(today)  # 폴백 문구(content.json)는 한국어뿐
        return
    if gen is None:
        print("!!! 3회 연속 팩트체크 반려 — 오늘 게시 스킵 (직접 확인 필요)")
        return
    if product:
        gen["experiment"]["threads_format"] = "image"
        gen["experiment"]["topic"] = "앱기능"
        gen["experiment"]["showcase"] = product["kind"]
    if LANG != "ko":
        gen["experiment"]["threads_format"] = "text"  # 카드 렌더러는 한국어 전용
    if DRY_RUN:
        print(json.dumps(gen, ensure_ascii=False, indent=2))
        print("[dry-run] 게시·기록 안 함")
        return

    base = env("CARD_BASE_URL").rstrip("/")
    if LANG != "ko":
        fname = None
    elif product:
        # 쇼케이스 데이: 텍스트 카드 대신 실제 앱 화면 슬라이드
        fname = product["file"]
    else:
        fname = f"daily/{today}.png"
        render_card(gen["card"], os.path.join(UPLOAD_DIR, f"{today}.png"))
    image_url = f"{base}/{fname}" if fname else None

    ig_id = None
    if INSTAGRAM_ENABLED and image_url:
        try:
            ig_id = ig_post(image_url, gen["ig_caption"])
            print(f"[IG] {ig_id}")
        except Exception as e:
            print(f"IG 게시 실패(Threads는 계속 진행): {e}")
    else:
        print("[IG] 비활성 — 건너뜀 (ENABLE_INSTAGRAM=1 로 재개)")

    th_img = image_url if gen["experiment"].get("threads_format") != "text" else None
    th_id = None
    try:
        th_id = th_post(gen["threads_text"], th_img)
        print(f"[Threads] {th_id}")
    except Exception as e:
        print(f"Threads 게시 실패: {e}")
    if not th_id and not ig_id:
        sys.exit("게시 실패 — history 기록 없이 종료(다음 실행에서 재시도)")

    # '스레드에 추가' — 본문 조회수 부스트 + 링크는 본문이 아닌 댓글에 (노출 페널티 회피)
    th_reply_id = None
    try:
        landing = BRAND["landing"] if LANG == "ko" else BRAND_JA["landing"]
        reply_text = gen["threads_reply"] + f"\n\n🔗 {landing}"
        time.sleep(20)
        th_reply_id = th_post(reply_text, reply_to=th_id)
        print(f"[Threads reply] {th_reply_id}")
    except Exception as e:
        print(f"Threads 댓글 실패(본문은 유지): {e}")

    append_history({
        "date": today, "mode": "gemini", "lang": LANG,
        "verify": gen.get("_verify"),
        "analysis": gen.get("analysis"),
        "experiment": gen["experiment"],
        "content": {**gen["card"], "ig_caption": gen["ig_caption"],
                    "threads_text": gen["threads_text"], "threads_reply": gen["threads_reply"]},
        "image": fname,
        "ids": {"ig": ig_id, "threads": th_id, "threads_reply": th_reply_id},
    })
    print(f"기록 완료: {today} / 실험={json.dumps(gen['experiment'], ensure_ascii=False)}")


if __name__ == "__main__":
    main()
