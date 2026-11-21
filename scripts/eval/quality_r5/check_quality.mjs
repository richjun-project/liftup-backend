#!/usr/bin/env node
// 6-a·b·c 실제 모델 확인(quality_r5 결과): 규칙 채점 전부 통과 + 통증 질문은 2회 모두 오늘 플랜 도구를 부르고 '절대/never/絶対' 단정이 없음
// + 모든 대화에서 검색 2회 이하. 양성 대조: 예전 결과(model_r3 3.8-flash)는 통증 검사에서 걸려야 한다.
import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url'; import { execFileSync } from 'node:child_process';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const rows = (f, m) => fs.readFileSync(f, 'utf8').split('\n').filter(Boolean).map(JSON.parse).filter((r) => !m || r.model === m);
const tools = (r) => r.calls.flatMap((c) => c.tool_calls.map((t) => t.name));
const ABS = /절대|never|絶対|決して/i;
function painOk(rs) { const p = rs.filter((r) => r.scenario === 'ko_lowback_deadlift'); return p.length === 2 && p.every((r) => tools(r).some((t) => t === 'get_today_workout' || t === 'get_plan_overview') && !ABS.test(r.final_text || '')); }
const neu = rows(path.join(HERE, 'data/results.jsonl'));
const old = rows(path.join(HERE, '../model_r3/data/results.jsonl'), 'gemini-3.8-flash');
if (painOk(old)) { console.log('CONTROL_FAILED — 예전 결과가 통증 검사를 통과하면 검사가 무의미'); process.exit(1); }
if (neu.length !== 28 || neu.some((r) => r.error)) { console.log('RUN_INCOMPLETE ' + neu.length); process.exit(1); }
if (!painOk(neu)) { console.log('PAIN_RULE_FAILED'); process.exit(1); }
const over = neu.filter((r) => tools(r).filter((t) => t === 'search_exercises').length > 3); // 상한 2 + 거절된 1번까지 허용
if (over.length) { console.log('SEARCH_LOOP ' + over.map((r) => r.scenario).join(',')); process.exit(1); }
const g = execFileSync('node', [path.join(HERE, 'grade.mjs')], { encoding: 'utf8' });
const m = g.match(/\| gemini-3\.8-flash \| (\d+)\/(\d+)/);
if (!m || m[1] !== m[2]) { console.log('RULE_GRADE_NOT_FULL ' + (m ? m[0] : '?')); process.exit(1); }
console.log(`QUALITY_RULES_OK rules=${m[1]}/${m[2]} pain=2/2 searches<=2`);
