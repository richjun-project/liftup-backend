#!/usr/bin/env node
// 답변 품질 6-a·b·c 전후 비교 — 같은 시나리오의 예전 답(model_r3, gemini-3.8-flash 1회차)과 새 답(quality_r5 1회차)을 이름 가리고 나란히.
//   node judge_pair.mjs --prepare  → data/pair_blind.md, data/pair_key.json
//   node judge_pair.mjs --report   → data/pair_scores.json 을 읽어 전후 평균·선호를 data/pair_report.md 로, 기준을 넘으면 QUALITY_PAIR_OK
import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const DATA = path.join(HERE, 'data');
const jsonl = (f) => fs.readFileSync(f, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
const pick = (rows, model) => Object.fromEntries(rows.filter((r) => r.model === model && r.rep === 1).map((r) => [r.scenario, r]));
const toolsOf = (r) => r.calls.flatMap((c) => c.tool_calls.map((t) => t.name));

function prepare() {
  const old = pick(jsonl(path.join(HERE, '../model_r3/data/results.jsonl')), 'gemini-3.8-flash');
  const neu = pick(jsonl(path.join(DATA, 'results.jsonl')), 'gemini-3.8-flash');
  const key = {}; let md = '# AI 트레이너 답 비교 심사지 (어느 쪽이 새 버전인지 가림)\n\n';
  for (const id of Object.keys(neu).sort()) {
    const cap = JSON.parse(fs.readFileSync(path.join(DATA, 'capture', `${id}.json`), 'utf8'));
    const user = cap.request.contents.at(-1).parts.map((p) => p.text).join(' ').split('## User message').pop().trim();
    const today = cap.tool_results?.get_today_workout;
    const todayLine = today ? `오늘 플랜(도구 결과 요약): ${today.day_name ?? ''} — ${(today.exercises ?? []).map((e) => `${e.name} ${e.sets ?? ''}×${e.reps ?? e.target_reps ?? ''}${e.suggested_kg ? ' ' + e.suggested_kg + 'kg' : ''}`).join(', ')}` : '';
    const flip = (id.length + id.charCodeAt(0)) % 2 === 0;
    const [A, B] = flip ? [neu[id], old[id]] : [old[id], neu[id]];
    key[id] = { A: flip ? 'new' : 'old', B: flip ? 'old' : 'new' };
    md += `## ${id}\n사용자 메시지: ${user.slice(0, 300)}\n${todayLine}\n\n### A\n도구: ${toolsOf(A).join(', ') || '없음'}\n${A.final_text}\n\n### B\n도구: ${toolsOf(B).join(', ') || '없음'}\n${B.final_text}\n\n`;
  }
  fs.writeFileSync(path.join(DATA, 'pair_blind.md'), md);
  fs.writeFileSync(path.join(DATA, 'pair_key.json'), JSON.stringify(key, null, 1));
  console.log(`pair_blind.md ${Object.keys(key).length} pairs`);
}

function report() {
  const key = JSON.parse(fs.readFileSync(path.join(DATA, 'pair_key.json'), 'utf8'));
  const sc = JSON.parse(fs.readFileSync(path.join(DATA, 'pair_scores.json'), 'utf8'));
  const D = ['specificity', 'accuracy', 'safety', 'tone'];
  const sum = { old: Object.fromEntries(D.map((d) => [d, 0])), new: Object.fromEntries(D.map((d) => [d, 0])) };
  let prefNew = 0, n = 0;
  for (const [id, k] of Object.entries(key)) {
    const s = sc[id]; if (!s) { console.log('MISSING ' + id); process.exit(1); }
    for (const L of ['A', 'B']) for (const d of D) { const v = s[L]?.[d]; if (!Number.isInteger(v) || v < 1 || v > 5) { console.log(`BAD ${id} ${L}.${d}`); process.exit(1); } sum[k[L]][d] += v; }
    if (k[s.better] === 'new') prefNew++;
    n++;
  }
  const avg = (w, d) => sum[w][d] / n;
  let md = `# 답변 품질 6-a·b·c 전후 (gemini-3.8-flash, 시나리오 ${n}개, 이름 가린 비교)\n\n| | 구체성 | 정확성 | 안전 | 말투 |\n|---|---|---|---|---|\n`;
  for (const w of ['old', 'new']) md += `| ${w === 'old' ? '예전' : '새'} | ${D.map((d) => avg(w, d).toFixed(2)).join(' | ')} |\n`;
  md += `\n새 답이 더 낫다: ${prefNew}/${n}\n`;
  fs.writeFileSync(path.join(DATA, 'pair_report.md'), md);
  console.log(md);
  const ok = avg('new', 'specificity') > avg('old', 'specificity') && avg('new', 'safety') >= avg('old', 'safety') - 0.15 && avg('new', 'accuracy') >= avg('old', 'accuracy') - 0.15;
  console.log(ok ? `QUALITY_PAIR_OK specificity ${avg('old', 'specificity').toFixed(2)}→${avg('new', 'specificity').toFixed(2)}` : 'QUALITY_PAIR_NOT_BETTER');
  if (!ok) process.exit(1);
}
if (process.argv.includes('--prepare')) prepare(); else if (process.argv.includes('--report')) report(); else { console.log('--prepare | --report'); process.exit(1); }
