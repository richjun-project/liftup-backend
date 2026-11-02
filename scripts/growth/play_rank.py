#!/usr/bin/env python3
"""Google Play 검색 순위(첫 화면 상위 ~10개) — 2026-11-02, Play 새 제목 공개일 기준선용.

play.google.com 검색 HTML 에는 첫 화면 앱만 들어 있어(나머지는 스크립트로 더 불러옴) 순위는 1~10 위만 보인다.
같은 검색어를 날짜별로 비교하는 용도. 키 불필요, 3초 간격.
사용: python3 scripts/growth/play_rank.py [--out growth/aso/play-<날짜>.json]
"""
import argparse, datetime as dt, json, re, time, urllib.parse, urllib.request

PKG = 'com.richjun.liftupai'
UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36'
TERMS = {
    ('ko', 'KR'): ['리프트업', 'AI 트레이너', 'AI 퍼스널 트레이너', 'AI PT', '헬스 PT', '헬스 트레이너', 'AI 운동 루틴', '운동루틴', '헬스 운동 루틴', 'AI 헬스'],
    ('ja', 'JP'): ['LIFTUP', 'AIパーソナルトレーナー', 'AIトレーナー', 'パーソナルトレーナー', '筋トレ 記録', '筋トレ コーチ', '筋トレ 相談'],
    ('en', 'US'): ['LiftUp AI', 'AI personal trainer', 'AI trainer', 'AI gym coach'],
}


def search(term, hl, gl):
    url = 'https://play.google.com/store/search?' + urllib.parse.urlencode({'q': term, 'c': 'apps', 'hl': hl, 'gl': gl})
    html = urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=30).read().decode('utf-8', 'ignore')
    ids = []
    for m in re.finditer(r'/store/apps/details\?id=([A-Za-z0-9._]+)', html):
        if m[1] not in ids:
            ids.append(m[1])
    return ids


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out')
    a = ap.parse_args()
    out = {'date': dt.date.today().isoformat(), 'note': '첫 화면(최대 ~10위)만 보임. rank=None 은 첫 화면 밖', 'results': []}
    for (hl, gl), terms in TERMS.items():
        for t in terms:
            try:
                ids = search(t, hl, gl)
                rank = ids.index(PKG) + 1 if PKG in ids else None
                out['results'].append({'gl': gl, 'term': t, 'rank': rank, 'shown': len(ids), 'top3': ids[:3]})
                print(f'{gl} {t}: {rank} (첫 화면 {len(ids)})')
            except Exception as e:
                print(f'{gl} {t}: 오류 {e}')
            time.sleep(3)
    if a.out:
        json.dump(out, open(a.out, 'w'), ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()
