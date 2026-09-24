#!/usr/bin/env python3
"""e2e 품질 평가 실행기.

  GEMINI_API_KEY=... python3 scripts/eval/run.py --base-url http://localhost:8081 --out scripts/eval/results/baseline.json
  python3 scripts/eval/run.py --only chat --limit 3 --out /tmp/eval-smoke.json     # 심판 없이 규칙만(키 없으면 자동 skip)
  python3 scripts/eval/run.py --only reco|plan|reg

시나리오마다 새 디바이스 계정을 만들고(가입 → 언어 설정 → 호출), 규칙 검사 → 심판 순으로 채점한다.
실패해도 계속 진행하고, 요약과 원자료를 JSON 으로 남긴다.
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import judge as J  # noqa: E402
import reco_rules as R  # noqa: E402
import rules as C  # noqa: E402
from common import ApiError, Client, wait_for_server  # noqa: E402

PROFILES = json.load(open(os.path.join(HERE, "scenarios", "profiles.json"), encoding="utf-8"))["profiles"]
CHAT = json.load(open(os.path.join(HERE, "scenarios", "chat.json"), encoding="utf-8"))["scenarios"]
PLAN_SUBSET = None  # None → 모든 프로필


def use_golden():
    """--set golden: 60 프로필 / 335 채팅 / 플랜 35 프로필."""
    global PROFILES, CHAT, PLAN_SUBSET
    gp = json.load(open(os.path.join(HERE, "scenarios", "golden_profiles.json"), encoding="utf-8"))
    PROFILES, PLAN_SUBSET = gp["profiles"], gp["plan_subset"]
    CHAT = json.load(open(os.path.join(HERE, "scenarios", "golden_chat.json"), encoding="utf-8"))["scenarios"]


def failed_ids(path: str) -> set[str]:
    """이전 결과에서 규칙 실패·에러·심판 hard_fail/평균 3.5 미만인 채팅 id."""
    prev = json.load(open(path, encoding="utf-8"))
    out = set()
    for r in prev.get("chat", []):
        j = r.get("judge") or {}
        if r.get("error") or not r.get("hard_pass") or j.get("hard_fail") or (j.get("mean") is not None and j["mean"] < 3.5):
            out.add(r["id"])
    return out

_print_lock = threading.Lock()


def log(msg):
    with _print_lock:
        print(msg, flush=True)


def _profile_for(scn) -> dict:
    p = dict(PROFILES[scn["profile"]])
    p["pt_style"] = scn["persona"]
    return p


# ── chat ──

def run_chat_scenario(base_url: str, scn: dict, with_judge: bool) -> dict:
    profile = _profile_for(scn)
    locale = scn.get("locale") or profile["locale"]
    injuries = list(profile.get("injuries") or []) + list(scn.get("injuries") or [])
    rec = {"id": scn["id"], "kind": "chat", "profile": scn["profile"], "persona": scn["persona"], "category": scn["category"],
           "locale": locale, "turns": [], "rules": [], "hard_pass": None, "judge": None, "error": None, "latency_ms": []}
    c = Client(base_url, timeout=180)
    try:
        c.register(profile, tag="ev-" + scn["id"])
        c.set_language(locale)
        convo = []
        for i, msg in enumerate(scn["turns"]):
            st, reply, raw, ms = c.chat(msg, locale=locale)
            rec["latency_ms"].append(ms)
            if st != 200 or reply is None:
                reply = ""
                rec["turns"].append({"user": msg, "assistant": None, "http": st, "raw": str(raw)[:300]})
            else:
                rec["turns"].append({"user": msg, "assistant": reply, "http": st})
            convo.append((msg, reply))
            ctx = {"reply": reply, "turn": msg, "locale": locale, "persona": scn["persona"], "profile": profile,
                   "category": scn["category"], "injuries": injuries, "turns": [t for t, _ in convo]}
            results = C.run_rules(ctx, scn["hard_rules"])
            rec["rules"].append([{"rule": r.rule, "passed": r.passed, "skipped": r.skipped, "detail": r.detail} for r in results])
        rec["hard_pass"] = all(r["passed"] for turn in rec["rules"] for r in turn)
        if with_judge and convo and convo[-1][1]:
            rec["judge"] = J.judge(profile, scn["persona"], scn["category"], locale, convo, scn.get("judge_focus"))
    except ApiError as e:
        rec["error"] = str(e)
    except Exception as e:  # 한 시나리오의 예외가 전체를 멈추지 않게
        rec["error"] = f"{type(e).__name__}: {e}"
    rec["gemini_calls"] = len(scn["turns"]) + (2 if rec.get("judge") and not rec["judge"].get("skipped") else 0)
    fails = [r["rule"] for turn in rec["rules"] for r in turn if not r["passed"]]
    log(f"[chat] {scn['id']:8} {scn['persona']:15} {scn['category']:20} {'PASS' if rec['hard_pass'] else 'FAIL'} {fails[:4]} {rec['error'] or ''}")
    return rec


# ── reco ──

class Resolver:
    """exercise_id → 카탈로그 존재 여부. 상세 API 로 확인하고 캐시한다."""

    def __init__(self, client: Client):
        self.c = client
        self.cache: dict[str, bool | None] = {}
        self.lock = threading.Lock()

    def __call__(self, eid) -> bool | None:
        key = str(eid)
        with self.lock:
            if key in self.cache:
                return self.cache[key]
        st, _, _ = self.c.exercise_details(eid)
        val = True if st == 200 else (False if st == 404 else None)
        with self.lock:
            self.cache[key] = val
        return val


def _score(exs, profile, target, meta, resolver):
    results = R.run_reco_rules(exs, profile, target, meta.get("duration"), resolver)
    return [{"rule": r.rule, "passed": r.passed, "skipped": r.skipped, "detail": r.detail} for r in results]


def run_reco_profile(base_url: str, pname: str) -> dict:
    profile = PROFILES[pname]
    locale = profile["locale"]
    rec = {"id": f"reco_{pname}", "kind": "reco", "profile": pname, "locale": locale, "basic": None, "personalized": None, "error": None}
    c = Client(base_url, timeout=180)
    try:
        c.register(profile, tag="ev-reco")
        c.set_language(locale)
        resolver = Resolver(c)
        target = profile.get("session_duration")
        for key, fn in (("basic", c.reco_basic), ("personalized", c.reco_personalized)):
            st, res, ms = fn(target, locale=locale)
            entry = {"http": st, "latency_ms": ms, "rules": [], "exercises": [], "hard_pass": None}
            if st == 200 and isinstance(res, dict):
                exs, meta = R.normalize_exercises(res, "reco")
                entry["exercises"] = [{"id": e["id"], "name": e["name"], "equipment": e["equipment"], "sets": e["sets"], "reps": e["reps"], "weight": e["weight"]} for e in exs]
                entry["rules"] = _score(exs, profile, target, meta, resolver)
                entry["hard_pass"] = all(r["passed"] for r in entry["rules"])
            else:
                entry["hard_pass"] = False
                entry["raw"] = str(res)[:300]
            rec[key] = entry
            fails = [r["rule"] for r in entry["rules"] if not r["passed"]]
            log(f"[reco] {pname:26} {key:12} HTTP {st} {ms}ms {'PASS' if entry['hard_pass'] else 'FAIL'} {fails}")
    except Exception as e:
        rec["error"] = f"{type(e).__name__}: {e}"
        log(f"[reco] {pname} ERROR {rec['error']}")
    return rec


# ── plan ──

def run_plan_profile(base_url: str, pname: str) -> dict:
    profile = PROFILES[pname]
    locale = profile["locale"]
    rec = {"id": f"plan_{pname}", "kind": "plan", "profile": pname, "locale": locale, "http": None, "latency_ms": None,
           "days": [], "balance": None, "hard_pass": None, "error": None}
    c = Client(base_url, timeout=240)
    try:
        c.register(profile, tag="ev-plan")
        c.set_language(locale)
        resolver = Resolver(c)
        st, res, ms = c.generate_ai_plan(profile, locale=locale)
        rec["http"], rec["latency_ms"] = st, ms
        if st not in (200, 201) or not isinstance(res, dict) or not res.get("success"):
            rec["hard_pass"] = False
            rec["raw"] = str(res)[:300]
            log(f"[plan] {pname:26} HTTP {st} {ms}ms FAIL (생성 실패)")
            return rec
        dash = res.get("data") or {}
        rec["plan_name"] = dash.get("plan_name")
        rec["total_days"] = dash.get("total_days")
        days_exs = []
        target = profile.get("session_duration")
        for d in dash.get("days") or []:
            n = d.get("day_number")
            st2, res2, ms2 = c.plan_day(n, locale=locale)
            entry = {"day": n, "http": st2, "rules": [], "exercises": []}
            if st2 == 200 and isinstance(res2, dict):
                exs, meta = R.normalize_exercises(res2, "plan_day")
                days_exs.append(exs)
                entry["exercises"] = [{"id": e["id"], "name": e["name"], "equipment": e["equipment"], "sets": e["sets"], "reps": e["reps"], "weight": e["weight"]} for e in exs]
                entry["rules"] = _score(exs, profile, target, meta, resolver)
            rec["days"].append(entry)
        bal = R.check_muscle_balance(days_exs)
        rec["balance"] = {"rule": bal.rule, "passed": bal.passed, "skipped": bal.skipped, "detail": bal.detail}
        day_pass = all(r["passed"] for d in rec["days"] for r in d["rules"]) and all(d["http"] == 200 for d in rec["days"])
        rec["hard_pass"] = day_pass and bal.passed and len(rec["days"]) == (profile.get("weekly_days") or len(rec["days"]))
        fails = sorted({r["rule"] for d in rec["days"] for r in d["rules"] if not r["passed"]} | ({bal.rule} if not bal.passed else set()))
        log(f"[plan] {pname:26} HTTP {st} {ms/1000:.0f}s days={len(rec['days'])} {'PASS' if rec['hard_pass'] else 'FAIL'} {fails}")
    except Exception as e:
        rec["error"] = f"{type(e).__name__}: {e}"
        log(f"[plan] {pname} ERROR {rec['error']}")
    return rec


# ── regression ──

def run_regressions(base_url: str) -> list[dict]:
    """코드로 확인된 회귀 케이스."""
    out = []
    # R-1: injuries 에 severity 없이 가입해도 201 이어야 한다(앱은 severity 를 보내지 않는다).
    c = Client(base_url, timeout=60)
    p = dict(PROFILES["knee_surgery"])
    req = {"device_id": "ev-reg-" + os.urandom(6).hex(), "nickname": "회귀검증", "experience_level": p["experience_level"], "goals": p["goals"],
           "pt_style": p["pt_style"], "body_info": p["body_info"], "injuries": [{"body_part": "knee"}], "notification_enabled": False}
    st, res, ms = c.call("POST", "/api/auth/device/register", req, retry=0)
    out.append({"id": "reg_register_injury_without_severity", "expected": 201, "http": st, "passed": st == 201, "detail": str(res)[:200] if st != 201 else ""})
    log(f"[reg ] register_injury_without_severity HTTP {st} {'PASS' if st == 201 else 'FAIL'}")
    return out


# ── summary ──

def pct(n, d):
    return round(100.0 * n / d, 1) if d else None


def summarize(chat, reco, plan, regs) -> dict:
    s = {}
    if chat:
        ok = [r for r in chat if r["error"] is None]
        s["chat"] = {"scenarios": len(chat), "errors": len(chat) - len(ok),
                     "hard_pass_rate": pct(sum(1 for r in ok if r["hard_pass"]), len(ok))}
        by_rule, by_cat, by_persona = {}, {}, {}
        for r in ok:
            for turn in r["rules"]:
                for x in turn:
                    if x["skipped"]:
                        continue
                    d = by_rule.setdefault(x["rule"], [0, 0]); d[1] += 1; d[0] += x["passed"]
            for key, dct in ((r["category"], by_cat), (r["persona"], by_persona)):
                d = dct.setdefault(key, [0, 0]); d[1] += 1; d[0] += bool(r["hard_pass"])
        s["chat"]["pass_rate_by_rule"] = {k: pct(v[0], v[1]) for k, v in sorted(by_rule.items())}
        s["chat"]["pass_rate_by_category"] = {k: pct(v[0], v[1]) for k, v in sorted(by_cat.items())}
        s["chat"]["pass_rate_by_persona"] = {k: pct(v[0], v[1]) for k, v in sorted(by_persona.items())}
        judged = [r["judge"] for r in ok if r.get("judge") and not r["judge"].get("skipped")]
        if judged:
            s["chat"]["judge"] = {ax: round(statistics.mean(j[ax] for j in judged), 2) for ax in J.AXES}
            s["chat"]["judge"]["mean"] = round(statistics.mean(j["mean"] for j in judged), 2)
            s["chat"]["judge"]["hard_fail_rate"] = pct(sum(1 for j in judged if j["hard_fail"]), len(judged))
            s["chat"]["judge"]["n"] = len(judged)
        lat = [ms for r in ok for ms in r["latency_ms"]]
        if lat:
            lat.sort()
            s["chat"]["latency_ms"] = {"p50": lat[len(lat) // 2], "p95": lat[int(len(lat) * 0.95) - 1 if len(lat) > 1 else 0], "n": len(lat)}
        s["chat"]["gemini_calls"] = sum(r.get("gemini_calls", 0) for r in chat)
    if reco:
        for key in ("basic", "personalized"):
            entries = [r[key] for r in reco if r.get(key)]
            by_rule = {}
            for e in entries:
                for x in e["rules"]:
                    if x["skipped"]:
                        continue
                    d = by_rule.setdefault(x["rule"], [0, 0]); d[1] += 1; d[0] += x["passed"]
            lat = sorted(e["latency_ms"] for e in entries)
            s[f"reco_{key}"] = {"profiles": len(entries), "hard_pass_rate": pct(sum(1 for e in entries if e["hard_pass"]), len(entries)),
                                "http_ok_rate": pct(sum(1 for e in entries if e["http"] == 200), len(entries)),
                                "pass_rate_by_rule": {k: pct(v[0], v[1]) for k, v in sorted(by_rule.items())},
                                "latency_ms_p50": lat[len(lat) // 2] if lat else None}
    if plan:
        by_rule = {}
        for r in plan:
            for d in r["days"]:
                for x in d["rules"]:
                    if x["skipped"]:
                        continue
                    dd = by_rule.setdefault(x["rule"], [0, 0]); dd[1] += 1; dd[0] += x["passed"]
            if r.get("balance") and not r["balance"]["skipped"]:
                dd = by_rule.setdefault("reco_muscle_balance", [0, 0]); dd[1] += 1; dd[0] += r["balance"]["passed"]
        lat = sorted(r["latency_ms"] for r in plan if r.get("latency_ms"))
        s["plan"] = {"profiles": len(plan), "generated_ok_rate": pct(sum(1 for r in plan if r["http"] in (200, 201)), len(plan)),
                     "hard_pass_rate": pct(sum(1 for r in plan if r["hard_pass"]), len(plan)),
                     "pass_rate_by_rule": {k: pct(v[0], v[1]) for k, v in sorted(by_rule.items())},
                     "latency_ms_p50": lat[len(lat) // 2] if lat else None}
    if regs:
        s["regressions"] = {r["id"]: ("PASS" if r["passed"] else f"FAIL(HTTP {r['http']})") for r in regs}
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8081")
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", choices=["chat", "reco", "plan", "reg"], default=None)
    ap.add_argument("--limit", type=int, default=None, help="chat 시나리오 / 프로필 수 제한")
    ap.add_argument("--concurrency", type=int, default=4)
    ap.add_argument("--no-judge", action="store_true")
    ap.add_argument("--label", default=None)
    ap.add_argument("--set", choices=["default", "golden"], default="default")
    ap.add_argument("--category", default=None, help="채팅 카테고리 필터(쉼표 구분)")
    ap.add_argument("--ids", default=None, help="채팅 id 필터(쉼표 구분)")
    ap.add_argument("--rerun-failed", default=None, help="이전 결과 JSON 에서 실패한 채팅만 다시 돈다")
    args = ap.parse_args()
    if args.set == "golden":
        use_golden()

    if not wait_for_server(args.base_url, max_wait_sec=600, interval=20):
        print("서버가 응답하지 않는다:", args.base_url)
        sys.exit(2)
    with_judge = (not args.no_judge) and bool(os.environ.get("GEMINI_API_KEY"))
    if not with_judge:
        log("심판(judge) 건너뜀: GEMINI_API_KEY 없음 또는 --no-judge")

    t0 = time.time()
    chat_res, reco_res, plan_res, regs = [], [], [], []
    want = lambda k: args.only in (None, k)  # noqa: E731

    if want("chat"):
        scns = CHAT
        if args.category:
            cats = set(args.category.split(","))
            scns = [x for x in scns if x["category"] in cats]
        if args.ids:
            wanted = set(args.ids.split(","))
            scns = [x for x in scns if x["id"] in wanted]
        if args.rerun_failed:
            wanted = failed_ids(args.rerun_failed)
            scns = [x for x in scns if x["id"] in wanted]
        scns = scns[: args.limit] if args.limit else scns
        with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
            futs = [ex.submit(run_chat_scenario, args.base_url, s, with_judge) for s in scns]
            chat_res = [f.result() for f in as_completed(futs)]
        chat_res.sort(key=lambda r: r["id"])
    if want("reco"):
        names = list(PROFILES)[: args.limit] if args.limit else list(PROFILES)
        with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
            reco_res = [f.result() for f in as_completed([ex.submit(run_reco_profile, args.base_url, n) for n in names])]
        reco_res.sort(key=lambda r: r["id"])
    if want("plan"):
        names = list(PLAN_SUBSET or PROFILES)
        names = names[: args.limit] if args.limit else names
        with ThreadPoolExecutor(max_workers=min(2, args.concurrency)) as ex:  # 플랜 생성은 45~75초 → 동시성 2
            plan_res = [f.result() for f in as_completed([ex.submit(run_plan_profile, args.base_url, n) for n in names])]
        plan_res.sort(key=lambda r: r["id"])
    if want("reg"):
        regs = run_regressions(args.base_url)

    summary = summarize(chat_res, reco_res, plan_res, regs)
    summary["elapsed_sec"] = round(time.time() - t0)
    summary["label"] = args.label or os.path.splitext(os.path.basename(args.out))[0]
    summary["judge_enabled"] = with_judge
    summary["set"] = args.set
    out = {"summary": summary, "chat": chat_res, "reco": reco_res, "plan": plan_res, "regressions": regs}
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("\n=== SUMMARY ===")
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    print("→", args.out)


if __name__ == "__main__":
    main()
