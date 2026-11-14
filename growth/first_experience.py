#!/usr/bin/env python3
"""첫 경험 리포트(2026-11-14 coach-autopilot-r1).

새 가입자마다 "온보딩 장비 → 받은 플랜 → 그 플랜의 불변 조건 위반 → 첫 세션(시간·세트·품질) → 코치 신호"를 한 줄로 만든다.
매일 루프가 손으로 하던 사람 단위 분석(296 '오늘 풀 데이', 304 '器具はありません')을 가입자마다 자동으로 남기려는 것.

  python3 -I growth/first_experience.py --users 296,304        # 특정 사용자
  python3 -I growth/first_experience.py --days 2                # 최근 N일 가입자(테스트 기기 이벤트가 있는 사용자 제외)
  python3 -I growth/first_experience.py --users 296,304 --check # 296·304 의 알려진 사실을 다시 만드는지 검사 → FIRST_EXPERIENCE_OK

운영 DB 는 kw(ssh-liftupai) → docker exec mysql 로 읽기만 한다. 불변 조건은 백엔드 PlanInvariants 의 규칙을 그대로 옮긴 것이다
(필요 도구는 src/main/resources/catalog/exercise-requires.json 을 같이 읽는다). 규칙을 바꾸면 두 곳을 같이 고친다.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REQUIRES = json.load(open(os.path.join(ROOT, 'src/main/resources/catalog/exercise-requires.json'), encoding='utf8'))['requires']

# ── PlanInvariants 와 같은 규칙 ─────────────────────────────────────────
REAL_PULL = {'PULLUP_CHINUP', 'INVERTED_ROW', 'BARBELL_ROW', 'DUMBBELL_ROW', 'CABLE_ROW', 'LAT_PULLDOWN', 'MACHINE_ROW'}
UPPER_BACK_EXTRA = {'FACE_PULL', 'REAR_DELT'}
UPPER_BACK_SLUGS = {'superman-pulls'}
PUSH_PREFIX = ('HORIZONTAL_PRESS', 'VERTICAL_PRESS', 'INCLINE_PRESS', 'DECLINE_PRESS')
BACK_DAYS = {'FULL_BODY', 'UPPER', 'PULL', 'BACK'}
PULL_DAYS = {'UPPER', 'PULL', 'BACK'}
GYM = {'BARBELL', 'MACHINE', 'CABLE', 'SMITH_MACHINE', 'GYM', 'FULL_GYM', 'ALL', 'FULL', 'COMMERCIAL_GYM', 'EVERYTHING'}
BAR = GYM | {'OTHER', 'BAR', 'PULL_UP_BAR', 'PULLUP_BAR'}
PULL_CAPABLE = {'DUMBBELL', 'RESISTANCE_BAND', 'CABLE', 'MACHINE', 'BARBELL', 'KETTLEBELL'}


def is_real_pull(e): return (e['pattern'] or '').upper() in REAL_PULL
def is_upper_back(e):
    p = (e['pattern'] or '').upper()
    return is_real_pull(e) or p in UPPER_BACK_EXTRA or e['slug'] in UPPER_BACK_SLUGS or (e['category'] == 'BACK' and p == 'OTHER')
def is_push(e):
    p = (e['pattern'] or '').upper()
    return e['category'] == 'CHEST' or p in ('PUSHUP', 'DIPS') or p.startswith(PUSH_PREFIX)


def available(req, eq):
    if not eq: return True
    if req in ('NONE', 'HOUSEHOLD', 'ANCHOR'): return True
    if req == 'PULLUP_BAR': return bool(eq & BAR)
    if req in ('DIP_BARS', 'LOW_BAR', 'GYM'): return bool(eq & GYM)
    if req == 'DOOR_ANCHOR': return 'RESISTANCE_BAND' in eq or bool(eq & GYM)
    return True


def requirement(e):
    if e['slug'] in REQUIRES: return REQUIRES[e['slug']]
    if e['equipment'] not in ('BODYWEIGHT', 'OTHER', None, ''): return 'NONE'
    p = (e['pattern'] or '').upper()
    if p == 'PULLUP_CHINUP': return 'PULLUP_BAR'
    if p == 'DIPS' and 'bench' not in e['slug'] and 'chair' not in e['slug']: return 'DIP_BARS'
    if 'hanging' in e['slug'] or 'muscle-up' in e['slug']: return 'PULLUP_BAR'
    return 'NONE'


def invariants(days, eq):
    out = []
    can_pull = (not eq) or bool(eq & PULL_CAPABLE) or bool(eq & BAR)
    for n, wtype, exs in days:
        for e in exs:
            if e['equipment'] in ('BODYWEIGHT', 'OTHER', None, '') and not available(requirement(e), eq):
                out.append(f"REQUIRES(d{n}:{e['slug']})")
            elif e['equipment'] not in ('BODYWEIGHT', 'OTHER', None, '') and eq and e['equipment'] not in eq:
                out.append(f"EQUIPMENT(d{n}:{e['slug']})")
        t = (wtype or '').upper()
        if t in BACK_DAYS and exs and not any(is_upper_back(e) for e in exs): out.append(f'UPPER_BACK(d{n})')
        if t in PULL_DAYS and can_pull and exs and not any(is_real_pull(e) for e in exs): out.append(f'REAL_PULL(d{n})')
    allx = [e for _, _, exs in days for e in exs]
    push, back = sum(map(is_push, allx)), sum(map(is_upper_back, allx))
    if push >= 2 and push > 2 * back: out.append(f'PUSH_BACK_RATIO({push}:{back})')
    return out


# ── 운영 DB ───────────────────────────────────────────────────────────
def remote_query(sql):
    """SQL(여러 문장)을 운영 MySQL 에 보내 TSV 행을 돌려준다. 문장마다 '##' 줄로 구분한다."""
    remote = (
        "docker exec -i liftupai-mysql-prod sh -c 'mysql -uroot -p\"$MYSQL_ROOT_PASSWORD\" liftupai_db "
        "--default-character-set=utf8mb4 -N -B 2>&1 | grep -v \"Using a password\"' <<'SQLEOF'\n" + sql + "\nSQLEOF\n"
    )
    with tempfile.NamedTemporaryFile('w', suffix='.sh', delete=False) as f:
        f.write(remote)
        path = f.name
    try:
        r = subprocess.run(
            ['kw', 'run', 'ssh-liftupai', '--', 'sh', '-c',
             'ssh -o BatchMode=yes -o ConnectTimeout=20 -i "$KW_SSH_LIFTUPAI" ubuntu@api.liftupai.net bash -s'],
            stdin=open(path), capture_output=True, text=True, timeout=120)
    finally:
        os.unlink(path)
    if r.returncode != 0 or 'ERROR ' in r.stdout:
        err = [l for l in r.stdout.splitlines() if l.startswith('ERROR')]
        sys.exit(f'remote query failed: {(err[0] if err else r.stderr.strip())[:300]}')
    sections, cur = [], []
    for line in r.stdout.splitlines():
        if line == '##':
            sections.append(cur); cur = []
        else:
            cur.append(line.split('\t'))
    sections.append(cur)
    return sections


def sep(sql): return sql.rstrip().rstrip(';') + ";\nSELECT '##';\n"


def user_ids_since(days):
    rows = remote_query(f"""
SELECT u.id FROM users u
WHERE u.join_date >= UTC_TIMESTAMP() - INTERVAL {int(days)} DAY
  AND NOT EXISTS (SELECT 1 FROM app_events t WHERE t.user_id = u.id AND t.test_device = 1)
ORDER BY u.id;""")[0]
    return [int(r[0]) for r in rows if r and r[0].isdigit()]


def report(uid):
    has_quality = "(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='liftupai_db' AND table_name='workout_sessions' AND column_name='data_quality')"
    sql = ''.join(sep(q) for q in [
        f"SELECT u.id, DATE_FORMAT(u.join_date,'%Y-%m-%d %H:%i'), COALESCE(s.language,''), COALESCE(p.experience_level,''), COALESCE(s.weekly_workout_days,''), COALESCE(s.workout_duration,'') "
        f"FROM users u LEFT JOIN user_settings s ON s.user_id=u.id LEFT JOIN user_profiles p ON p.user_id=u.id WHERE u.id={uid}",
        f"SELECT e.equipment FROM user_settings s JOIN user_available_equipment e ON e.settings_id=s.id WHERE s.user_id={uid}",
        f"SELECT e.equipment FROM user_profiles p JOIN user_equipment e ON e.profile_id=p.id WHERE p.user_id={uid}",
        f"SELECT id, source_type, COALESCE(source_id,''), status, DATE_FORMAT(created_at,'%Y-%m-%d %H:%i') FROM user_workout_plans WHERE user_id={uid} ORDER BY created_at",
        f"SELECT d.plan_id, d.day_number, d.workout_type, x.exercise_id, e.slug, COALESCE(e.category,''), COALESCE(e.movement_pattern,''), COALESCE(e.equipment,'') "
        f"FROM user_workout_plans pl JOIN user_plan_days d ON d.plan_id=pl.id JOIN user_plan_day_exercises x ON x.plan_day_id=d.id JOIN exercises e ON e.id=x.exercise_id "
        f"WHERE pl.user_id={uid} ORDER BY d.plan_id, d.day_number, x.order_in_day",
        f"SELECT s.id, DATE_FORMAT(s.start_time,'%Y-%m-%d %H:%i:%s'), COALESCE(DATE_FORMAT(s.end_time,'%Y-%m-%d %H:%i:%s'),''), s.status, "
        f"COALESCE(TIMESTAMPDIFF(SECOND, s.start_time, s.end_time),''), "
        f"(SELECT COUNT(*) FROM workout_exercises we JOIN exercise_sets es ON es.workout_exercise_id=we.id WHERE we.session_id=s.id AND es.completed=1) "
        f"FROM workout_sessions s WHERE s.user_id={uid} ORDER BY s.start_time LIMIT 5",
        f"SELECT {has_quality}",
        f"SELECT name, COALESCE(params,''), DATE_FORMAT(occurred_at,'%m-%d %H:%i') FROM app_events WHERE user_id={uid} AND "
        f"(name LIKE 'coach_%' OR name IN ('plan_invariant_violation','session_quality_flag','onboarding_plan_choice','template_apply_result')) ORDER BY occurred_at LIMIT 40",
    ])
    s = remote_query(sql)
    basic = s[0][0] if s[0] and s[0][0] and s[0][0][0] else None
    if not basic:
        return {'uid': uid, 'missing': True, 'line': f'{uid}: 사용자 없음'}
    eq_settings = {r[0] for r in s[1] if r and r[0]}
    eq_profile = {r[0] for r in s[2] if r and r[0]}
    eq = eq_settings or eq_profile
    plans = [r for r in s[3] if len(r) >= 5]
    by_plan = {}
    for r in s[4]:
        if len(r) < 8: continue
        pid, day, wtype = r[0], int(r[1]), r[2]
        by_plan.setdefault(pid, {}).setdefault((day, wtype), []).append(
            {'slug': r[4], 'category': r[5], 'pattern': r[6], 'equipment': r[7]})
    sessions = [r for r in s[5] if len(r) >= 6]
    quality_col = s[6] and s[6][0] and s[6][0][0] == '1'
    events = [r for r in s[7] if len(r) >= 3]

    plan_parts, plan_checks = [], {}
    for pl in plans:
        pid, src, code, status = pl[0], pl[1], pl[2], pl[3]
        days = [(d, w, ex) for (d, w), ex in sorted(by_plan.get(pid, {}).items())]
        v = invariants(days, eq)
        plan_checks[pid] = {'code': code, 'status': status, 'violations': v, 'days': days}
        plan_parts.append(f"{code or src}[{status[:3]}]" + (f" ⚠{','.join(v[:4])}{'…' if len(v) > 4 else ''}" if v else ' ✓'))
    sess_parts = []
    for r in sessions[:3]:
        secs = int(r[4]) if r[4].isdigit() else None
        sets = int(r[5]) if r[5].isdigit() else 0
        fast = secs is not None and sets >= 3 and secs < max(60, 10 * sets)
        sess_parts.append(f"{r[1][5:16]} {r[3][:4]} {sets}세트/{secs if secs is not None else '?'}초" + (' ⚠속도' if fast else ''))
    coach = [f"{n}{('(' + p + ')') if p else ''}" for n, p, _ in events if n.startswith('coach_') or n in ('plan_invariant_violation', 'session_quality_flag')]
    line = (f"{uid} 가입{basic[1][5:]} {basic[2] or '?'} {basic[3] or '?'} 주{basic[4] or '?'}·{basic[5] or '?'}분 "
            f"장비={','.join(sorted(eq)) or '없음(모름)'}"
            f"{' (설정≠프로필 ' + ','.join(sorted(eq_profile)) + ')' if eq_settings and eq_profile and eq_settings != eq_profile else ''} | "
            f"플랜 {' → '.join(plan_parts) or '없음'} | 세션 {'; '.join(sess_parts) or '없음'}"
            f"{' | 코치 ' + ' '.join(coach[:6]) if coach else ''}")
    return {'uid': uid, 'line': line, 'equipment': eq, 'plans': plan_checks, 'sessions': sessions, 'quality_col': quality_col}


def check(results):
    """296·304 에 대해 알려진 사실(GROWTH.md 11-13·11-14)을 리포트가 다시 만드는지."""
    by = {r['uid']: r for r in results}
    problems = []
    r304 = by.get(304)
    if not r304 or r304.get('missing'):
        problems.append('304 없음')
    else:
        # 11-13: 헬스장 분할(맨몸 사용자에게 바벨·머신 = 장비 위반) → 11-14 맞춤으로 맨몸 템플릿. 첫 세션: 15세트를 몇 초에.
        if 'BODYWEIGHT' not in r304['equipment']:
            problems.append(f"304 장비에 BODYWEIGHT 가 없다: {r304['equipment']}")
        gym_plan = [p for p in r304['plans'].values() if any(v.startswith(('EQUIPMENT', 'REQUIRES')) for v in p['violations'])]
        if not gym_plan:
            problems.append('304 의 헬스장 플랜(장비 위반)을 못 잡았다')
        fast = [s for s in r304['sessions'] if s[4].isdigit() and s[5].isdigit() and int(s[5]) >= 10 and int(s[4]) < 60]
        if not fast:
            problems.append('304 의 15세트 몇 초 세션을 못 잡았다')
    r296 = by.get(296)
    if not r296 or r296.get('missing'):
        problems.append('296 없음')
    elif not r296['plans']:
        problems.append('296 플랜 없음')
    for p in problems:
        print('FAIL', p)
    return not problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--users')
    ap.add_argument('--days', type=int)
    ap.add_argument('--check', action='store_true')
    a = ap.parse_args()
    uids = [int(x) for x in a.users.split(',')] if a.users else user_ids_since(a.days or 1)
    results = [report(u) for u in uids]
    for r in results:
        print(r['line'])
    if a.check:
        if check(results):
            print('FIRST_EXPERIENCE_OK')
        else:
            sys.exit(1)


if __name__ == '__main__':
    main()
