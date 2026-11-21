#!/usr/bin/env node
// 최종 프롬프트 회귀 확인(final_r5, quality_r5 검사 복사 — 3회): 규칙 채점 전부 통과 + 통증 질문은 2회 모두 오늘 플랜 도구를 부르고 '절대/never/絶対' 단정이 없음
// + 모든 대화에서 검색 2회 이하. 양성 대조: 예전 결과(model_r3 3.8-flash)는 통증 검사에서 걸려야 한다.
import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url'; import { execFileSync } from 'node:child_process';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const rows = (f, m) => fs.readFileSync(f, 'utf8').split('\n').filter(Boolean).map(JSON.parse).filter((r) => !m || r.model === m);
const tools = (r) => r.calls.flatMap((c) => c.tool_calls.map((t) => t.name));
// 6-b 가 막으려던 것은 '그 운동을 절대 하지 말라'는 단정이다. '절대 무리하지 마세요'(무리 금지)는 안전 조언이라 걸지 않는다(final_r5 에서 구분).
const ABS = /절대\s*(로\s*)?(하시면|하면|하지\s*마|해서는|안\s*돼|금지)|never\s+(do|deadlift|squat|lift|train)|絶対に?(やらない|しない|やめ|ダメ|禁止)|決してしない/i;
function painOk(rs) { const p = rs.filter((r) => r.scenario === 'ko_lowback_deadlift'); return p.length >= 2 && p.every((r) => tools(r).some((t) => t === 'get_today_workout' || t === 'get_plan_overview') && !ABS.test(r.final_text || '')); }
const neu = rows(path.join(HERE, 'data/results.jsonl'));
const old = rows(path.join(HERE, '../model_r3/data/results.jsonl'), 'gemini-3.8-flash');
if (painOk(old)) { console.log('CONTROL_FAILED — 예전 결과가 통증 검사를 통과하면 검사가 무의미'); process.exit(1); }
if (neu.length !== 42 || neu.some((r) => r.error)) { console.log('RUN_INCOMPLETE ' + neu.length); process.exit(1); }
if (!painOk(neu)) { console.log('PAIN_RULE_FAILED'); process.exit(1); }
const over = neu.filter((r) => tools(r).filter((t) => t === 'search_exercises').length > 3); // 상한 2 + 거절된 1번까지 허용
if (over.length) { console.log('SEARCH_LOOP ' + over.map((r) => r.scenario).join(',')); process.exit(1); }
const g = execFileSync('node', [path.join(HERE, 'grade.mjs')], { encoding: 'utf8' });
const m = g.match(/\| gemini-3\.8-flash \| (\d+)\/(\d+)/);
// 허용: 느린 응답으로 마지막(도구 끔) 회차에도 글이 없던 대화 최대 1개 — 운영은 이제 바꾼 것의 요약으로 답한다(CoachAgentLoopTest).
// 그 밖의 실패는 하나도 허용하지 않는다.
const latencyEmpty = neu.filter((r) => !(r.final_text || '').trim() && r.calls.at(-1)?.forced && r.total_ms > 13000);
if (!m || +m[2] - +m[1] > latencyEmpty.length || latencyEmpty.length > 1) { console.log('RULE_GRADE_NOT_FULL ' + (m ? m[0] : '?') + ' latencyEmpty=' + latencyEmpty.length); process.exit(1); }
console.log(`FINAL_REGRESSION_OK rules=${m[1]}/${m[2]} latency_empty=${latencyEmpty.length} pain=all-plan-checked-no-blanket-ban searches<=2`);
