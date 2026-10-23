#!/bin/bash
# 리프트업AI 매일 CEO 운영 루프(launchd: com.richjun.liftupai-ceo, 매일 10:20 KST).
#   1) KPI 를 모아 growth/kpi/<날짜>.json  2) Claude Code(headless)가 daily_prompt.md 대로 오늘 일을 하고 Slack 으로 보고
# 수동 실행: ~/Develop/liftupai/growth/daily_ceo.sh   (이미 돌고 있으면 그냥 끝난다)
set -uo pipefail
export PATH="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export TZ=Asia/Seoul
G="$HOME/Develop/liftupai/growth"
DAY=$(date +%F)
mkdir -p "$G/kpi" "$G/logs" "$G/reports"
LOG="$G/logs/$DAY.log"
exec >> "$LOG" 2>&1
echo "=== $(date '+%F %T') start ==="

LOCK=/tmp/liftupai-ceo.lock
if ! mkdir "$LOCK" 2>/dev/null; then echo "이미 실행 중"; exit 0; fi
trap 'rmdir "$LOCK"' EXIT
# 실행 중엔 잠들지 않게, 깨어난 직후(네트워크 없음)면 최대 60분 기다린다
caffeinate -i -w $$ &
for i in $(seq 1 120); do curl -s -m 5 -o /dev/null https://api.liftupai.net/ && break; sleep 30; done

"$HOME/Develop/liftupai/scripts/ops/kpi_daily.sh" > "$G/kpi/$DAY.json" || echo "KPI 수집 실패"
# iOS 스토어 퍼널(국가·유입 경로별 노출→페이지→첫 다운로드, 최근 14일). ASC 분석 리포트는 관리자 키가 필요하다
kw run asc-chrobox -- python3 "$HOME/Develop/liftupai/scripts/growth/asc_funnel.py" --days 14 --json > "$G/kpi/$DAY.store.json" || echo "스토어 퍼널 수집 실패"

PROMPT="$(cat "$G/daily_prompt.md")

## 오늘
- 날짜: $DAY ($(date '+%a %H:%M') KST)
- KPI 파일: $G/kpi/$DAY.json
- iOS 스토어 퍼널(14일): $G/kpi/$DAY.store.json
- 보고 파일: $G/reports/$DAY.md"

cd "$HOME/Documents"   # 메모리(MEMORY.md)가 이 작업 폴더 기준으로 붙는다
OK=0
for M in claude-opus-5-5 opus sonnet; do
  if timeout 14400 claude -p "$PROMPT" --model "$M" --dangerously-skip-permissions --output-format text < /dev/null > "$G/logs/$DAY.claude.txt" 2>> "$LOG"; then
    echo "model: $M"; OK=1; break
  fi
  echo "claude($M) 실패: $(tail -c 300 "$G/logs/$DAY.claude.txt" | tr '\n' ' ')"
done
if [ "$OK" = 0 ]; then
  osascript -e 'display notification "리프트업 CEO 루프 실패 — growth/logs 확인" with title "LiftUp"' || true
  exit 1
fi
# 보고를 못 보냈으면(보고 파일이 없으면) 숫자만이라도 보낸다
if [ ! -s "$G/reports/$DAY.md" ]; then
  python3 - "$G/kpi/$DAY.json" > "$G/reports/$DAY.md" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); m = d.get('metrics', {}); s = d.get('state', {})
g = lambda k: m.get(k, {}).get('last24h', 0)
print(f":muscle: *리프트업AI 대표 보고(숫자만)* — 운영 루프가 보고를 쓰지 못했다\n"
      f"MRR ₩{s.get('mrr_krw_est', 0):,} · 유료 {s.get('paying_subscribers', 0)} · 체험 {s.get('in_trial', 0)} · 활성 7일 {s.get('active_users_7d', 0)}\n"
      f"24h 실가입 {g('signups_onboarded')} · 첫 세트 {g('first_set_users')} · 결제 화면 {g('ev_paywall_view')} · 체험 {g('trials_started')}")
PY
  kw run slack-bot-token -- python3 "$G/slack_report.py" send "$G/reports/$DAY.md" || true
fi
echo "=== $(date '+%F %T') end ==="
