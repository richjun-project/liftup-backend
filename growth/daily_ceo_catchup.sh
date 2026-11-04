#!/bin/bash
# 매일 루프 따라잡기(launchd: com.richjun.liftupai-ceo-catchup, 로그인 때 + 매시간).
# launchd 달력 작업(10:20)은 맥이 잠들어 있었으면 깨어날 때 돌지만, 꺼져 있었으면 놓친 실행을 다시 하지 않는다 —
# 2026-11-03 맥이 11:34 에 켜져 그날 루프가 통째로 빠졌다. 10:20 이 지났는데 오늘 로그가 없으면 본 루프를 돈다.
# 설치: cp growth/launchd/com.richjun.liftupai-ceo-catchup.plist ~/Library/LaunchAgents/ && launchctl bootstrap gui/$(id -u) <그 파일>
export TZ=Asia/Seoul
G="$HOME/Develop/liftupai/growth"
DAY=$(date +%F)
[ "$(date +%H%M)" -lt 1020 ] && exit 0
[ -e "$G/logs/$DAY.log" ] && exit 0        # 오늘 이미 시작했다(성공·실패 무관 — 실패면 맥 알림이 뜬다)
[ -d /tmp/liftupai-ceo.lock ] && exit 0    # 지금 돌고 있다
exec "$G/daily_ceo.sh"
