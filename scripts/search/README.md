# 운동 검색 — 의미(임베딩) 리소스

`src/main/resources/catalog/exercise-embeddings.bin` / `.meta.json` 은 카탈로그의 모든 운동을 Gemini 로 임베딩한 버전 관리 리소스다.
런타임은 이 파일만 읽는다(운동 임베딩을 위해 API 를 부르지 않는다). 질의 임베딩만 요청 시점에 부른다.

| 항목 | 값 |
| --- | --- |
| 모델 | `gemini-embedding-001`, taskType `RETRIEVAL_DOCUMENT`, 768차원 |
| 정규화 | L2 정규화 후 운동별 스케일로 int8 양자화 (복원값 = 바이트 × 스케일, 코사인 오차 < 0.01) |
| 파일 | `.bin` = 운동 수 × 768 바이트(메타의 `entries` 순서), `.meta.json` = 모델·차원·레시피 버전·SHA-256·운동별 `slug`·`hash`·`scale` |
| 문서 텍스트 레시피 | `v1` — 세 언어(en/ja/ko) 이름 + 분류·장비·근육·동작 + 세 언어 설명문 첫 줄(160자). 정의는 `regenerate_embeddings.py` 의 `doc_text()` 와 Kotlin `ExerciseEmbeddingText` 가 같다 |
| `hash` | 문서 텍스트의 SHA-256 앞 16자리. 카탈로그가 바뀌어 벡터가 낡았는지 가려낸다 |

## 측정 기록 (레시피 v1 을 고른 이유)

4번째 검증셋(462건, 이 브랜치의 튜닝용)에서 의미 검색 단독 hit@5 / 어휘 상위 10 + 의미 상위 30 RRF hit@5. 문서 텍스트만 바꿔서 비교했다.

| 레시피 | 의미 단독 | RRF |
| --- | --- | --- |
| **v1: 이름 3개 + 분류·장비·근육·동작 + 설명문 첫 줄 3개(160자)** | **0.816** | **0.944** |
| 설명문 첫 줄을 320자로 | 0.812 | 0.942 |
| 이름·메타만(설명문 없음) | 0.801 | 0.929 |
| 설명문 앞 두 줄(en·ja만, 300자) | — | 0.939 |
| v1 + 난이도·장소(집/헬스장) 문장 | 0.807 | 0.946 |
| v1 + ja/ko 분류·장비 표기 | 0.816 | 0.939 |

차이가 0.5%p 안팎이라 가장 단순한 v1 을 유지했다. 질의 임베딩은 `RETRIEVAL_QUERY`, 문서는 `RETRIEVAL_DOCUMENT` 로 비대칭이다.

융합(`HybridSearch.Config`) 기본값은 같은 셋과 이전 평가셋 다섯 개를 함께 보며 정했다: RRF k=60, 어휘 상위 5 + 의미 상위 50, 어휘 1위가 정확·단어 경계 이름 일치(점수 ≥ 160)면 1위 고정,
부상 조건이 풀린 질의는 어휘 가중치 ×2, 의미 호출은 자연어(3단어 이상·공백 없는 8글자 이상 CJK)이거나 사전이 못 알아들은 덩어리가 있을 때만.

## 언제 다시 만드나

카탈로그(`exercise-catalog.json`)의 이름·설명문·분류·장비·근육·동작을 바꾸거나 운동을 추가·삭제했을 때.
`ExerciseVectorsTest` 가 "벡터가 없는 slug"·"해시가 낡은 벡터"·"카탈로그에 없는 벡터"를 잡아 빌드를 실패시킨다.

## 다시 만들기

```sh
# 바뀐(해시가 다른) 운동만 다시 임베딩 — 보통 이것
python3 scripts/search/regenerate_embeddings.py

# 전부 다시(모델·레시피를 바꿨을 때)
python3 scripts/search/regenerate_embeddings.py --all

# 네트워크 없이 최신 여부만 확인(종료 코드 0 = 최신, 1 = 다시 만들어야 함)
python3 scripts/search/regenerate_embeddings.py --check
```

- Gemini 키는 환경변수 `GEMINI_API_KEY` 또는 `--env-file`(기본 `<repo>/.env`)의 `GEMINI_API_KEY=` 줄에서 읽는다. 출력·로그·파일에 남기지 않는다.
- 키가 운영 서버 IP 로 제한돼 있으면 SSH 터널 경유 프록시를 `HTTPS_PROXY` 로 준다(`HTTPS_PROXY=http://127.0.0.1:18888 python3 …`).
- 레시피나 모델을 바꾸면 `RECIPE_VERSION`(Python)과 `ExerciseEmbeddingText.RECIPE_VERSION`(Kotlin)을 함께 올리고 `--all` 로 다시 만든다.
- 다시 만든 뒤 `./gradlew test --tests '*ExerciseVectorsTest'` 와 검색 품질 평가(`search-eval.mjs --semantic`)를 돌린다.

## 런타임 설정

`application.yml` 의 `search.semantic.*` (환경변수 `SEMANTIC_SEARCH_ENABLED`). 기본 꺼짐, `prod`·`local` 프로파일은 켜짐, 테스트는 꺼짐.

| 키 | 기본 | 의미 |
| --- | --- | --- |
| `enabled` | false (prod/local true) | 끄면 어휘 검색만(오늘과 같다) |
| `timeout-ms` | 1200 | 질의 임베딩 한 번의 제한 시간 — 넘기면 어휘 결과로 간다 |
| `cache-size` | 2048 | 정규화한 질의 문자열 기준 LRU |
| `breaker-failures` / `breaker-open-ms` | 5 / 30000 | 연속 실패 시 차단기. 열린 동안은 호출하지 않고(다시 열릴 때마다 두 배, 최대 8배) 어휘 검색만 |

질의 임베딩 요청에는 질의 문자열만 담긴다(사용자 식별자 없음). 키는 `x-goog-api-key` 헤더로만 가고 로그·예외 메시지에 남기지 않는다.

# 운동 검색 — LLM 질의 해석 (5.1)

자연어 질의를 코치 눈으로 읽어 **영어 운동 이름 힌트(최대 6)와 제외 장비**를 얻고(`search/rewrite/QueryRewriter`, Gemini `generateContent`),
힌트마다 어휘 엔진으로 다시 검색해 하이브리드 결과에 RRF 로 섞는다(`HybridSearch` → `RewriteFusion`).
"野球の肩を守るトレ" → External Rotation, "역삼각형" → Lat Pulldown 처럼 사전·임베딩이 못 잇는 말을 잇는다.

## 파이프라인

1. 어휘 검색 → 하이브리드(의미) 융합(기존). 정확한 운동 이름·브라우즈·초성 질의는 여기서 끝(LLM 을 부르지 않는다).
2. 어휘 1위가 정확·단어 경계 이름 일치(점수 ≥ 160)인 짧은(≤2단어) 질의도 LLM 을 건너뛴다(`rewriteSkipScore`). 나머지는 모두 부른다.
3. LLM 해석과 질의 임베딩을 **병렬로** 가져온다(해석 시간 제한 2.5초, 기다림 상한 3초). 둘 중 하나가 실패해도 가진 것으로 진행한다.
4. 힌트별 어휘 검색(영어, 호출자의 카테고리·장비 필터 적용) → 결정적 분석기의 하드 조건(부정·부상·집·사물 장비 범위)을 통과한 상위 3개만 후보.
   하이브리드 상위 10(가중치 1)과 힌트 후보를 RRF(k=60, 힌트 i 번째 가중치 0.85^i, 힌트가 운동 이름과 정확히 같으면 그 1위 후보 ×2)로 섞고,
   LLM 이 낸 제외 장비를 거른다(거르면 비게 되면 거르지 않음). 어휘 1위가 정확 이름 일치면 1위로 고정.
5. 하드 조건이 LLM 보다 항상 우선한다: 힌트가 부정한 운동·장비·부상 금지 동작을 가리켜도 후보에서 빠진다.
6. 질의가 부정·군더더기뿐이라("no ab wheel, similar effect") 어휘가 붙들 긍정 신호가 없으면 하이브리드에서 어휘 순위(= 인기 순 목록)를 믿지 않는다(`SemanticConstraint.lexicalInformative`).

실패(꺼짐·키 없음·시간 초과·차단기 열림·잘못된 응답)는 모두 하이브리드 결과 그대로이고, 그 결과는 30초만 캐시한다.

## 프롬프트·모델 (평가와 운영이 같아야 한다)

- 프롬프트는 Kotlin `QueryRewriter.PROMPT_PREFIX` 하나다. 평가 도구(`search-eval.mjs --rewrite` 가 부르는 `rewrites.py` 의 `rewrite.PROMPT`)와 **글자까지 같다**.
  갈라지지 않았는지: `python3 scripts/search/check_rewrite_prompt.py <rewrite.py 경로>` → `REWRITE_PROMPT_SAME`.
- 프롬프트를 바꾸려면 Kotlin 상수와 평가 도구의 프롬프트를 같이 바꾸고, 위 점검을 통과시킨 뒤, 5번째 검증셋 외 이전 평가셋 전부를 다시 잰다.
- 모델 기본 `gemini-flash-lite-latest`(`QUERY_REWRITE_MODEL`). `thinkingConfig` 는 보내지 않는다(이 계열이 거부). 온도 0, `application/json`, `maxOutputTokens` 256.
- 5번째 검증셋(404건) 모델 비교(같은 프롬프트·같은 융합, 상위5/MRR): `gemini-flash-lite-latest` 0.955/0.861, `gemini-3.5-flash-lite` 0.955/0.863, `gemini-3.1-flash-lite` 0.946/0.851.
  지연은 터널 경유 p50 약 1.3초로 셋이 같았다 → 별칭을 쓰는 기본값 유지(모델 갱신을 따라간다).

## 설정

`application.yml` 의 `search.rewrite.*`: `enabled`(`QUERY_REWRITE_ENABLED`, 기본 꺼짐 · prod·local 켜짐 · 테스트 꺼짐), `model`, `timeout-ms`(2500), `cache-size`(4096),
`breaker-failures`(5), `breaker-open-ms`(30000, 실패 때마다 두 배, 최대 16배). 의미 검색(`search.semantic.enabled`)이 꺼져 있으면 해석도 쓰이지 않는다(하이브리드 위에서만 동작).
키는 `gemini.api-key` 를 그대로 쓰고 `x-goog-api-key` 헤더로만 보낸다. 질의 문자열 외에는 아무것도 보내지 않고, 키·질의는 로그에 남기지 않는다.

## 측정 기록 (5번째 검증셋 404건, 튜닝용 / 이전 평가셋 회귀)

| 단계 | 상위5 | MRR | 금지 장비 위반 |
| --- | --- | --- | --- |
| 하이브리드만 | 0.814 | 0.706 | 1 |
| + LLM 힌트(실험 설정, 하드 조건 미적용) | 0.950 | 0.809 | 3 |
| + 하드 조건 적용·정확 이름 보호·정확 이름 힌트 ×2·건너뛰기 | **0.955** | **0.861** | **0** |

이전 평가셋(상위5/MRR): 4번째 0.974/0.916, 3번째 0.992/0.953, 2번째 1.000/0.981, 적대적 0.988/0.861, 개발 0.993/0.935.
LLM 호출 비율(건너뛰기 적용): 개발 38%, 적대적 62%, 5번째 90%.

튜닝 도구: `RewriteTuneTest`(환경변수 `RW_TUNE_ROOT` 가 없으면 아무것도 안 한다). `<root>/<세트>/{eval,qvec,rewrites}.json` 을 두면 한 JVM 에서 융합 설정 여러 개를 한꺼번에 채점한다.
```sh
RW_TUNE_ROOT=<dir> RW_TUNE_SAFETY=<search-safety.json> ./gradlew cleanTest test --tests '*RewriteTuneTest' -i
```
