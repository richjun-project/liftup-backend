| 모델 | 통과 | required | forbidden | args | language | noDeflection | grounded | startButton | noTools | 전체 p50/p95 초 | 첫 호출 p50 | 21초 초과 | 채팅 1건 $ (지금 / 인상 뒤) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| gemini-3.8-flash | 41/42 (98%) | 42/42 | 42/42 | 42/42 | 41/42 | 15/15 | 21/21 | 6/6 | 3/3 | 5.8/12.2 | 2.8 | 1/42 | 0.0123 / 0.0246 (2027-01-01) |

시나리오별 통과(2회 중):

| 시나리오 | gemini-3.8-flash |
|---|---|
| en_getting_stronger | 3/3 |
| en_protein_general | 3/3 |
| en_pushup_too_easy | 3/3 |
| ja_equipment_none | 3/3 |
| ja_greeting | 3/3 |
| ja_pull_day | 3/3 |
| ja_stop_messages | 3/3 |
| ja_weekends_busy | 3/3 |
| ko_bench_history | 3/3 |
| ko_legpress_opinion | 3/3 |
| ko_lowback_deadlift | 3/3 |
| ko_protein_today | 3/3 |
| ko_swap_squat_knee | 2/3 |
| ko_workout_time | 3/3 |

실패 이유:

- gemini-3.8-flash — ko_swap_squat_knee#3: empty answer; language

토큰(대화 평균): gemini-3.8-flash 입력 15162·출력+생각 248·호출 1.9
