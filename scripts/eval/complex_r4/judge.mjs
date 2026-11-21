#!/usr/bin/env node
// complex-model-r4 이름 가린 심사.
//   node judge.mjs --prepare   → data/judge_plan.md, judge_reco.md, judge_key.json (1회차 16개 경우 × 5모델, 경우마다 순서 섞음)
//   node judge.mjs --report    → data/judge_scores_{plan,reco}.json 을 모델별 평균으로 묶어 data/judge_report.md
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'data');
const rd = (f) => fs.readFileSync(path.join(DIR, f), 'utf8');
const jsonl = (f) => rd(f).split('\n').filter(Boolean).map((l) => JSON.parse(l));
const clean = (t) => t.trim().replace(/^```(?:json)?\s*/i, '').replace(/```\s*$/, '').trim();
const cut = (s, n) => (s && s.length > n ? s.slice(0, n) + '…' : s || '');
const LETTERS = ['A', 'B', 'C', 'D', 'E'];

// 결정적 섞기(경우 id 로 씨앗) — 다시 돌려도 같은 글자 배정
function shuffle(arr, seedStr) {
  let h = 2166136261;
  for (const c of seedStr) h = Math.imul(h ^ c.charCodeAt(0), 16777619) >>> 0;
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) { h = Math.imul(h ^ (h >>> 15), 2246822507) >>> 0; const j = h % (i + 1); [a[i], a[j]] = [a[j], a[i]]; }
  return a;
}

function personaLine(p, prompt) {
  const mins = (prompt.match(/Duration: (\d+) minutes/) || [])[1];
  const first = /first tracked workout/.test(prompt);
  return `언어 ${p.locale} · ${p.level} · ${p.age}세 ${p.gender} · 목표 ${p.goals.join(', ')} · 주 ${p.days}일 ${p.minutes}분 · 장비 ${p.equipment.join(', ')} · 부상 ${p.injuries.join(', ') || '없음'}${p.focus?.length ? ' · 집중 ' + p.focus.join(', ') : ''}` +
    (mins ? `\n이번 추천의 요청 시간: ${mins}분${first ? ' (첫 운동 — 서버 규칙상 익숙한 기본 동작, 2~3회 여유)' : ''}` : '');
}

function renderPlan(text) {
  let j; try { j = JSON.parse(clean(text)); } catch { return '(JSON 파싱 실패)'; }
  const head = `플랜: ${j.planName} · ${j.splitType} · ${j.totalDays}일 · ${j.estimatedWeeks}주 · ${j.progressionModel}\n설명: ${cut(j.planDescription, 220)}\n코칭: ${cut(j.coachingNotes, 320)}`;
  const days = (j.days || []).map((d) => `Day ${d.dayNumber} ${d.dayName} (${d.workoutType}, ${d.estimatedDurationMinutes}분): ` +
    (d.exercises || []).map((e) => `${e.exerciseName} ${e.sets}×${e.minReps}-${e.maxReps} 휴식${e.restSeconds}s${e.targetRPE ? ' RPE' + e.targetRPE : ''}`).join(' / ')).join('\n');
  return `${head}\n${days}`;
}

function renderReco(text, prompt) {
  let j; try { j = JSON.parse(clean(text)); } catch { return '(JSON 파싱 실패)'; }
  const names = {};
  for (const m of prompt.matchAll(/\[ID: (\d+)] ([^|]+)\|[^\n]*equipment=(\w+)/g)) names[m[1]] = `${m[2].replace(/ CORE\s*$/, '').trim()} [${m[3]}]`;
  const ex = (j.exercises || []).map((e, i) => `${i + 1}. ${names[String(e.id)] || '#' + e.id} ${e.sets}세트 ${e.reps} 휴식${e.rest_seconds}s`).join('\n');
  const ins = j.ai_insights || {};
  return `이름: ${j.workout_name} · 난이도 ${j.difficulty} · 장비 ${(j.equipment || []).join(', ')}\n${ex}\n팁: ${cut((j.tips || []).join(' | '), 260)}\n이유: ${cut(ins.workout_rationale, 200)}\n핵심: ${cut(ins.key_point, 160)}`;
}

function prepare() {
  const rs = jsonl('results.jsonl').filter((r) => r.rep === 1);
  const caps = Object.fromEntries(fs.readdirSync(path.join(DIR, 'capture')).map((f) => { const c = JSON.parse(rd('capture/' + f)); return [c.id, c]; }));
  const key = {};
  for (const kind of ['plan', 'reco']) {
    const cases = Object.keys(caps).filter((c) => c.startsWith(kind + '__')).sort();
    let md = `# ${kind === 'plan' ? 'AI 주간 플랜' : 'AI 오늘 운동 추천'} 심사지 (모델 이름 가림)\n\n`;
    for (const cid of cases) {
      const cap = caps[cid];
      const outs = shuffle(rs.filter((r) => r.case === cid), cid);
      key[cid] = Object.fromEntries(outs.map((r, i) => [LETTERS[i], r.model]));
      md += `## ${cid}\n사용자: ${personaLine(cap.persona, cap.prompt)}\n\n`;
      outs.forEach((r, i) => { md += `### ${LETTERS[i]}\n${r.error ? '(전송 오류)' : kind === 'plan' ? renderPlan(r.text) : renderReco(r.text, cap.prompt)}\n\n`; });
    }
    fs.writeFileSync(path.join(DIR, `judge_${kind}.md`), md);
    console.log(`judge_${kind}.md ${cases.length} cases, ${Math.round(md.length / 1024)}KB`);
  }
  fs.writeFileSync(path.join(DIR, 'judge_key.json'), JSON.stringify(key, null, 1));
}

function report() {
  const key = JSON.parse(rd('judge_key.json'));
  const DIMS = ['fit', 'structure', 'safety', 'coaching'];
  let md = '# 이름 가린 독립 심사 결과 (complex-model-r4, 1회차)\n\n심사자: Claude 하위 에이전트(Gemini 아님). 1~5점. fit=사용자 조건 맞춤, structure=구성·순서·볼륨, safety=부상·수준 안전, coaching=설명·팁의 구체성.\n';
  for (const kind of ['plan', 'reco']) {
    const s = JSON.parse(rd(`judge_scores_${kind}.json`));
    const agg = {};
    for (const [cid, letters] of Object.entries(key).filter(([c]) => c.startsWith(kind + '__'))) {
      for (const [L, model] of Object.entries(letters)) {
        const a = (agg[model] ??= { n: 0, wins: 0, ...Object.fromEntries(DIMS.map((d) => [d, 0])) });
        a.n++; DIMS.forEach((d) => (a[d] += s[cid][L][d]));
        if (s[cid].best === L) a.wins++;
      }
    }
    const rows = Object.entries(agg).map(([m, a]) => ({ m, ...Object.fromEntries(DIMS.map((d) => [d, a[d] / a.n])), avg: DIMS.reduce((x, d) => x + a[d], 0) / a.n / DIMS.length, wins: a.wins, n: a.n }))
      .sort((x, y) => y.avg - x.avg);
    md += `\n## ${kind === 'plan' ? 'AI 주간 플랜' : 'AI 오늘 운동 추천'}\n\n| 모델 | 맞춤 | 구성 | 안전 | 코칭 | 평균 | 1등 |\n|---|---|---|---|---|---|---|\n`;
    for (const r of rows) md += `| ${r.m} | ${r.fit.toFixed(2)} | ${r.structure.toFixed(2)} | ${r.safety.toFixed(2)} | ${r.coaching.toFixed(2)} | **${r.avg.toFixed(2)}** | ${r.wins}/${r.n} |\n`;
    const notes = Object.entries(s).filter(([c]) => c.startsWith(kind + '__')).map(([c, v]) => `- ${c}: 1등 ${key[c][v.best]} — ${v.best_reason || ''}`);
    md += `\n경우별 1등:\n${notes.join('\n')}\n`;
  }
  fs.writeFileSync(path.join(DIR, 'judge_report.md'), md);
  console.log(md);
}

if (process.argv.includes('--prepare')) prepare();
else if (process.argv.includes('--report')) report();
else { console.log('usage: --prepare | --report'); process.exit(1); }
