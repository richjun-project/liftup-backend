"""App Store Connect API 최소 클라이언트(리프트업AI). 키는 키체인: kw run asc-liftupai -- python3 ...
timebox/aso/asc.py 와 같은 방식(ES256 JWT). 키 값은 출력하지 않는다."""
import json, os, time, urllib.error, urllib.request

import jwt

KEY_ID = '4HRHDTP47U'
ISSUER = '3a7331ed-e39f-4631-9738-a664230a6b0c'
APP = '6752910787'


# 분석 리포트(analyticsReportRequests)는 관리자 키가 필요하다 — LiftUp 키(4HRHDTP47U)는 403.
# 같은 팀의 관리자 키(asc-chrobox, KCBM3VV77T)로 실행하면 그 키를 쓴다: kw run asc-chrobox -- python3 ...
ADMIN_KEY_ID = 'KCBM3VV77T'


def token():
    admin = os.environ.get('KW_ASC_CHROBOX')
    p = admin or os.environ.get('KW_ASC_LIFTUPAI') or os.environ.get('ASC_KEY_PATH')
    kid = ADMIN_KEY_ID if admin else KEY_ID
    return jwt.encode({'iss': ISSUER, 'iat': int(time.time()), 'exp': int(time.time()) + 1100, 'aud': 'appstoreconnect-v1'},
                      open(p).read(), algorithm='ES256', headers={'kid': kid, 'typ': 'JWT'})


def call(method, path, body=None, tries=3):
    # 연결 끊김(Connection reset·타임아웃)은 두 번 더 해 본다 — 11-01 아침 수집이 한 번 끊겨 스토어 퍼널이 통째로 비었다.
    # HTTP 오류 응답은 다시 하지 않는다(POST 중복 생성 방지).
    for attempt in range(tries):
        req = urllib.request.Request('https://api.appstoreconnect.apple.com' + path if path.startswith('/') else path, method=method,
                                     data=json.dumps(body).encode() if body else None,
                                     headers={'Authorization': 'Bearer ' + token(), 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                t = r.read()
                return r.status, (json.loads(t) if t and t[:1] in b'[{' else {})
        except urllib.error.HTTPError as e:
            b = e.read()
            try:
                return e.code, json.loads(b or b'{}')
            except Exception:
                return e.code, {'raw': b[:300].decode('utf8', 'replace')}
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            if attempt == tries - 1 or method != 'GET':
                raise
            time.sleep(10 * (attempt + 1))
