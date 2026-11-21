#!/usr/bin/env node
// 새 행동 도구를 실제 모델이 고르는지(features_r5): 규칙 채점 14/14 + 시나리오마다 기대 도구를 2회 모두 실제로 부름.
// 양성 대조: 도구를 부르지 않은 가짜 결과는 걸려야 한다.
import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url'; import { execFileSync } from 'node:child_process';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const rows = fs.readFileSync(path.join(HERE, 'data/results.jsonl'), 'utf8').split('\n').filter(Boolean).map(JSON.parse);
const WANT = { ko_meal_text: 'log_meal', en_weight_now: 'update_my_profile', ja_knee_injury: 'update_my_profile', ko_easier_plan: 'get_plan_options',
  ko_outside_workout: 'log_completed_workout', en_undo_change: 'undo_last_coach_change' };
const called = (r) => r.calls.flatMap((c) => c.tool_calls.map((t) => t.name));
const check = (rs) => Object.entries(WANT).every(([sc, tool]) => { const x = rs.filter((r) => r.scenario === sc); return x.length === 3 && x.every((r) => called(r).includes(tool)); });
const fake = rows.map((r) => r.scenario === 'ko_meal_text' ? { ...r, calls: [] } : r);
if (check(fake)) { console.log('CONTROL_FAILED'); process.exit(1); }
if (rows.length !== 21 || rows.some((r) => r.error)) { console.log('RUN_INCOMPLETE ' + rows.length); process.exit(1); }
if (!check(rows)) { console.log('TOOL_NOT_CHOSEN'); process.exit(1); }
const g = execFileSync('node', [path.join(HERE, 'grade.mjs')], { encoding: 'utf8' });
const m = g.match(/\| gemini-3\.8-flash \| (\d+)\/(\d+)/);
if (!m || m[1] !== m[2]) { console.log('RULE_GRADE_NOT_FULL'); process.exit(1); }
const over = rows.filter((r) => r.total_ms > 21000).length;
if (over) { console.log('TOO_SLOW ' + over); process.exit(1); }
console.log(`FEATURE_TOOLS_OK rules=${m[1]}/${m[2]} tools=${Object.keys(WANT).length}x3`);
