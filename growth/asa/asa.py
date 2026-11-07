#!/usr/bin/env python3
"""Apple Search Ads(Advanced) API v5 최소 클라이언트 — 리프트업AI 한국 검색 광고 테스트(2026-11-07).

대표가 계정을 열고 API 키를 붙여 넣으면 캠페인 생성·매일 리포트·조정을 Claude 가 이걸로 한다. 구성은 campaign_kr.json, 이유는 PLAN.md.

  python3 growth/asa/asa.py keygen                       # 한 번: P-256 키 쌍 → 개인 키는 키체인(liftupai-asa-key), 공개 키는 public_key.pem
  kw run liftupai-asa-key -- python3 growth/asa/asa.py selftest          # 네트워크 없이: 서명·검증, 예산 상한, 요청 본문
  kw run liftupai-asa-key,liftupai-asa-ids -- python3 growth/asa/asa.py acls          # 계정(orgId) 확인
  kw run liftupai-asa-key,liftupai-asa-ids -- python3 growth/asa/asa.py plan          # 만들 것 미리 보기(요청 안 함)
  kw run liftupai-asa-key,liftupai-asa-ids -- python3 growth/asa/asa.py launch [--enable]   # 기본은 일시정지 상태로 생성
  kw run liftupai-asa-key,liftupai-asa-ids -- python3 growth/asa/asa.py status | enable | pause
  kw run liftupai-asa-key,liftupai-asa-ids -- python3 growth/asa/asa.py report [--days 7]   # 캠페인·키워드·검색어 → reports/<날짜>.json
  kw run liftupai-asa-key,liftupai-asa-ids -- python3 growth/asa/asa.py call GET /campaigns   # 그 밖의 조정(입찰 등)

자격: 개인 키는 ASA_KEY_PATH(kw --env-path), clientId·teamId·keyId 는 ASA_IDS("clientId teamId keyId", kw set liftupai-asa-ids
--env-content ASA_IDS) 또는 ids.json. 키·토큰 값은 출력하지 않는다. 상태(캠페인·광고 그룹 id)는 state.json.
"""
import argparse, datetime as dt, json, os, re, subprocess, sys, tempfile, time, urllib.error, urllib.parse, urllib.request

import jwt

HERE = os.path.dirname(os.path.abspath(__file__))
CONF = os.path.join(HERE, 'campaign_kr.json')
STATE = os.path.join(HERE, 'state.json')
PUBKEY = os.path.join(HERE, 'public_key.pem')
IDS_FILE = os.path.join(HERE, 'ids.json')
REPORTS = os.path.join(HERE, 'reports')
KEY_NAME = 'liftupai-asa-key'
API = 'https://api.searchads.apple.com/api/v5'
TOKEN_URL = 'https://appleid.apple.com/auth/oauth2/token'


# ── 자격 ─────────────────────────────────────────────────────────────────────
def key_path():
    p = os.environ.get('ASA_KEY_PATH') or os.environ.get('KW_LIFTUPAI_ASA_KEY')
    if not p or not os.path.exists(p):
        sys.exit(f'개인 키가 없습니다 — kw run {KEY_NAME} -- … 로 실행하세요(없으면 keygen 먼저).')
    return p


def ids():
    raw = os.environ.get('ASA_IDS') or os.environ.get('KW_LIFTUPAI_ASA_IDS')
    if raw:
        parts = [x for x in re.split(r'[\s,;]+', raw.strip()) if x]
        if len(parts) != 3:
            sys.exit('ASA_IDS 는 "clientId teamId keyId" 세 값이어야 합니다.')
        return dict(zip(('clientId', 'teamId', 'keyId'), parts))
    if os.path.exists(IDS_FILE):
        return json.load(open(IDS_FILE))
    sys.exit('clientId·teamId·keyId 가 없습니다 — 대표가 ASA 설정 → API 에 public_key.pem 을 붙여 넣은 뒤 '
             'kw set liftupai-asa-ids --env-content ASA_IDS 로 "clientId teamId keyId" 를 저장.')


def client_secret(i, pem, now=None):
    now = int(now or time.time())
    return jwt.encode({'sub': i['clientId'], 'aud': 'https://appleid.apple.com', 'iat': now,
                       'exp': now + 86400 * 180, 'iss': i['teamId']},
                      pem, algorithm='ES256', headers={'alg': 'ES256', 'kid': i['keyId']})


_TOKEN = {}


def access_token():
    if _TOKEN.get('exp', 0) > time.time() + 60:
        return _TOKEN['v']
    i = ids()
    body = urllib.parse.urlencode({'grant_type': 'client_credentials', 'client_id': i['clientId'],
                                   'client_secret': client_secret(i, open(key_path()).read()),
                                   'scope': 'searchadsorg'}).encode()
    req = urllib.request.Request(TOKEN_URL, data=body, headers={'Content-Type': 'application/x-www-form-urlencoded'})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            d = json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f'토큰 발급 실패 {e.code}: {e.read()[:300].decode("utf8", "replace")}')
    _TOKEN.update(v=d['access_token'], exp=time.time() + int(d.get('expires_in', 3600)))
    return _TOKEN['v']


def state():
    return json.load(open(STATE)) if os.path.exists(STATE) else {}


def save_state(s):
    json.dump(s, open(STATE, 'w'), ensure_ascii=False, indent=1)


def call(method, path, body=None, org=True):
    """(status, json). HTTP 오류도 돌려준다. POST 는 재시도하지 않는다(중복 생성 방지)."""
    h = {'Authorization': 'Bearer ' + access_token(), 'Content-Type': 'application/json'}
    if org:
        oid = state().get('orgId') or os.environ.get('ASA_ORG_ID')
        if oid:
            h['X-AP-Context'] = f'orgId={oid}'
    req = urllib.request.Request(API + path, method=method, headers=h,
                                 data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None)
    for attempt in range(3 if method == 'GET' else 1):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                t = r.read()
                return r.status, (json.loads(t) if t else {})
        except urllib.error.HTTPError as e:
            t = e.read()
            try:
                return e.code, json.loads(t or b'{}')
            except Exception:
                return e.code, {'raw': t[:300].decode('utf8', 'replace')}
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            if attempt == 2 or method != 'GET':
                raise
            time.sleep(5 * (attempt + 1))


def ok(s, d, what):
    if s >= 300:
        sys.exit(f'{what} 실패 {s}: {json.dumps(d, ensure_ascii=False)[:600]}')
    return d.get('data', d)


# ── 구성 → 요청 본문 ─────────────────────────────────────────────────────────
def money(amount, c):
    return {'amount': str(int(amount)), 'currency': c['currency']}


def ts(t):
    return t.strftime('%Y-%m-%dT%H:%M:%S.000')


def build(c, start=None):
    """campaign_kr.json → (캠페인, [(광고 그룹, 키워드, 그룹 제외 키워드)], 캠페인 제외 키워드)."""
    if c['dailyBudget'] * c['days'] > c['totalBudget']:
        raise ValueError('하루 예산 × 일수가 총예산을 넘는다')
    start = start or (dt.datetime.now() + dt.timedelta(minutes=10)).replace(second=0, microsecond=0)
    end = start + dt.timedelta(days=c['days'])
    camp = {'name': c['campaignName'], 'adamId': c['adamId'], 'countriesOrRegions': [c['country']],
            'supplySources': ['APPSTORE_SEARCH_RESULTS'], 'adChannelType': 'SEARCH', 'billingEvent': 'TAPS',
            'dailyBudgetAmount': money(c['dailyBudget'], c), 'budgetAmount': money(c['totalBudget'], c),
            'status': 'PAUSED'}
    exact = [k for g in c['adGroups'] if g['matchType'] == 'EXACT' for k in g['keywords']]
    groups = []
    for g in c['adGroups']:
        ag = {'name': g['name'], 'pricingModel': 'CPC', 'defaultBidAmount': money(g['bid'], c),
              'startTime': ts(start), 'endTime': ts(end), 'automatedKeywordsOptIn': bool(g['searchMatch']),
              'targetingDimensions': {'deviceClass': {'included': ['IPHONE', 'IPAD']}}, 'status': 'ENABLED'}
        kws = [{'text': k, 'matchType': g['matchType'], 'bidAmount': money(g['bid'], c)} for k in g['keywords']]
        neg = [{'text': k, 'matchType': 'EXACT'} for k in exact] if g.get('negativeFromGroups') else []
        groups.append((ag, kws, neg))
    cneg = [{'text': k, 'matchType': 'BROAD'} for k in c['campaignNegatives']]
    return camp, groups, cneg


# ── 명령 ─────────────────────────────────────────────────────────────────────
def cmd_keygen(_):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    if os.path.exists(PUBKEY):
        sys.exit(f'이미 있다: {PUBKEY} — 키를 바꾸려면 ASA 에 붙인 공개 키도 바꿔야 한다. 지우고 다시 하세요.')
    k = ec.generate_private_key(ec.SECP256R1())
    priv = k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL,
                           serialization.NoEncryption())
    pub = k.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    d = tempfile.mkdtemp(prefix='asa-')
    p = os.path.join(d, 'liftupai-asa-private-key.pem')
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.write(fd, priv)
    os.close(fd)
    r = subprocess.run(['kw', 'import', p, '--as', KEY_NAME, '--env-path', 'ASA_KEY_PATH', '--yes'],
                       capture_output=True, text=True)
    if os.path.exists(p):
        os.remove(p)
    os.rmdir(d)
    if r.returncode != 0:
        sys.exit('키체인 저장 실패: ' + (r.stderr or r.stdout)[-300:])
    open(PUBKEY, 'wb').write(pub)
    print(f'개인 키 → 키체인 {KEY_NAME}(ASA_KEY_PATH), 공개 키 → {PUBKEY}')


def cmd_selftest(_):
    from cryptography.hazmat.primitives import serialization
    pem = open(key_path()).read()
    pub = open(PUBKEY).read()
    fake = {'clientId': 'SEARCHADS.test-client', 'teamId': 'SEARCHADS.test-team', 'keyId': 'test-key'}
    tok = client_secret(fake, pem, now=time.time())
    claims = jwt.decode(tok, pub, algorithms=['ES256'], audience='https://appleid.apple.com')
    head = jwt.get_unverified_header(tok)
    assert head['alg'] == 'ES256' and head['kid'] == 'test-key', head
    assert claims['sub'] == fake['clientId'] and claims['iss'] == fake['teamId'], claims
    assert claims['exp'] - claims['iat'] <= 86400 * 180, '180일 상한'
    # 키체인의 개인 키와 저장소의 공개 키가 짝인지
    k = serialization.load_pem_private_key(pem.encode(), None)
    assert k.public_key().public_bytes(serialization.Encoding.PEM,
                                       serialization.PublicFormat.SubjectPublicKeyInfo).decode() == pub, '키 쌍 불일치'
    c = json.load(open(CONF))
    camp, groups, cneg = build(c, start=dt.datetime(2026, 11, 10, 9, 0))
    assert camp['status'] == 'PAUSED' and camp['countriesOrRegions'] == ['KR']
    assert int(camp['dailyBudgetAmount']['amount']) * c['days'] <= int(camp['budgetAmount']['amount'])
    assert groups[0][0]['endTime'] == '2026-11-24T09:00:00.000'
    assert all(not g[0]['automatedKeywordsOptIn'] for g in groups[:2]) and groups[2][0]['automatedKeywordsOptIn']
    assert len(groups[2][2]) == sum(len(g['keywords']) for g in c['adGroups'][:2])  # 발굴 그룹이 정확 일치 키워드를 빼앗지 않게
    try:
        build(dict(c, dailyBudget=c['totalBudget']))
        raise AssertionError('예산 상한 검사가 안 걸렸다')
    except ValueError:
        pass
    print(f'selftest OK — 서명·검증·키 쌍 일치, 예산 ₩{c["dailyBudget"]:,}×{c["days"]}일 ≤ ₩{c["totalBudget"]:,}, '
          f'광고 그룹 {len(groups)}개·키워드 {sum(len(g[1]) for g in groups)}개·제외 {len(cneg)}개')


def cmd_acls(_):
    s, d = call('GET', '/acls', org=False)
    rows = ok(s, d, 'acls')
    for r in rows:
        print(r.get('orgId'), r.get('orgName'), r.get('currency'), r.get('timeZone'), r.get('paymentModel'), r.get('roleNames'))
    st = state()
    if rows and not st.get('orgId'):
        st['orgId'] = rows[0]['orgId']
        save_state(st)
        print('orgId 저장:', rows[0]['orgId'])


def cmd_plan(_):
    camp, groups, cneg = build(json.load(open(CONF)))
    print(json.dumps({'campaign': camp, 'campaignNegatives': cneg,
                      'adGroups': [{'adGroup': a, 'keywords': k, 'negatives': n} for a, k, n in groups]},
                     ensure_ascii=False, indent=1))


def cmd_launch(a):
    st = state()
    if st.get('campaignId'):
        sys.exit(f'이미 만든 캠페인이 있다: {st["campaignId"]} — status 로 확인')
    if not st.get('orgId'):
        cmd_acls(a)
        st = state()
    c = json.load(open(CONF))
    camp, groups, cneg = build(c)
    s, d = call('POST', '/campaigns', camp)
    if s == 400 and 'budgetAmount' in json.dumps(d):  # 총예산을 안 받는 계정이면 하루 예산 + 광고 그룹 종료일로만 묶는다
        camp.pop('budgetAmount')
        s, d = call('POST', '/campaigns', camp)
    cid = ok(s, d, '캠페인 생성')['id']
    st.update(campaignId=cid, adGroups={}, createdAt=dt.datetime.now().isoformat(timespec='seconds'), enabled=False)
    save_state(st)
    print('캠페인', cid, '(PAUSED)')
    ok(*call('POST', f'/campaigns/{cid}/negativekeywords/bulk', cneg), '캠페인 제외 키워드')
    for ag, kws, neg in groups:
        gid = ok(*call('POST', f'/campaigns/{cid}/adgroups', ag), f'광고 그룹 {ag["name"]}')['id']
        st['adGroups'][ag['name']] = gid
        save_state(st)
        ok(*call('POST', f'/campaigns/{cid}/adgroups/{gid}/targetingkeywords/bulk', kws), f'키워드 {ag["name"]}')
        if neg:
            ok(*call('POST', f'/campaigns/{cid}/adgroups/{gid}/negativekeywords/bulk', neg), f'그룹 제외 {ag["name"]}')
        print('광고 그룹', gid, ag['name'], f'키워드 {len(kws)}', f'제외 {len(neg)}')
    if a.enable:
        cmd_enable(a)


def _set_status(v):
    st = state()
    cid = st.get('campaignId') or sys.exit('캠페인이 없다 — launch 먼저')
    ok(*call('PUT', f'/campaigns/{cid}', {'campaign': {'status': v}}), f'캠페인 {v}')
    st['enabled'] = v == 'ENABLED'
    save_state(st)
    print('캠페인', cid, v)


def cmd_enable(_):
    _set_status('ENABLED')


def cmd_pause(_):
    _set_status('PAUSED')


def cmd_status(_):
    st = state()
    cid = st.get('campaignId') or sys.exit('캠페인이 없다')
    c = ok(*call('GET', f'/campaigns/{cid}'), '캠페인')
    print(c.get('name'), c.get('status'), c.get('servingStatus'), c.get('servingStateReasons'),
          '하루', (c.get('dailyBudgetAmount') or {}).get('amount'), '총', (c.get('budgetAmount') or {}).get('amount'))
    for g in ok(*call('GET', f'/campaigns/{cid}/adgroups?limit=50'), '광고 그룹'):
        print(' ', g['id'], g['name'], g.get('status'), g.get('servingStatus'), g.get('servingStateReasons'))


def _report(path, start, end):
    body = {'startTime': start, 'endTime': end, 'timeZone': 'ORTZ', 'returnRowTotals': True, 'returnGrandTotals': True,
            'returnRecordsWithNoMetrics': False,
            'selector': {'orderBy': [{'field': 'localSpend', 'sortOrder': 'DESCENDING'}],
                         'pagination': {'offset': 0, 'limit': 1000}}}
    s, d = call('POST', path, body)
    return ok(s, d, f'리포트 {path}').get('reportingDataResponse', {})


def _m(t):
    sp = (t.get('localSpend') or {}).get('amount', '0')
    inst = t.get('totalInstalls', t.get('installs', t.get('tapInstalls', 0)))
    return f"노출 {t.get('impressions', 0)} · 탭 {t.get('taps', 0)} · 설치 {inst} · 지출 ₩{float(sp):,.0f}"


def cmd_report(a):
    st = state()
    cid = st.get('campaignId') or sys.exit('캠페인이 없다')
    end = dt.date.today()
    start = end - dt.timedelta(days=a.days - 1)
    s, e = start.isoformat(), end.isoformat()
    out = {'range': [s, e], 'campaigns': _report('/reports/campaigns', s, e),
           'keywords': _report(f'/reports/campaigns/{cid}/keywords', s, e),
           'searchterms': _report(f'/reports/campaigns/{cid}/searchterms', s, e)}
    os.makedirs(REPORTS, exist_ok=True)
    json.dump(out, open(os.path.join(REPORTS, f'{end}.json'), 'w'), ensure_ascii=False, indent=1)
    g = (out['campaigns'].get('grandTotals') or {}).get('total') or {}
    print(f'{s}~{e} 합계: {_m(g)}')
    for r in out['keywords'].get('row', [])[:15]:
        md = r.get('metadata', {})
        print(f"  [{md.get('adGroupName', '')}] {md.get('keyword')} ({md.get('matchType')}) — {_m(r.get('total', {}))}")
    print('  검색어:')
    for r in out['searchterms'].get('row', [])[:15]:
        md = r.get('metadata', {})
        print(f"    {md.get('searchTermText') or '(작은 값)'} — {_m(r.get('total', {}))}")


def cmd_call(a):
    s, d = call(a.method.upper(), a.path, json.loads(a.body) if a.body else None)
    print(s, json.dumps(d, ensure_ascii=False, indent=1)[:4000])


def main():
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest='cmd', required=True)
    for n in ('keygen', 'selftest', 'acls', 'plan', 'status', 'enable', 'pause'):
        sp.add_parser(n)
    p = sp.add_parser('launch')
    p.add_argument('--enable', action='store_true')
    p = sp.add_parser('report')
    p.add_argument('--days', type=int, default=7)
    p = sp.add_parser('call')
    p.add_argument('method')
    p.add_argument('path')
    p.add_argument('body', nargs='?')
    a = ap.parse_args()
    globals()['cmd_' + a.cmd](a)


if __name__ == '__main__':
    main()
