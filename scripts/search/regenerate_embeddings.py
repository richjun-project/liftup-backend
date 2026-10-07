#!/usr/bin/env python3
"""운동 임베딩 리소스(src/main/resources/catalog/exercise-embeddings.{bin,meta.json}) 재생성.

사용법은 scripts/search/README.md 참고. 요약:
  python3 scripts/search/regenerate_embeddings.py            # 카탈로그가 바뀐(해시가 다른) 운동만 다시 임베딩
  python3 scripts/search/regenerate_embeddings.py --all      # 전부 다시 임베딩
  python3 scripts/search/regenerate_embeddings.py --check    # 네트워크 없이 리소스가 최신인지만 확인(종료 코드 0/1)

Gemini 키는 환경변수 GEMINI_API_KEY 또는 --env-file 의 GEMINI_API_KEY= 줄에서 읽고, 출력·파일·로그에 절대 남기지 않는다.
키가 서버 IP 로 제한돼 있으면 HTTPS_PROXY(예: SSH 터널 경유 프록시)를 설정해서 실행한다.

문서 텍스트 레시피(RECIPE_VERSION)는 Kotlin 쪽 ExerciseEmbeddingText 와 글자 단위로 같아야 한다.
둘이 어긋나면 ExerciseVectorsTest 의 해시 검사가 실패한다(그게 이 검사의 목적이다).
"""
import argparse, hashlib, json, math, os, struct, sys, time, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
CATALOG = os.path.join(ROOT, 'src/main/resources/catalog/exercise-catalog.json')
OUT_BIN = os.path.join(ROOT, 'src/main/resources/catalog/exercise-embeddings.bin')
OUT_META = os.path.join(ROOT, 'src/main/resources/catalog/exercise-embeddings.meta.json')

MODEL = 'gemini-embedding-001'
DIM = 768
RECIPE_VERSION = 'v1'
QUANT = 'int8-per-vector-scale'
DESC_CHARS = 160


def name(e, loc):
    return next((t.get('name') or '' for t in e['translations'] if t['locale'] == loc), '')


def first_line(e, loc):
    ins = next(((t.get('instructions') or '') for t in e['translations'] if t['locale'] == loc), '')
    return ins.split('\n')[0][:DESC_CHARS]


def doc_text(e):
    """레시피 v1: 세 언어 이름 + 분류·장비·근육·동작 + 세 언어 설명문 첫 줄(160자)."""
    return (f"{name(e, 'en')} / {name(e, 'ja')} / {name(e, 'ko')}. Category: {e['category']}. Equipment: {e.get('equipment')}. "
            f"Muscles: {', '.join(e.get('muscleGroups', []))}. Pattern: {e.get('movementPattern')}. "
            f"{first_line(e, 'en')} {first_line(e, 'ja')} {first_line(e, 'ko')}")


def content_hash(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()[:16]


def read_key(env_file):
    k = os.environ.get('GEMINI_API_KEY')
    if k:
        return k.strip()
    if env_file and os.path.exists(env_file):
        for line in open(env_file, encoding='utf-8'):
            if line.startswith('GEMINI_API_KEY='):
                return line.split('=', 1)[1].strip().strip('"').strip("'")
    sys.exit('GEMINI_API_KEY 가 없습니다(환경변수 또는 --env-file).')


def embed_batch(texts, key, retries=5):
    url = f'https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:batchEmbedContents'
    body = {'requests': [{'model': f'models/{MODEL}', 'content': {'parts': [{'text': t}]},
                          'taskType': 'RETRIEVAL_DOCUMENT', 'outputDimensionality': DIM} for t in texts]}
    last = ''
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                         headers={'Content-Type': 'application/json', 'x-goog-api-key': key})
            with urllib.request.urlopen(req, timeout=90) as r:
                return [e['values'] for e in json.loads(r.read())['embeddings']]
        except Exception as ex:  # 키가 메시지에 섞이지 않게 걸러서만 보여 준다
            last = str(ex).replace(key, '<key>')[:160]
            print(f'  재시도 {attempt + 1}/{retries}: {last}', file=sys.stderr)
            time.sleep(2 + attempt * 3)
    sys.exit(f'임베딩 실패: {last}')


def normalize(v):
    n = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / n for x in v]


def quantize(v):
    """정규화된 벡터 → (int8 DIM 개, 스케일). 복원값 = q * scale. 최대 오차는 scale/2 ≈ 0.4% 수준이다."""
    m = max(abs(x) for x in v) or 1.0
    scale = m / 127.0
    return [max(-127, min(127, round(x / scale))) for x in v], scale


def load_existing():
    if not (os.path.exists(OUT_BIN) and os.path.exists(OUT_META)):
        return {}
    meta = json.load(open(OUT_META, encoding='utf-8'))
    if meta.get('model') != MODEL or meta.get('dim') != DIM or meta.get('recipe') != RECIPE_VERSION:
        return {}
    raw = open(OUT_BIN, 'rb').read()
    out = {}
    for i, ent in enumerate(meta['entries']):
        out[ent['slug']] = (ent['hash'], ent['scale'], raw[i * DIM:(i + 1) * DIM])
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--all', action='store_true', help='해시가 같아도 전부 다시 임베딩')
    ap.add_argument('--check', action='store_true', help='네트워크 없이 최신 여부만 확인')
    ap.add_argument('--env-file', default=os.path.join(ROOT, '.env'))
    args = ap.parse_args()

    catalog = json.load(open(CATALOG, encoding='utf-8'))
    texts = {e['slug']: doc_text(e) for e in catalog}
    hashes = {s: content_hash(t) for s, t in texts.items()}
    existing = {} if args.all else load_existing()
    todo = [e['slug'] for e in catalog if existing.get(e['slug'], (None,))[0] != hashes[e['slug']]]
    stale_extra = [s for s in existing if s not in texts]
    print(f'카탈로그 {len(catalog)}종, 다시 임베딩할 운동 {len(todo)}종, 카탈로그에서 사라진 운동 {len(stale_extra)}종')
    if args.check:
        sys.exit(1 if todo or stale_extra else 0)

    fresh = {}
    if todo:
        key = read_key(args.env_file)
        for i in range(0, len(todo), 100):
            chunk = todo[i:i + 100]
            vecs = embed_batch([texts[s] for s in chunk], key)
            for s, v in zip(chunk, vecs):
                if len(v) != DIM:
                    sys.exit(f'차원이 다릅니다: {len(v)}')
                q, scale = quantize(normalize(v))
                fresh[s] = (hashes[s], scale, bytes((x + 256) % 256 for x in q))
            print(f'  {min(i + 100, len(todo))}/{len(todo)}')

    entries, blob = [], bytearray()
    for e in catalog:
        s = e['slug']
        h, scale, raw = fresh.get(s) or existing[s]
        entries.append({'slug': s, 'hash': h, 'scale': float(f'{scale:.9g}')})
        blob += raw
    meta = {
        'model': MODEL, 'dim': DIM, 'taskType': 'RETRIEVAL_DOCUMENT', 'recipe': RECIPE_VERSION,
        'quantization': QUANT, 'normalized': True, 'descChars': DESC_CHARS,
        'binSha256': hashlib.sha256(bytes(blob)).hexdigest(), 'count': len(entries), 'entries': entries,
    }
    open(OUT_BIN, 'wb').write(bytes(blob))
    with open(OUT_META, 'w', encoding='utf-8') as f:
        json.dump(meta, f, ensure_ascii=False, indent=0, separators=(',', ':'))
        f.write('\n')
    print(f'쓰기 완료: {OUT_BIN} ({len(blob)} bytes), {OUT_META}')


if __name__ == '__main__':
    main()
