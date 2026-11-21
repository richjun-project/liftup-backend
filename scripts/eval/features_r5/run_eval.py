#!/usr/bin/env python3
"""채팅으로 다 되게 — 새 행동 도구 선택 실측(2026-11-21 ceo-r5, quality_r5 실행기 복사) — 서버처럼 검색 2회 상한·교체 인자 별칭을 흉내 낸다.
원래 설명: 모델 실측 평가 실행기(2026-11-21 model-design-r3) — 운영 서버에서 돈다(Gemini 키가 운영 서버 IP 전용).

  python3 run_eval.py <capture_dir> <out.jsonl> <models,comma> <repeats>
키는 환경변수 GEMINI_API_KEY. 캡처(ModelEvalCaptureTest)의 첫 요청 본문을 후보 모델마다 보내고, 모델이 부른 도구에는
캡처된 실제 도구 결과를 돌려준다(행동 도구는 기대 인자로 실제 실행한 결과, 그 밖의 인자는 같은 모양의 응답).
에이전트 루프와 같게: 최대 5회, 마지막 회 또는 13초가 지나면 도구 끄기(toolConfig NONE). 대화마다 한 줄 JSON.
"""
import json, os, sys, time, glob, threading, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor

KEY = os.environ['GEMINI_API_KEY']
cap_dir, out_path, models, repeats = sys.argv[1], sys.argv[2], sys.argv[3].split(','), int(sys.argv[4])
MAX_ROUNDS, SOFT_BUDGET = 5, 13.0
lock = threading.Lock()


def call(model, body):
    url = f'https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={KEY}'
    data = json.dumps(body).encode()
    for attempt in range(3):
        t = time.time()
        try:
            req = urllib.request.Request(url, data=data, headers={'content-type': 'application/json'})
            r = json.load(urllib.request.urlopen(req, timeout=60))
            return r, time.time() - t, None
        except urllib.error.HTTPError as e:
            msg = e.read().decode()[:200]
            if e.code in (429, 500, 503) and attempt < 2:
                time.sleep(4 * (attempt + 1)); continue
            return None, time.time() - t, f'HTTP {e.code} {msg}'
        except Exception as e:
            if attempt < 2:
                time.sleep(3); continue
            return None, time.time() - t, str(e)[:200]


def tool_response(sc, name, args):
    tr, mut, days, names = sc['tool_results'], sc['mutating_results'], sc['days'], sc['exercise_names']
    if name in tr:
        return tr[name]
    if name == 'set_today_workout_day':
        d = days.get(str(args.get('day')))
        if not d:
            return {'ok': False, 'error': 'bad_arguments', 'detail': f"day must be between 1 and {len(days)}"}
        return {'ok': True, 'changed': args.get('day') != 1, 'previous_day': 1, **d}
    if name in mut:
        return mut[name]
    if name == 'set_available_equipment':
        return {'ok': True, 'changed': True, 'equipment': args.get('equipment'), 'plan': "their plan already fits this equipment — nothing in it had to change"}
    if name == 'swap_exercise_in_plan':
        pick = lambda *ks: next((str(args[k]) for k in ks if args.get(k) not in (None, '', 0)), 'None')
        f = pick('from_exercise_id', 'old_exercise_id', 'current_exercise_id', 'exercise_id', 'from')
        t = pick('to_exercise_id', 'new_exercise_id', 'replacement_exercise_id', 'target_exercise_id', 'to')
        if f not in names:
            return {'ok': False, 'error': 'refused', 'detail': 'that exercise is not in the active plan'}
        return {'ok': True, 'changed': True, 'from': names[f], 'to': names.get(t, f'#{t}'), 'today_session_changed': True}
    if name == 'set_workout_time':
        return {'ok': True, 'changed': True, 'workout_time': args.get('time'), 'reminder_at': '30 minutes before'}
    if name == 'set_coach_messages':
        return {'ok': True, 'changed': True, 'coach_messages': 'on' if args.get('enabled') else 'off'}
    # 11-21 새 행동 도구 — 캡처에서 실제로 실행하지 않은 것은 같은 모양으로 흉내(외부 추정 호출이 있어 캡처 때 못 돌림)
    if name == 'log_meal':
        return {'ok': True, 'changed': True, 'meal_log_id': 501, 'meal_type': args.get('meal_type') or 'lunch', 'meal': args.get('description', '')[:40],
                'items': [args.get('description', '')[:40]], 'portion': '1 serving', 'calories_kcal': 640, 'protein_g': 28, 'carbs_g': 78, 'fat_g': 21,
                'today': {'consumed_kcal': 1790, 'target_kcal': 2000, 'remaining_kcal': 210, 'protein_g': '88/120'},
                'note': 'Numbers are estimates. If they correct the amount, call delete_meal_log with this meal_log_id, then log_meal again.'}
    if name == 'delete_meal_log':
        return {'ok': True, 'changed': True, 'removed_meal_log_id': args.get('meal_log_id', 501)}
    if name == 'log_completed_workout':
        ex = args.get('exercises') or []
        sets = sum(len(e.get('sets') or []) for e in ex if isinstance(e, dict))
        if not ex or not sets:
            return {'ok': False, 'error': 'bad_arguments', 'detail': 'exercises with sets are required'}
        return {'ok': True, 'changed': True, 'session_id': 9001, 'total_sets': sets, 'total_volume_kg': int(sum((s.get('weight_kg') or 0) * (s.get('reps') or 0) for e in ex for s in (e.get('sets') or []))), 'exercises': len(ex)}
    if name in ('update_my_profile', 'pause_or_resume_plan', 'change_plan', 'undo_last_coach_change'):
        return {'ok': True, 'changed': True}
    if name == 'remember':
        return {'ok': True, 'changed': True, 'memory_id': 101, 'fact': args.get('fact')}
    if name == 'forget':
        return {'ok': True, 'changed': True}
    return {'ok': False, 'error': 'unknown_tool'}


def run_one(model, sc, rep):
    req = sc['request']
    contents = json.loads(json.dumps(req['contents']))
    rec = {'model': model, 'scenario': sc['id'], 'rep': rep, 'calls': [], 'final_text': None, 'error': None}
    searches = 0
    started = time.time()
    for rnd in range(1, MAX_ROUNDS + 1):
        forced = rnd == MAX_ROUNDS or (time.time() - started) > SOFT_BUDGET
        body = {'contents': contents, 'tools': req['tools'],
                'toolConfig': {'functionCallingConfig': {'mode': 'NONE' if forced else 'AUTO'}},
                'generationConfig': req['generationConfig']}
        r, dt, err = call(model, body)
        if err:
            rec['error'] = err; break
        cand = (r.get('candidates') or [{}])[0]
        content = cand.get('content') or {}
        parts = content.get('parts') or []
        u = r.get('usageMetadata', {})
        fcs = [p['functionCall'] for p in parts if 'functionCall' in p]
        rec['model_version'] = r.get('modelVersion')
        rec['calls'].append({'round': rnd, 'ms': round(dt * 1000), 'finish': cand.get('finishReason'), 'forced': forced,
                             'prompt_tokens': u.get('promptTokenCount', 0), 'thoughts': u.get('thoughtsTokenCount', 0) or 0,
                             'out': u.get('candidatesTokenCount', 0) or 0,
                             'tool_calls': [{'name': f.get('name'), 'args': f.get('args', {})} for f in fcs]})
        if not fcs or forced:
            rec['final_text'] = ''.join(p.get('text', '') for p in parts if 'text' in p and not p.get('thought'))
            break
        content = dict(content); content['role'] = 'model'
        contents.append(content)
        responses = []
        for f in fcs:
            if f.get('name') == 'search_exercises':
                searches += 1
            if f.get('name') == 'search_exercises' and searches > 2:  # 서버 SearchExercisesTool 과 같은 상한·문구
                result = {'ok': False, 'error': 'search_limit', 'detail': 'You already searched 2 times in this reply. Do not search again. Pick the best fitting exercise from the earlier results and call swap_exercise_in_plan, or name 2-3 of those options to the user and ask which one they want.'}
            else:
                result = tool_response(sc, f.get('name'), f.get('args', {}) or {})
            fr = {'name': f.get('name'), 'response': {'result': result}}
            if f.get('id'): fr['id'] = f['id']
            responses.append({'functionResponse': fr})
        contents.append({'role': 'user', 'parts': responses})
    rec['total_ms'] = round((time.time() - started) * 1000)
    with lock:
        with open(out_path, 'a') as fh:
            fh.write(json.dumps(rec, ensure_ascii=False) + '\n')
    return rec


scenarios = [json.load(open(f)) for f in sorted(glob.glob(os.path.join(cap_dir, '*.json')))]
jobs = [(m, sc, rep) for rep in range(1, repeats + 1) for sc in scenarios for m in models]
open(out_path, 'w').close()
with ThreadPoolExecutor(max_workers=4) as ex:
    done = 0
    for rec in ex.map(lambda j: run_one(*j), jobs):
        done += 1
        if done % 20 == 0:
            print(f'{done}/{len(jobs)}', flush=True)
print(f'DONE {len(jobs)}', flush=True)
