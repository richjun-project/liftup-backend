"""집중 부위 A/B 채점(11-22): 같은 프롬프트에서 규칙 9 만 다르게(예전 '최소 하나' vs 새 '몫 N') — 모델이 고른 종목 중 집중 부위 종목 수.
부위 판정은 서버 FocusArea.hits 와 같다: LEGS=category LEGS, GLUTES=muscles 에 GLUTES, ARMS=category ARMS.
  python3 score.py → 표 + FOCUS_AB_OK(새 규칙이 몫을 채운 비율 ≥ 예전 + 0.2 이고 ≥ 0.8)"""
import json, re, collections, sys
cap = {}
for f in ['en_inter_f_glutes_knee', 'ko_beginner_16_bulk', 'ja_inter_f_home_shoulder']:
    p = json.load(open(f'capture/reco__{f}.json'))['prompt']
    cand = {}
    for m in re.finditer(r'\[ID: (\d+)\][^\n]*?category=(\w+)[^\n]*?muscles=([A-Z_, ]+)', p):
        cand[int(m.group(1))] = (m.group(2), set(x.strip() for x in m.group(3).split(',')))
    areas = [a.strip().upper() for a in re.search(r'Focus areas: (.*)', p).group(1).split(',')]
    q = re.search(r'at least (\d+) of the exercises', p); quota = int(q.group(1)) if q else 1
    cap[f] = (cand, areas, quota)
def hits(area, cat, mus): return cat == 'LEGS' if area == 'LEGS' else ('GLUTES' in mus if area == 'GLUTES' else cat == area)
rows = collections.defaultdict(list)
for l in open('results.jsonl'):
    r = json.loads(l)
    kind, pid = r['case'].split('__')
    cand, areas, quota = cap[pid]
    try: ex = json.loads(r['text'].replace('```json', '').replace('```', '').strip())['exercises']
    except Exception: rows[(kind, pid)].append(('parse', 0, quota, False)); continue
    ids = [int(e['id']) for e in ex if str(e.get('id', '')).isdigit()]
    n = sum(1 for i in ids if i in cand and any(hits(a, *cand[i]) for a in areas))
    each = all(any(i in cand and hits(a, *cand[i]) for i in ids) for a in areas)
    first3 = any(i in cand and any(hits(a, *cand[i]) for a in areas) for i in ids[:3])
    rows[(kind, pid)].append((r["model"], n, quota, n >= quota))
ok = {}
for kind in ['recoold', 'reco']:
    met = tot = 0
    for pid in cap:
        rs = rows[(kind, pid)]
        met += sum(1 for x in rs if x[3]); tot += len(rs)
        print(f"{kind:8} {pid:26} quota={cap[pid][2]} focus counts={[x[1] for x in rs]} met={sum(1 for x in rs if x[3])}/{len(rs)}")
    ok[kind] = met / tot
    print(f"{kind} quota met rate = {met}/{tot} = {ok[kind]:.2f}\n")
if ok['reco'] >= 0.8 and ok['reco'] >= ok['recoold'] + 0.2: print('FOCUS_AB_OK')
else: sys.exit(1)
