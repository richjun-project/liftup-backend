"""App Store 검색어별 우리 앱 순위와 상위 10의 경쟁 강도를 본다(2026-10-25, 키 불필요).

  python3 scripts/growth/aso_rank.py [--out growth/aso/<날짜>.json] [--only kr|jp|us]
- iTunes Search API(itunes.apple.com/search, 국가별 최대 50개)로 실제 App Store 검색을 근사한다.
  실제 순위와 똑같지는 않다 — 같은 검색어를 날짜별로 비교하는 용도. 분당 20회 제한이라 3초 간격.
- weak10 = 상위 10 중 평점 수 200 미만인 앱 수. 클수록 새 앱이 10위 안에 들 여지가 있다.
- 기준선 growth/aso/2026-10-25.json(1.1.33 이름·부제·키워드 바꾸기 전). 바꾼 뒤 1~2주에 다시 잰다.
"""
import argparse, datetime as dt, json, os, sys, time, urllib.parse, urllib.request

APP = 6752910787
TERMS = {
    "us": ["ai personal trainer", "ai fitness coach", "ai workout planner", "ai gym coach", "ai workout", "ai trainer",
           "personal trainer", "workout planner", "gym workout tracker", "workout log", "ai coach", "gym coach",
           "workout generator", "ai workout generator", "lifting log", "home workout planner", "fitness coach",
           "workout tracker", "ai gym trainer", "ai personal coach", "ai strength coach", "ai workout coach",
           "ai fitness trainer", "ai pt", "ai workout tracker", "ai lifting", "ai gym", "liftup"],
    "kr": ["AI PT", "AI 트레이너", "헬스 루틴", "운동 루틴", "운동일지", "헬스", "PT", "홈트", "운동 계획", "AI 운동",
           "AI 헬스", "헬스 트레이너", "운동 기록", "퍼스널 트레이닝", "헬스 일지", "오운완", "운동 앱", "AI 코치",
           "AI 피티", "AI 퍼스널 트레이너", "AI 헬스 트레이너", "헬스 PT", "AI 운동 루틴", "헬스 코치", "AI 운동 추천",
           "AI 홈트", "리프트업"],
    "jp": ["AI パーソナルトレーナー", "AIトレーナー", "筋トレ", "筋トレ 記録", "パーソナルジム", "ジム", "ワークアウト",
           "筋トレメニュー", "AI コーチ", "トレーニング記録", "自宅トレ", "パーソナルトレーナー", "筋トレ アプリ", "AI 筋トレ",
           "フィットネス", "AI パーソナルジム", "AI ジム", "AI フィットネス", "筋トレ コーチ", "筋トレ トレーナー",
           "AI 運動", "AIパーソナルトレーナー", "AI トレーニング", "筋トレ 相談", "リフトアップ"],
}


def search(term, country):
    url = "https://itunes.apple.com/search?" + urllib.parse.urlencode(
        {"term": term, "country": country, "entity": "software", "limit": 50})
    for _ in range(3):
        try:
            return json.load(urllib.request.urlopen(url, timeout=20)).get("results", [])
        except Exception:
            time.sleep(6)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.expanduser(f"~/Develop/liftupai/growth/aso/{dt.date.today()}.json"))
    ap.add_argument("--only")
    a = ap.parse_args()
    out = {}
    for country, terms in TERMS.items():
        if a.only and country != a.only:
            continue
        for t in terms:
            res = search(t, country)
            if res is None:
                print(f"{country}\t{t}\t실패", file=sys.stderr)
                continue
            pos = next((i + 1 for i, r in enumerate(res) if r.get("trackId") == APP), None)
            weak = sum(1 for r in res[:10] if r.get("userRatingCount", 0) < 200)
            top = [[r.get("trackName", "")[:40], r.get("userRatingCount", 0)] for r in res[:5]]
            out[f"{country}:{t}"] = {"pos": pos, "n": len(res), "weak_top10": weak, "top": top}
            print(f"{country}\t{t}\tpos={pos}\tweak10={weak}", flush=True)
            time.sleep(3.2)
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    json.dump(out, open(a.out, "w"), ensure_ascii=False, indent=1)
    ranked = sorted((v["pos"], k) for k, v in out.items() if v["pos"])
    print(f"\n50위 안: {len(ranked)}/{len(out)} — " + ", ".join(f"{k} {p}위" for p, k in ranked))


if __name__ == "__main__":
    main()
