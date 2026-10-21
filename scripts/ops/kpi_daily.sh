#!/bin/bash
# LiftUp 일일 KPI 수집기(읽기 전용, 2026-10-21). 출력: JSON 1개 — 대표 일일 리포트(~/orca/projects/daily-report)의 수집기 형식.
#   metrics.<이름> = {last24h, prev24h, avg7d, uniq24h, uniq7d, uniq30d}  (uniq 는 사용자·건 중복 제거)
#   state  = 현재 값(유료 구독자, 추정 MRR, 체험 중, 누적·활성 사용자)
#   ops    = 앱 ERROR·5xx·채팅 지연(Caddy)·디스크, marketing = Threads 자동 게시 조회수, store = App Store 평점 수
# 운영 서버 접속은 키체인 kw(ssh-liftupai). 비밀번호·토큰은 출력하지 않는다.
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
export PATH="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

# 원격 스크립트: SQL 은 docker exec 의 표준입력(heredoc)으로 넣는다
{
  cat <<'EOS'
set -uo pipefail
echo "@@SQL"
docker exec -i liftupai-mysql-prod sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" liftupai_db --default-character-set=utf8mb4 -N -B 2>/dev/null' <<'SQLEOF'
EOS
  cat "$DIR/kpi_daily.sql"
  cat <<'EOS'
SQLEOF
echo "@@OPS"
L=~/liftup-backend/logs
echo "app_errors_24h $(docker logs liftupai-app-prod --since 24h 2>&1 | grep ' ERROR ' | grep -vc 'QdrantClient\|ALREADY_EXISTS')"
echo "container_running $(docker inspect -f '{{.State.Running}}' liftupai-app-prod 2>/dev/null)"
echo "disk_used_pct $(df / | awk 'NR==2{gsub("%","",$5); print $5}')"
sudo -n python3 - <<'PY' 2>/dev/null
import json, time
since = time.time() - 86400
app = s5 = s4 = 0; chat = []
for line in open('/var/log/caddy/api-access.log', errors='ignore'):
    try: o = json.loads(line)
    except Exception: continue
    if o.get('ts', 0) < since: continue
    r = o.get('request', {}); h = r.get('headers', {}); s = o.get('status', 0)
    if 'dart' not in ((h.get('User-Agent') or [''])[0]).lower(): continue
    app += 1
    if s >= 500: s5 += 1
    elif s >= 400: s4 += 1
    if r.get('uri', '').split('?')[0] == '/api/ai/chat': chat.append(o.get('duration', 0))
chat.sort()
print('app_requests_24h', app); print('app_5xx_24h', s5); print('app_4xx_24h', s4)
print('chat_requests_24h', len(chat))
print('chat_p50_s', round(chat[len(chat) // 2], 1) if chat else 'null')
print('chat_max_s', round(chat[-1], 1) if chat else 'null')
PY
echo "@@MKT"
python3 - <<'PY' 2>/dev/null
import json, datetime
rows = [json.loads(l) for l in open('/home/ubuntu/liftup-backend/marketing/history.jsonl') if l.strip()]
cut = (datetime.date.today() - datetime.timedelta(days=8)).isoformat()
recent = [r for r in rows if (r.get('date') or '') >= cut]
views = [((r.get('metrics') or {}).get('threads') or r.get('metrics') or {}).get('views') for r in recent]
views = [v for v in views if isinstance(v, (int, float))]
print(json.dumps({'threads_posts_8d': len(recent), 'threads_views_avg': round(sum(views) / len(views)) if views else None,
                  'threads_views_max': max(views) if views else None, 'last_post_date': rows[-1].get('date') if rows else None}))
PY
EOS
} > "$TMP/remote.sh"
kw run ssh-liftupai -- sh -c 'ssh -o BatchMode=yes -o ConnectTimeout=20 -i "$KW_SSH_LIFTUPAI" ubuntu@api.liftupai.net bash -s' < "$TMP/remote.sh" > "$TMP/out.txt" 2>"$TMP/err.txt"

for c in kr jp us; do
  curl -s -m 15 "https://itunes.apple.com/lookup?id=6752910787&country=$c" > "$TMP/store_$c.json" || echo '{}' > "$TMP/store_$c.json"
done

python3 - "$TMP" <<'PY'
import json, sys, os, datetime
tmp = sys.argv[1]
text = open(os.path.join(tmp, 'out.txt'), errors='replace').read()
sec = {}; cur = None
for line in text.splitlines():
    if line.startswith('@@'): cur = line[2:]; sec[cur] = []; continue
    if cur: sec[cur].append(line)
metrics, state = {}, {}
for line in sec.get('SQL', []):
    p = line.split('\t')
    if len(p) < 8: continue
    kind, name = p[0], p[1]
    nums = [float(x) if x not in ('NULL', '') else 0 for x in p[2:8]]
    if kind == 'metric':
        c0, c1, c7, u0, u7, u30 = nums
        metrics[name] = {'last24h': int(c0), 'prev24h': int(c1), 'avg7d': round(c7 / 7, 1), 'uniq24h': int(u0), 'uniq7d': int(u7), 'uniq30d': int(u30)}
    else:
        state[name] = round(nums[0]) if nums[0] == int(nums[0]) else round(nums[0], 1)
ops = {}
for line in sec.get('OPS', []):
    k, _, v = line.partition(' ')
    if not k: continue
    try: ops[k] = json.loads(v)
    except Exception: ops[k] = v
mkt = {}
for line in sec.get('MKT', []):
    try: mkt = json.loads(line)
    except Exception: pass
store = {}
for c in ('kr', 'jp', 'us'):
    try:
        r = (json.load(open(os.path.join(tmp, f'store_{c}.json'))).get('results') or [{}])[0]
        store[c] = {'rating': r.get('averageUserRating'), 'ratings': r.get('userRatingCount'), 'version': r.get('version')}
    except Exception:
        store[c] = None
out = {'app': 'liftupai', 'collected_at': datetime.datetime.now().isoformat(timespec='seconds'),
       'metrics': metrics, 'state': state, 'ops': ops, 'marketing': mkt, 'store': store,
       'notes': ['개발자 계정(22) 제외', 'signups_all 에는 매일 오는 Google 테스트 기기(온보딩 없음)가 섞인다 — 실가입은 signups_onboarded',
                 'mrr_krw_est 는 최근 30일 실제 결제 합(JPY×9.2, USD×1400)']}
if not metrics:
    out['error'] = 'SQL 결과 없음: ' + open(os.path.join(tmp, 'err.txt'), errors='replace').read()[-300:]
print(json.dumps(out, ensure_ascii=False))
PY
