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
