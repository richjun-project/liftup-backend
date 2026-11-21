#!/bin/sh
# Google Play 심사용 로그인 계정(play.review@liftupai.net)을 운영 서버에 만든다. (2026-11-21, Play '로그인 사용자 인증 정보 없음' 거절 대응)
# - 비밀번호는 대표가 직접 정한다. 화면에 안 보이고, 파일·로그 어디에도 남기지 않는다.
# - Play 규정: 로그인 정보는 영어(영문·숫자·기호)만, 언제나 쓸 수 있어야 한다 → 이 계정은 지우지 않는다.
# - X-Test-Device 헤더로 보내 KPI(실가입)에 안 섞인다.
# 실행: 터미널 앱에서  sh ~/Develop/liftupai-coach/scripts/ops/create_play_review_account.sh
set -e
EMAIL="play.review@liftupai.net"
printf '심사 계정 비밀번호 (영문·숫자 8자 이상, 입력이 화면에 안 보임): '
stty -echo; read -r PW; stty echo; echo
printf '한 번 더: '
stty -echo; read -r PW2; stty echo; echo
[ "$PW" = "$PW2" ] || { echo '두 번 입력이 다릅니다. 다시 실행하세요.'; exit 1; }
[ ${#PW} -ge 8 ] || { echo '8자 이상이어야 합니다.'; exit 1; }
case "$PW" in *[!A-Za-z0-9!@#%^*._-]*) echo '영문·숫자·기호(! @ # % ^ * . _ -)만 쓸 수 있습니다(Play 는 영어만 허용).'; exit 1;; esac
LIFTUP_PW="$PW" python3 - "$EMAIL" <<'EOF'
import json, os, sys, urllib.request, urllib.error
email, pw = sys.argv[1], os.environ['LIFTUP_PW']
def post(path, body):
    req = urllib.request.Request('https://api.liftupai.net/api/auth/' + path, data=json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json', 'X-Test-Device': '1', 'X-Platform': 'ANDROID'})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]
s, r = post('register', {'email': email, 'password': pw})
if s in (200, 201):
    print('가입 완료 — user_id', (r.get('data') or r).get('user_id'))
else:
    print('가입 응답', s, '(이미 있는 계정이면 아래 로그인 확인만 보면 됩니다)')
s, r = post('login', {'email': email, 'password': pw})
print('로그인 확인:', '성공 — 이 비밀번호를 플레이 콘솔 「앱 액세스」의 비밀번호 칸에 넣으세요' if s == 200 else f'실패 {s}')
EOF
