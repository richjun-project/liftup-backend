"""App Store 인앱 이벤트 만들기·이미지 올리기·심사 제출(2026-11-01, 4주 12회 챌린지).

이벤트 이름·짧은 설명은 App Store 검색 색인에 들어가고 이벤트 결과는 앱과 따로 순위가 매겨진다 —
앱 이름으로는 못 이기는 큰 검색어(筋トレ 初心者·헬스 초보·홈트)에 무료로 카드를 거는 길.
사용(앱 키): cd scripts/growth && kw run asc-liftupai -- python3 ../../growth/events/create_event.py <단계>
  create  — 이벤트 + 언어별 문구(이미 있으면 문구만 갱신)
  upload  — 언어별 카드(1920×1080)·상세(1080×1920) 이미지
  submit  — 심사 제출(reviewSubmissions, 이벤트만)
  status  — 상태 보기
상태(이벤트·현지화 id)는 같은 폴더 state.json.
"""
import hashlib, json, sys, urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts' / 'growth'))
import asc  # noqa: E402

HERE = Path(__file__).resolve().parent / '2026-11-challenge'
STATE = HERE / 'state.json'
APP = asc.APP
REF = '2026-11 4-week 12-workout challenge'

# 11월 3일(화) 0시 ~ 11월 30일 23:59 KST. 공개(예고)는 11월 2일 0시 KST 부터
SCHEDULE = {
    'publishStart': '2026-11-01T15:00:00Z',
    'eventStart': '2026-11-02T15:00:00Z',
    'eventEnd': '2026-11-30T14:59:00Z',
    'territories': ['KOR', 'JPN', 'USA', 'GBR', 'CAN', 'AUS', 'NZL', 'SGP', 'IRL', 'TWN', 'HKG'],
}

# 사실만: 첫 세션 4종목·30분은 서버 FirstSessionRule, 기록은 홈 연속 기록·통계 캘린더, AI PT 는 기록 조회 도구로 답한다
TEXT = {
    'ko': {
        'name': '헬스 초보 4주 운동 습관 챌린지',
        'shortDescription': '4주 동안 12번, 홈트도 헬스장도 AI PT와 함께',
        'longDescription': '목표는 4주 동안 12번(주 3회) 운동하기. 첫 운동은 4종목·30분 안으로 가볍게 시작해요. '
                           '운동한 날은 통계 캘린더와 연속 기록에 쌓이고, AI PT가 기록을 보고 다음 운동을 맞춰 줘요.',
    },
    'ja': {
        'name': '筋トレ初心者 4週間チャレンジ',
        'shortDescription': '4週間で12回。自宅でもジムでもAIトレーナーと筋トレ習慣',
        'longDescription': '目標は4週間で12回(週3回)。最初のトレーニングは4種目・30分以内で軽めにスタート。'
                           '記録は統計カレンダーと連続記録に残り、AIトレーナーが記録を見て次のメニューを調整します。',
    },
    'en-US': {
        'name': '4-Week Beginner Gym Challenge',
        'shortDescription': '12 workouts in 4 weeks with your AI trainer',
        'longDescription': 'Aim for 12 workouts in 4 weeks. Start short: 4 exercises, 30 min. '
                           'Your streak and stats calendar track each workout.',
    },
}
LIMITS = {'name': 30, 'shortDescription': 50, 'longDescription': 120}

# 이벤트 딥링크는 심사에 필수다. 앱에 자체 스킴이 아직 없어(1.1.36) 앱 전용으로 등록된 카카오 스킴을 쓴다 —
# 카카오 플러그인은 host 가 kakaolink 일 때만, 구글 로그인은 자기 콜백만 처리하고, iOS FlutterDeepLinkingEnabled=false 라
# 라우터에도 안 가서 앱이 그냥 열린다(홈: 연속 기록·오늘의 운동). 다음 앱 버전에 liftupai:// 를 넣으면 바꾼다.
DEEP_LINK = 'kakao7aeaf6d3fe22b126a274c5e2f8c2c2f2://challenge'


def check_text():
    for loc, t in TEXT.items():
        for k, n in LIMITS.items():
            if len(t[k]) > n:
                sys.exit(f'{loc}.{k} {len(t[k])}자 > {n}: {t[k]}')


def load():
    return json.loads(STATE.read_text()) if STATE.exists() else {}


def save(st):
    STATE.write_text(json.dumps(st, ensure_ascii=False, indent=2))


def ok(s, d, what):
    if s >= 300:
        sys.exit(f'{what} 실패 {s}: {json.dumps(d.get("errors", d), ensure_ascii=False)[:800]}')
    return d


def create():
    check_text()
    st = load()
    if 'event' not in st:
        body = {'data': {'type': 'appEvents', 'attributes': {
            'referenceName': REF, 'badge': 'CHALLENGE', 'primaryLocale': 'en-US', 'priority': 'HIGH',
            'purpose': 'ATTRACT_NEW_USERS', 'purchaseRequirement': 'NO_COST_ASSOCIATED',
            'deepLink': DEEP_LINK, 'territorySchedules': [SCHEDULE]},
            'relationships': {'app': {'data': {'type': 'apps', 'id': APP}}}}}
        d = ok(*asc.call('POST', '/v1/appEvents', body), 'appEvents POST')
        st['event'] = d['data']['id']
        save(st)
        print('event', st['event'])
    else:
        ok(*asc.call('PATCH', f"/v1/appEvents/{st['event']}", {'data': {'type': 'appEvents', 'id': st['event'], 'attributes': {
            'deepLink': DEEP_LINK, 'territorySchedules': [SCHEDULE]}}}), 'appEvents PATCH')
    st.setdefault('loc', {})
    existing = ok(*asc.call('GET', f"/v1/appEvents/{st['event']}/localizations?limit=50"), 'localizations GET')
    have = {x['attributes']['locale']: x['id'] for x in existing.get('data', [])}
    for loc, t in TEXT.items():
        if loc in have:
            ok(*asc.call('PATCH', f'/v1/appEventLocalizations/{have[loc]}',
                         {'data': {'type': 'appEventLocalizations', 'id': have[loc], 'attributes': t}}), f'{loc} PATCH')
            st['loc'][loc] = have[loc]
        else:
            d = ok(*asc.call('POST', '/v1/appEventLocalizations', {'data': {
                'type': 'appEventLocalizations', 'attributes': {'locale': loc, **t},
                'relationships': {'appEvent': {'data': {'type': 'appEvents', 'id': st['event']}}}}}), f'{loc} POST')
            st['loc'][loc] = d['data']['id']
        print(loc, st['loc'][loc])
    save(st)


def upload_one(loc_id, path, kind):
    data = path.read_bytes()
    d = ok(*asc.call('POST', '/v1/appEventScreenshots', {'data': {
        'type': 'appEventScreenshots',
        'attributes': {'fileName': path.name, 'fileSize': len(data), 'appEventAssetType': kind},
        'relationships': {'appEventLocalization': {'data': {'type': 'appEventLocalizations', 'id': loc_id}}}}}),
        f'{path.name} reserve')
    shot = d['data']
    for op in shot['attributes']['uploadOperations']:
        chunk = data[op['offset']:op['offset'] + op['length']]
        req = urllib.request.Request(op['url'], data=chunk, method=op['method'],
                                     headers={h['name']: h['value'] for h in op.get('requestHeaders', [])})
        urllib.request.urlopen(req, timeout=120).read()
    ok(*asc.call('PATCH', f"/v1/appEventScreenshots/{shot['id']}", {'data': {
        'type': 'appEventScreenshots', 'id': shot['id'], 'attributes': {'uploaded': True}}}), f'{path.name} commit')
    print(' ', path.name, shot['id'], hashlib.md5(data).hexdigest()[:8])


def upload():
    st = load()
    for loc, loc_id in st['loc'].items():
        have = ok(*asc.call('GET', f'/v1/appEventLocalizations/{loc_id}/appEventScreenshots'), 'shots GET')
        kinds = {x['attributes']['appEventAssetType'] for x in have.get('data', [])}
        for kind, suffix in (('EVENT_CARD', 'card'), ('EVENT_DETAILS_PAGE', 'details')):
            if kind in kinds:
                print(' ', loc, kind, '이미 있음')
                continue
            upload_one(loc_id, HERE / f'{loc}_{suffix}.png', kind)


def submit():
    st = load()
    # 앞서 실패한 제출이 남긴 빈 제출(READY_FOR_REVIEW)이 있으면 그걸 쓴다 — 앱당 열린 제출은 하나
    opened = ok(*asc.call('GET', f'/v1/apps/{APP}/reviewSubmissions?filter[platform]=IOS&filter[state]=READY_FOR_REVIEW'), 'open GET')
    if opened.get('data'):
        sub = opened['data'][0]['id']
    else:
        d = ok(*asc.call('POST', '/v1/reviewSubmissions', {'data': {
            'type': 'reviewSubmissions', 'attributes': {'platform': 'IOS'},
            'relationships': {'app': {'data': {'type': 'apps', 'id': APP}}}}}), 'reviewSubmissions POST')
        sub = d['data']['id']
    ok(*asc.call('POST', '/v1/reviewSubmissionItems', {'data': {
        'type': 'reviewSubmissionItems',
        'relationships': {'reviewSubmission': {'data': {'type': 'reviewSubmissions', 'id': sub}},
                          'appEvent': {'data': {'type': 'appEvents', 'id': st['event']}}}}}), 'item POST')
    ok(*asc.call('PATCH', f'/v1/reviewSubmissions/{sub}', {'data': {
        'type': 'reviewSubmissions', 'id': sub, 'attributes': {'submitted': True}}}), 'submit PATCH')
    st['submission'] = sub
    save(st)
    print('submitted', sub)


def status():
    st = load()
    d = ok(*asc.call('GET', f"/v1/appEvents/{st['event']}"), 'event GET')
    a = d['data']['attributes']
    print(a['eventState'], a['badge'], a['territorySchedules'])
    for loc, loc_id in st['loc'].items():
        sh = ok(*asc.call('GET', f'/v1/appEventLocalizations/{loc_id}/appEventScreenshots'), 'shots GET')
        print(loc, [(x['attributes']['appEventAssetType'], (x['attributes'].get('assetDeliveryState') or {}).get('state'))
                    for x in sh.get('data', [])])


if __name__ == '__main__':
    {'create': create, 'upload': upload, 'submit': submit, 'status': status, 'check': check_text}[sys.argv[1]]()
