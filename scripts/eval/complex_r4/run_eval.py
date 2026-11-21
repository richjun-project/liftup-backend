#!/usr/bin/env python3
"""복잡한 일 모델 실측(2026-11-21 complex-model-r4) — 운영 서버에서 돈다(Gemini 키가 운영 서버 IP 전용).

  python3 run_eval.py <capture_dir> <out.jsonl> <models,comma> <repeats>
키는 환경변수 GEMINI_API_KEY. 캡처(ComplexModelEvalTest.capture)의 프롬프트·생성 설정을 서버와 똑같은 본문으로
(callGeminiAPI: contents 한 조각 + nonChatGenerationConfig) 후보 모델마다 보낸다. 서버처럼 thinking 수준을 거절하면
thinking 없이 한 번 더 보낸다(shouldRetryWithoutThinking). 호출마다 한 줄 JSON.
"""
import json, os, sys, time, glob, threading, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor

KEY = os.environ['GEMINI_API_KEY']
cap_dir, out_path, models, repeats = sys.argv[1], sys.argv[2], sys.argv[3].split(','), int(sys.argv[4])
lock = threading.Lock()


def post(model, body, timeout):
    url = f'https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={KEY}'
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={'content-type': 'application/json'})
    return json.load(urllib.request.urlopen(req, timeout=timeout))


def call(model, body, timeout):
    for attempt in range(3):
        t = time.time()
        try:
            return post(model, body, timeout), time.time() - t, None
        except urllib.error.HTTPError as e:
            msg = e.read().decode()[:300]
            if e.code == 400 and 'thinking' in msg.lower() and 'thinkingConfig' in body['generationConfig']:
                body = json.loads(json.dumps(body)); body['generationConfig'].pop('thinkingConfig')
                continue
            if e.code in (429, 500, 503) and attempt < 2:
                time.sleep(5 * (attempt + 1)); continue
            return None, time.time() - t, f'HTTP {e.code} {msg}'
        except Exception as e:
            if attempt < 2:
                time.sleep(3); continue
            return None, time.time() - t, str(e)[:200]
    return None, 0, 'retries exhausted'


def run_one(model, case, rep):
    body = {'contents': [{'parts': [{'text': case['prompt']}]}], 'generationConfig': case['generationConfig']}
    r, dt, err = call(model, body, 240 if case['kind'] == 'plan' else 120)
    rec = {'model': model, 'case': case['id'], 'kind': case['kind'], 'rep': rep, 'ms': round(dt * 1000), 'error': err,
           'text': None, 'finish': None, 'model_version': None, 'in': 0, 'out': 0, 'think': 0}
    if r is not None:
        cand = (r.get('candidates') or [{}])[0]
        parts = (cand.get('content') or {}).get('parts') or []
        u = r.get('usageMetadata', {})
        rec.update(text=''.join(p.get('text', '') for p in parts if 'text' in p and not p.get('thought')),
                   finish=cand.get('finishReason'), model_version=r.get('modelVersion'),
                   **{'in': u.get('promptTokenCount', 0) or 0, 'out': u.get('candidatesTokenCount', 0) or 0,
                      'think': u.get('thoughtsTokenCount', 0) or 0})
    with lock:
        with open(out_path, 'a') as fh:
            fh.write(json.dumps(rec, ensure_ascii=False) + '\n')
    return rec


cases = [json.load(open(f)) for f in sorted(glob.glob(os.path.join(cap_dir, '*.json')))]
jobs = [(m, c, rep) for rep in range(1, repeats + 1) for c in cases for m in models]
open(out_path, 'w').close()
with ThreadPoolExecutor(max_workers=5) as ex:
    done = 0
    for rec in ex.map(lambda j: run_one(*j), jobs):
        done += 1
        if done % 10 == 0:
            print(f'{done}/{len(jobs)}', flush=True)
print(f'DONE {len(jobs)}', flush=True)
