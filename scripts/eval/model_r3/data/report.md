| 모델 | 통과 | required | forbidden | args | language | noDeflection | grounded | startButton | noTools | 전체 p50/p95 초 | 첫 호출 p50 | 21초 초과 | 채팅 1건 $ (지금 / 인상 뒤) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| gemini-3.8-flash | 28/28 (100%) | 28/28 | 28/28 | 28/28 | 28/28 | 10/10 | 14/14 | 4/4 | 2/2 | 5.5/9.9 | 3.2 | 1/28 | 0.0082 / 0.0165 (2027-01-01) |
| gemini-4-neon | 28/28 (100%) | 28/28 | 28/28 | 28/28 | 28/28 | 10/10 | 14/14 | 4/4 | 2/2 | 13.0/23.5 | 5.2 | 4/28 | 0.0428 |
| gemini-4-hydrogen | 27/28 (96%) | 28/28 | 28/28 | 27/28 | 27/28 | 10/10 | 14/14 | 4/4 | 2/2 | 6.1/12.9 | 3.9 | 0/28 | 0.0187 |
| gemini-4-carbon | 27/28 (96%) | 27/28 | 28/28 | 27/28 | 27/28 | 10/10 | 14/14 | 4/4 | 2/2 | 7.8/24.2 | 3.7 | 3/28 | 0.0256 / 0.0512 (2026-12-01) |
| gemini-4-argon | 25/28 (89%) | 27/28 | 28/28 | 26/28 | 25/28 | 10/10 | 13/14 | 3/4 | 2/2 | 15.0/38.1 | 8.7 | 7/28 | 0.0215 / 0.0429 (2026-12-01) |

시나리오별 통과(2회 중):

| 시나리오 | gemini-3.8-flash | gemini-4-neon | gemini-4-hydrogen | gemini-4-carbon | gemini-4-argon |
|---|---|---|---|---|---|
| en_getting_stronger | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| en_protein_general | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| en_pushup_too_easy | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| ja_equipment_none | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| ja_greeting | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| ja_pull_day | 2/2 | 2/2 | 2/2 | 2/2 | 1/2 |
| ja_stop_messages | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| ja_weekends_busy | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| ko_bench_history | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| ko_legpress_opinion | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| ko_lowback_deadlift | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| ko_protein_today | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |
| ko_swap_squat_knee | 2/2 | 2/2 | 1/2 | 1/2 | 0/2 |
| ko_workout_time | 2/2 | 2/2 | 2/2 | 2/2 | 2/2 |

실패 이유:

- gemini-4-hydrogen — ko_swap_squat_knee#2: empty answer; args [{"day_number":1,"old_exercise_id":160,"new_exercise_id":951}]; language
- gemini-4-carbon — ko_swap_squat_knee#1: empty answer; missing [["swap_exercise_in_plan"]] got ["get_today_workout","search_exercises","remember","search_exercises","search_exercises","search_exercises","search_exercises","search_exercises"]; args []; language
- gemini-4-argon — ko_swap_squat_knee#1: empty answer; args [{"old_exercise_id":160,"new_exercise_id":951}]; language
- gemini-4-argon — ja_pull_day#2: empty answer; language; not grounded in tool data; no start button
- gemini-4-argon — ko_swap_squat_knee#2: empty answer; missing [["swap_exercise_in_plan"]] got ["get_today_workout","search_exercises","search_exercises","remember","search_exercises"]; args []; language

토큰(대화 평균): gemini-3.8-flash 입력 10266·출력+생각 145·호출 1.8 / gemini-4-neon 입력 10993·출력+생각 654·호출 1.9 / gemini-4-hydrogen 입력 10199·출력+생각 459·호출 1.8 / gemini-4-carbon 입력 11845·출력+생각 190·호출 2.0 / gemini-4-argon 입력 9822·출력+생각 183·호출 1.8
