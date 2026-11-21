#!/usr/bin/env node
// 독립 품질 심사(2026-11-21 model-design-r3) — 모델 이름을 가린 답을 다른 계열 모델(Claude 하위 에이전트)이 매긴다.
//   node judge.mjs --prepare   → data/judge_blind.md(심사자가 읽을 파일)와 data/judge_key.json(가린 이름 ↔ 모델) 생성
//   node judge.mjs --check     → data/judge_scores.json 검사 + 모델별 평균 표 → JUDGE_OK
// 1회차만 쓴다(시나리오 14 × 모델 5 = 70개). 시나리오마다 답 순서를 섞는다(고정 시드).
import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const DIR = new URL('./data/', import.meta.url).pathname;
const capture = Object.fromEntries(readdirSync(join(DIR, 'capture')).filter((f) => f.endsWith('.json'))
  .map((f) => { const x = JSON.parse(readFileSync(join(DIR, 'capture', f), 'utf8')); return [x.id, x]; }));
const recs = readFileSync(join(DIR, 'results.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l)).filter((r) => r.rep === 1);
const LABELS = ['A', 'B', 'C', 'D', 'E'];
let seed = 20261121;
const rnd = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; };

function facts(sc) {
  const t = sc.tool_results || {};
  const pick = (o, keys) => Object.fromEntries(keys.filter((k) => o && k in o).map((k) => [k, o[k]]));
  const out = {
    today_workout: pick(t.get_today_workout, ['day', 'day_name', 'estimated_minutes', 'exercises']),
    exercise_history: pick(t.get_exercise_history, ['exercise', 'sessions', 'heaviest_set', 'best_est_1rm_kg']),
    nutrition_today: t.get_nutrition_today,
    action_result_if_called: sc.mutating_results,
  };
  return JSON.stringify(out, null, 0).slice(0, 2200);
}

if (process.argv.includes('--prepare')) {
  const key = {};
  let md = `# 답 품질 심사 자료 (모델 이름 가림)\n\n피트니스 앱 'LiftUp AI'의 AI 퍼스널 트레이너 답이다. 사용자가 고른 트레이너 말투(persona)가 있다. 시나리오마다 A~E 다섯 답을 매긴다.\n\n`;
  for (const id of Object.keys(capture).sort()) {
    const sc = capture[id];
    const rows = recs.filter((r) => r.scenario === id);
    const order = rows.map((r) => [rnd(), r]).sort((a, b) => a[0] - b[0]).map((x) => x[1]);
    key[id] = {};
    md += `## ${id}\n- 언어: ${sc.locale} · 트레이너 말투: ${sc.style}\n- 사용자 메시지: ${sc.message}\n- 앱이 가진 실제 데이터(도구 결과 요약): ${facts(sc)}\n\n`;
    order.forEach((r, i) => {
      key[id][LABELS[i]] = r.model;
      const tools = (r.calls || []).flatMap((c) => c.tool_calls || []).map((c) => `${c.name}(${JSON.stringify(c.args)})`).join(', ') || '없음';
      md += `### ${id} / ${LABELS[i]}\n- 부른 도구: ${tools}\n- 답:\n\n${(r.final_text || '(빈 답 — 앱은 예비 경로로 넘어감)').trim()}\n\n`;
    });
  }
  writeFileSync(join(DIR, 'judge_blind.md'), md);
  writeFileSync(join(DIR, 'judge_key.json'), JSON.stringify(key, null, 2));
  console.log(`prepared ${Object.keys(key).length} scenarios, ${recs.length} answers → data/judge_blind.md`);
  process.exit(0);
}

if (process.argv.includes('--check')) {
  const key = JSON.parse(readFileSync(join(DIR, 'judge_key.json'), 'utf8'));
  const path = join(DIR, 'judge_scores.json');
  if (!existsSync(path)) { console.error('judge_scores.json 없음'); process.exit(1); }
  const scores = JSON.parse(readFileSync(path, 'utf8'));
  const CRIT = ['accuracy', 'specificity', 'safety', 'voice'];
  const problems = [];
  const agg = {};
  let n = 0;
  for (const [id, labels] of Object.entries(key)) {
    for (const [label, model] of Object.entries(labels)) {
      const s = scores?.[id]?.[label];
      if (!s) { problems.push(`${id}/${label} 점수 없음`); continue; }
      for (const c of CRIT) if (!(Number.isInteger(s[c]) && s[c] >= 1 && s[c] <= 5)) problems.push(`${id}/${label} ${c}=${s[c]}`);
      if (!s.reason || String(s.reason).trim().length < 8) problems.push(`${id}/${label} 이유 없음`);
      const a = (agg[model] ||= { n: 0, sum: Object.fromEntries(CRIT.map((c) => [c, 0])), wins: 0 });
      a.n++; CRIT.forEach((c) => { a.sum[c] += s[c] || 0; });
      n++;
    }
    // 시나리오별 1등(4개 합 최고)
    const best = Object.entries(labels).map(([l, m]) => [m, CRIT.reduce((t, c) => t + (scores?.[id]?.[l]?.[c] || 0), 0)]).sort((x, y) => y[1] - x[1]);
    if (best.length && best[0][1] > (best[1]?.[1] ?? -1)) agg[best[0][0]].wins++;
  }
  if (n !== 70) problems.push(`점수 ${n}개(70개여야)`);
  if (problems.length) { console.error(problems.slice(0, 20).join('\n')); process.exit(1); }
  let md = '| 모델 | 정확성 | 구체성 | 안전 | 말투 | 평균 | 단독 1등 |\n|---|---|---|---|---|---|---|\n';
  for (const [m, a] of Object.entries(agg).sort((x, y) => CRIT.reduce((t, c) => t + y[1].sum[c], 0) - CRIT.reduce((t, c) => t + x[1].sum[c], 0))) {
    const avg = (c) => (a.sum[c] / a.n).toFixed(2);
    md += `| ${m} | ${CRIT.map(avg).join(' | ')} | ${(CRIT.reduce((t, c) => t + a.sum[c], 0) / a.n / CRIT.length).toFixed(2)} | ${a.wins}/14 |\n`;
  }
  writeFileSync(join(DIR, 'judge_report.md'), md);
  console.log(md);
  console.log('JUDGE_OK');
}
