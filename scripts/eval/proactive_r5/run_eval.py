#!/usr/bin/env python3
"""먼저 말 걸기 실제 모델 확인(2026-11-21 ceo-r5) — 운영 서버에서. GeminiAIService.generateCoachMessage 와 같은 설정으로 보낸다.
  python3 run_eval.py <capture_dir> <out.jsonl> <model> <repeats>"""
import json, os, sys, time, glob, urllib.request, urllib.error
KEY = os.environ['GEMINI_API_KEY']
cap, out, model, reps = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
cfg = {'temperature': 0.6, 'maxOutputTokens': 8192, 'topP': 0.95, 'topK': 40, 'thinkingConfig': {'thinkingLevel': 'low'}}
open(out, 'w').close()
for rep in range(1, reps + 1):
    for f in sorted(glob.glob(os.path.join(cap, '*.json'))):
        c = json.load(open(f)); t = time.time(); rec = {'case': c['id'], 'rep': rep, 'text': None, 'error': None}
        try:
            req = urllib.request.Request(f'https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={KEY}',
                data=json.dumps({'contents': [{'parts': [{'text': c['prompt']}]}], 'generationConfig': cfg}).encode(), headers={'content-type': 'application/json'})
            r = json.load(urllib.request.urlopen(req, timeout=60))
            parts = ((r.get('candidates') or [{}])[0].get('content') or {}).get('parts') or []
            rec['text'] = ''.join(p.get('text', '') for p in parts if not p.get('thought'))
        except Exception as e:
            rec['error'] = str(e)[:200]
        rec['ms'] = round((time.time() - t) * 1000)
        with open(out, 'a') as fh: fh.write(json.dumps(rec, ensure_ascii=False) + '\n')
print('DONE')
