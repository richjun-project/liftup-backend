# -*- coding: utf-8 -*-
"""게시물 지표 수집 — history.jsonl의 각 게시물에 인스타/Threads 인사이트를 채워넣는다.

매일 게시 직전(cron)에 실행해 최신 지표로 autopilot의 학습 데이터를 갱신한다.
30일 지난 게시물은 더 이상 갱신하지 않는다.

필요 환경변수: IG_ACCESS_TOKEN, THREADS_ACCESS_TOKEN
"""
import datetime
import json
import os
import sys
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
HISTORY = os.path.join(HERE, "history.jsonl")
IG_GRAPH = "https://graph.instagram.com/v21.0"
TH_GRAPH = "https://graph.threads.net/v1.0"
KST = datetime.timezone(datetime.timedelta(hours=9))


def get(base, path, params, token):
    q = urllib.parse.urlencode({**params, "access_token": token})
    with urllib.request.urlopen(f"{base}/{path}?{q}", timeout=60) as r:
        return json.loads(r.read())


def insights(base, media_id, metrics, token):
    try:
        data = get(base, f"{media_id}/insights", {"metric": metrics}, token)["data"]
        return {d["name"]: d["values"][0]["value"] for d in data}
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")
        if '"code":190' in body or "OAuthException" in body:
            print(f"!!! 토큰 무효화 감지({base}) — 재발급 필요: {body[:200]}", file=sys.stderr)
        else:
            print(f"  지표 실패 {media_id}: {e} {body[:150]}", file=sys.stderr)
        return None
    except Exception as e:
        print(f"  지표 실패 {media_id}: {e}", file=sys.stderr)
        return None


def main():
    # 2026-10-29: 일본어 실험 글(autopilot.py --lang ja)은 history_ja.jsonl 에 따로 쌓인다
    for path in (HISTORY, os.path.join(HERE, "history_ja.jsonl")):
        collect(path)


def collect(HISTORY):
    if not os.path.exists(HISTORY):
        print(f"{os.path.basename(HISTORY)} 없음")
        return
    with open(HISTORY, encoding="utf-8") as f:
        records = [json.loads(l) for l in f if l.strip()]

    # 인스타 게시 중단(2026-08-30)에 맞춰 지표 수집도 기본 비활성 — ENABLE_INSTAGRAM=1이면 재개
    ig_enabled = os.environ.get("ENABLE_INSTAGRAM", "0") == "1"
    ig_tok = os.environ.get("IG_ACCESS_TOKEN") if ig_enabled else None
    th_tok = os.environ.get("THREADS_ACCESS_TOKEN")
    today = datetime.datetime.now(KST).date()
    changed = False

    for rec in records:
        try:
            age = (today - datetime.date.fromisoformat(rec["date"])).days
        except Exception:
            continue
        if age > 30:
            continue
        # 삭제된 게시물은 조회할 대상이 없다 (사고로 내린 글 등)
        if rec.get("metrics", {}).get("deleted"):
            continue
        ids = rec.get("ids", {})
        m = rec.setdefault("metrics", {})
        if ids.get("ig") and ig_tok:
            r = insights(IG_GRAPH, ids["ig"], "views,likes,comments,reach,saved,shares", ig_tok)
            if r:
                m["ig"] = r
                changed = True
        if ids.get("threads") and th_tok:
            r = insights(TH_GRAPH, ids["threads"], "views,likes,replies,reposts,shares", th_tok)
            if r:
                m["threads"] = r
                changed = True
        m["collected_at"] = today.isoformat()

    if changed:
        tmp = HISTORY + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            for rec in records:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        os.replace(tmp, HISTORY)
        print(f"{os.path.basename(HISTORY)}: {sum(1 for r in records if r.get('metrics'))}건 지표 갱신")
    else:
        print("갱신 없음")


if __name__ == "__main__":
    main()
