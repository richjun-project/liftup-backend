#!/usr/bin/env node
// G2: 5개 모델 × 14 시나리오 × 2회가 모두 기록됐고 전송 오류가 5% 미만 → MODEL_EVAL_RUN_OK
import { readFileSync } from 'node:fs';
const rows = readFileSync(new URL('./data/results.jsonl', import.meta.url), 'utf8').trim().split('\n').map((l) => JSON.parse(l));
const models = ['gemini-3.8-flash', 'gemini-4-hydrogen', 'gemini-4-neon', 'gemini-4-carbon', 'gemini-4-argon'];
const problems = [];
const seen = new Set(rows.map((r) => `${r.model}|${r.scenario}|${r.rep}`));
const scen = [...new Set(rows.map((r) => r.scenario))];
if (scen.length !== 14) problems.push(`시나리오 ${scen.length}`);
for (const m of models) for (const s of scen) for (const rep of [1, 2]) if (!seen.has(`${m}|${s}|${rep}`)) problems.push(`없음 ${m} ${s} #${rep}`);
const errors = rows.filter((r) => r.error).length;
if (errors / rows.length >= 0.05) problems.push(`전송 오류 ${errors}/${rows.length}`);
for (const r of rows) {
  if (!r.error && !(r.calls || []).length) problems.push(`${r.model} ${r.scenario}: 호출 기록 없음`);
  if (!r.error && (r.calls || []).some((c) => typeof c.ms !== 'number' || typeof c.prompt_tokens !== 'number')) problems.push(`${r.model} ${r.scenario}: 지연·토큰 없음`);
  if (r.model_version && r.model_version !== r.model) problems.push(`${r.model} 이 ${r.model_version} 로 답함`);
}
if (problems.length) { console.error(problems.slice(0, 20).join('\n')); process.exit(1); }
console.log(`records=${rows.length} errors=${errors} models=${models.length}`);
console.log('MODEL_EVAL_RUN_OK');
