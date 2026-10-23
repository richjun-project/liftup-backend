"""iOS 스토어 퍼널(노출 → 페이지 조회 → 첫 다운로드)을 국가·유입 경로별로 본다(2026-10-23).

App Store Connect 분석 리포트(관리자 키 필요):
  kw run asc-chrobox -- python3 scripts/growth/asc_funnel.py [--days 30] [--json]
- 일 단위 인스턴스를 growth/asc/ 에 받아 두고(이미 받은 건 다시 안 받는다) 날짜별로 최신 처리본을 쓴다.
  요청 두 개: ONE_TIME_SNAPSHOT(과거 전체, 10-21 처리) + ONGOING(매일 새 날짜).
- 리포트: Discovery and Engagement Standard(노출·탭·페이지 조회), Detailed(유입 앱·웹 = Source Info),
  App Downloads Standard(첫 다운로드·재다운로드).
- 주의: Apple 은 분석 공유에 동의한 기기만 세고 작은 값은 버린다 → 실제보다 적다. 비율·순위로 읽는다.
"""
import argparse, collections, csv, datetime as dt, gzip, json, os, sys, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import asc  # noqa: E402

REQUESTS = ['bb9946a6-ff5a-4b9d-b28a-4483b1466a5f', '36f01ae1-2a64-4030-8b7e-1a00983364ab']
REPORTS = {'engagement': 'r14', 'engagement_detail': 'r15', 'downloads': 'r3'}
CACHE = os.path.expanduser('~/Develop/liftupai/growth/asc')


def fetch():
    os.makedirs(CACHE, exist_ok=True)
    for rid in REQUESTS:
        for name, r in REPORTS.items():
            s, d = asc.call('GET', f'/v1/analyticsReports/{r}-{rid}/instances?filter[granularity]=DAILY&limit=200')
            if s != 200:
                print(f'인스턴스 조회 실패 {name} {s}', file=sys.stderr)
                continue
            for inst in d.get('data', []):
                pdate = inst['attributes']['processingDate']
                s2, d2 = asc.call('GET', f"/v1/analyticsReportInstances/{inst['id']}/segments")
                for k, seg in enumerate(d2.get('data', [])):
                    fn = f'{CACHE}/{name}_{pdate}_{rid[:8]}_{inst["id"][-8:]}_{k}.tsv'
                    if os.path.exists(fn):
                        continue
                    raw = urllib.request.urlopen(seg['attributes']['url'], timeout=120).read()
                    try:
                        raw = gzip.decompress(raw)
                    except OSError:
                        pass
                    with open(fn, 'wb') as f:
                        f.write(raw)


def rows(name):
    """날짜별로 가장 늦게 처리된 파일의 행만 쓴다(스냅샷과 매일 리포트가 겹치는 날 중복 방지)."""
    by_date = {}
    for fn in sorted(f for f in os.listdir(CACHE) if f.startswith(name + '_2')):
        pdate = fn[len(name) + 1:len(name) + 11]
        with open(f'{CACHE}/{fn}', newline='') as fh:
            per = collections.defaultdict(list)
            for r in csv.DictReader(fh, delimiter='\t'):
                per[r['Date']].append(r)
        for day, rs in per.items():
            if day not in by_date or by_date[day][0] <= pdate:
                by_date[day] = (pdate, rs)
    for day in sorted(by_date):
        yield from by_date[day][1]


def summarize(days):
    lo = (dt.date.today() - dt.timedelta(days=days)).isoformat()
    funnel = collections.defaultdict(collections.Counter)
    for r in rows('engagement'):
        if r['Date'] >= lo:
            funnel[(r['Territory'], r['Source Type'])][r['Event']] += int(r['Unique Counts'] or 0)
    for r in rows('downloads'):
        if r['Date'] >= lo and r['Download Type'] in ('First-time download', 'Redownload'):
            funnel[(r['Territory'], r['Source Type'])]['First-time download' if r['Download Type'] == 'First-time download' else 'Redownload'] += int(r['Counts'] or 0)
    referrers = collections.Counter()
    for r in rows('engagement_detail'):
        if r['Date'] >= lo and r['Source Type'] in ('App referrer', 'Web referrer') and r['Event'] == 'Page view':
            referrers[(r['Territory'], r['Source Type'], r['Source Info'] or '(가려짐)')] += int(r['Unique Counts'] or 0)
    last = max((r['Date'] for r in rows('engagement')), default=None)
    out = {'since': lo, 'last_date': last, 'by_territory_source': [], 'referrers': []}
    for (t, s), c in sorted(funnel.items(), key=lambda x: -(x[1]['Impression'] + x[1]['Page view'] * 10)):
        out['by_territory_source'].append({'territory': t, 'source': s, 'impressions': c['Impression'], 'taps': c['Tap'],
                                           'page_views': c['Page view'], 'first_downloads': c['First-time download'],
                                           'redownloads': c['Redownload']})
    for (t, s, info), n in referrers.most_common(20):
        out['referrers'].append({'territory': t, 'source': s, 'info': info, 'page_views': n})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--days', type=int, default=30)
    ap.add_argument('--json', action='store_true')
    ap.add_argument('--no-fetch', action='store_true')
    a = ap.parse_args()
    if not a.no_fetch:
        fetch()
    out = summarize(a.days)
    if a.json:
        print(json.dumps(out, ensure_ascii=False))
        return
    print(f"iOS 스토어 퍼널 — {out['since']} ~ {out['last_date']} (분석 동의 기기만, 실제보다 적다)")
    print(f"{'국가':4} {'경로':20} {'노출':>6} {'탭':>4} {'페이지':>6} {'첫DL':>5} {'재DL':>4}")
    for x in out['by_territory_source']:
        if x['impressions'] + x['page_views'] + x['first_downloads'] == 0:
            continue
        print(f"{x['territory']:4} {x['source']:20} {x['impressions']:6} {x['taps']:4} {x['page_views']:6} {x['first_downloads']:5} {x['redownloads']:4}")
    print('유입 앱·웹(페이지 조회):')
    for x in out['referrers']:
        print(f"  {x['territory']} {x['source']} {x['info']}: {x['page_views']}")


if __name__ == '__main__':
    main()
