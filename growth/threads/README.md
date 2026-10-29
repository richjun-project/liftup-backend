# Threads 자동 게시(운영 서버 사본)

원본은 운영 서버 `~/liftup-backend/marketing/`(저장소 밖). 고칠 땐 여기 사본도 같이 갱신한다.

- `autopilot.py` — 한국어(cron `0 12 * * *` UTC = 21:00 KST, history.jsonl) · 일본어 실험(`--lang ja`, cron `0 3 * * *` UTC = 12:00 JST, history_ja.jsonl, 댓글 링크 App Store JP `ct=threads-ja`). `--dry-run` 은 생성·팩트체크만.
- `collect_metrics.py` — 두 기록 파일의 Threads 조회·좋아요·답글을 채운다(cron `15 11 * * *` UTC).
- 서버 백업: `autopilot.py.bak-20261029`, `collect_metrics.py.bak-20261029`, crontab `~/backups/crontab_20261029_pre_threads_ja.txt`.
- 일본어 실험 판정(11-05): 일본어 글 조회 평균 vs 한국어(같은 주), App Store 캠페인 `threads-ja` 페이지 조회·첫 다운로드(`asc_funnel.py`). 일본어가 한국어보다 낮거나 다운로드 0 이면 cron 줄을 지우고 끝낸다.
