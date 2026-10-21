#!/usr/bin/env python3
"""리프트업AI 대표 보고를 Slack 전용 채널로 보낸다(2026-10-21).

    kw run slack-bot-token -- python3 growth/slack_report.py setup            # 채널 만들기·대표 초대(한 번)
    kw run slack-bot-token -- python3 growth/slack_report.py send <report.md>  # 보고 보내기

형식은 대표 일일 리포트(~/orca/projects/daily-report)와 같다: Slack mrkdwn, `---THREAD---` 로 나눈 첫 부분은 채널,
나머지는 그 메시지의 스레드 댓글. 섹션 하나는 3,000자 제한이라 빈 줄 기준으로 나눈다. 채널 id 는 growth/slack.json.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

CHANNEL_NAME = "liftupai-대표보고"
PURPOSE = "리프트업AI 월 매출 1,000만 원 — 매일 지표·한 일·결정·다음 할 일(Claude 운영 보고)"
CONF = Path(__file__).resolve().parent / "slack.json"
LIMIT = 2900


def api(method, token, payload=None, **params):
    if payload is not None:
        req = urllib.request.Request(f"https://slack.com/api/{method}", data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json; charset=utf-8", "Authorization": f"Bearer {token}"})
    else:
        req = urllib.request.Request(f"https://slack.com/api/{method}?{urllib.parse.urlencode(params)}",
                                     headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=20) as r:
        res = json.load(r)
    if not res.get("ok"):
        raise RuntimeError(f"{method}: {res.get('error')}")
    return res


def chunks(text):
    parts, cur = [], ""
    for block in text.split("\n\n"):
        if len(cur) + len(block) + 2 > LIMIT and cur:
            parts.append(cur)
            cur = ""
        while len(block) > LIMIT:
            cut = block.rfind("\n", 0, LIMIT)
            cut = cut if cut > 0 else LIMIT
            parts.append(block[:cut])
            block = block[cut:].lstrip("\n")
        cur = f"{cur}\n\n{block}" if cur else block
    if cur:
        parts.append(cur)
    return parts


def setup(token):
    existing = None
    cursor = ""
    while True:
        r = api("conversations.list", token, types="public_channel,private_channel", limit=200, cursor=cursor, exclude_archived="true")
        existing = existing or next((c for c in r["channels"] if c["name"] == CHANNEL_NAME), None)
        cursor = r.get("response_metadata", {}).get("next_cursor", "")
        if existing or not cursor:
            break
    ch = existing or api("conversations.create", token, {"name": CHANNEL_NAME, "is_private": False})["channel"]
    api("conversations.setPurpose", token, {"channel": ch["id"], "purpose": PURPOSE})
    owner = next(m["id"] for m in api("users.list", token)["members"] if m.get("is_primary_owner"))
    try:
        api("conversations.invite", token, {"channel": ch["id"], "users": owner})
    except RuntimeError as e:
        if not any(k in str(e) for k in ("already_in_channel", "cant_invite_self")):
            raise
    CONF.write_text(json.dumps({"channel_name": CHANNEL_NAME, "channel_id": ch["id"]}, ensure_ascii=False, indent=2) + "\n")
    print(f"channel ready: {CHANNEL_NAME} ({'existing' if existing else 'created'})")


def send(token, path):
    channel = json.loads(CONF.read_text())["channel_id"]
    text = Path(path).read_text(encoding="utf-8").strip()
    sections = [s.strip() for s in text.split("---THREAD---") if s.strip()]
    thread_ts = None
    sent = 0
    for section in sections:
        for part in chunks(section):
            msg = {"channel": channel, "text": part, "mrkdwn": True, "unfurl_links": False, "unfurl_media": False}
            if thread_ts:
                msg["thread_ts"] = thread_ts
            res = api("chat.postMessage", token, msg)
            thread_ts = thread_ts or res["ts"]
            sent += 1
            time.sleep(1)
    print(f"sent {sent} message(s) to {CHANNEL_NAME}")


if __name__ == "__main__":
    tok = os.environ.get("KW_SLACK_BOT_TOKEN") or sys.exit("봇 토큰이 없습니다 (kw run slack-bot-token -- ...)")
    if len(sys.argv) >= 2 and sys.argv[1] == "setup":
        setup(tok)
    elif len(sys.argv) >= 3 and sys.argv[1] == "send":
        send(tok, sys.argv[2])
    else:
        sys.exit(__doc__)
