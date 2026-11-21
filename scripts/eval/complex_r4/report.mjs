#!/usr/bin/env node
// complex-model-r4 요약표: 지연·토큰·비용(지금 값 / 예고된 인상 뒤)·규칙 채점·독립 심사 평균 → data/report.md
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'data');
const jsonl = (f) => fs.readFileSync(path.join(DIR, f), 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
// 100만 토큰당 달러(입력, 출력). thinking 토큰은 출력 값. 2026-11-21 aistudio 문서 기준.
const PRICE = {
  'gemini-3.8-flash': { now: [0.75, 3.75], later: [1.5, 7.5], when: '2027-01-01' },
  'gemini-3.1-pro-preview': { now: [2, 12] },
  'gemini-4-carbon': { now: [2, 10], later: [4, 20], when: '2026-12-01' },
  'gemini-4-neon': { now: [3, 15] },
  'gemini-4-hydrogen': { now: [1.5, 7.5] },
};
const rs = jsonl('results.jsonl'), gs = jsonl('grades.jsonl');
const gradeOf = Object.fromEntries(gs.map((g) => [`${g.model}|${g.case}|${g.rep}`, g.grade]));
const judgeMd = fs.existsSync(path.join(DIR, 'judge_report.md')) ? fs.readFileSync(path.join(DIR, 'judge_report.md'), 'utf8') : '';
const judgeAvg = {};
for (const sec of judgeMd.split('\n## ').slice(1)) {
  const kind = sec.startsWith('AI 주간 플랜') ? 'plan' : 'reco';
  for (const m of sec.matchAll(/^\| (gemini-[^ ]+) \|.*\*\*([\d.]+)\*\* \| (\d+)\/(\d+) \|$/gm)) judgeAvg[`${m[1]}|${kind}`] = `${m[2]} (1등 ${m[3]}/${m[4]})`;
}
const q = (a, p) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(p * (s.length - 1) + 0.5))]; };
const cost = (r, [pi, po]) => (r.in * pi + (r.out + r.think) * po) / 1e6;
let md = '# 복잡한 일 모델 실측 (complex-model-r4, 2026-11-21)\n\n실제 앱 경로 프롬프트(AI 주간 플랜 8명 · AI 오늘 운동 추천 8명) × 모델 5개 × 2회 = 160 호출, 운영 서버에서. 전송 오류 0.\n규칙 채점은 서버의 실제 파서·환각 보정·플랜 품질 검사기(runPipeline)와 후보·장비·부상 규칙. 독립 심사는 Claude(이름 가림, 1회차).\n';
for (const kind of ['plan', 'reco']) {
  md += `\n## ${kind === 'plan' ? 'AI 주간 플랜 생성' : 'AI 오늘 운동 추천'}\n\n| 모델 | 독립 심사 평균 | 규칙 통과 | 지연 p50 / p95 / 최대 | 1회 비용(지금) | 인상 뒤 |\n|---|---|---|---|---|---|\n`;
  for (const m of Object.keys(PRICE)) {
    const v = rs.filter((r) => r.model === m && r.kind === kind);
    const ms = v.map((r) => r.ms / 1000);
    const pass = v.filter((r) => gradeOf[`${r.model}|${r.case}|${r.rep}`]?.valid).length;
    const c = v.reduce((s, r) => s + cost(r, PRICE[m].now), 0) / v.length;
    const cl = PRICE[m].later ? `$${(v.reduce((s, r) => s + cost(r, PRICE[m].later), 0) / v.length).toFixed(4)} (${PRICE[m].when}~)` : '—';
    md += `| ${m} | ${judgeAvg[`${m}|${kind}`] ?? '—'} | ${pass}/${v.length} | ${q(ms, .5).toFixed(1)} / ${q(ms, .95).toFixed(1)} / ${Math.max(...ms).toFixed(1)}초 | $${c.toFixed(4)} | ${cl} |\n`;
  }
}
const w = {};
for (const g of gs.filter((x) => x.case.startsWith('plan__'))) {
  const a = (w[g.model] ??= { warn: 0, rep: 0 });
  a.warn += (g.grade.warning_messages || []).length;
  a.rep += Object.values(g.grade.repair_reasons || {}).reduce((x, y) => x + y, 0);
}
md += '\n플랜 경고·자동 보정 수(16개 합): ' + Object.entries(w).map(([m, a]) => `${m} 경고 ${a.warn} · 보정 ${a.rep}`).join(' / ') + '\n';
md += '\n주의: 사용자 유형 8명 × 1회 심사라 0.2점 안팎 차이는 잡음일 수 있다. 추천 프롬프트는 테스트 환경에서 벡터 검색이 꺼져 폴백 후보(장비 무시)를 썼다 — 모든 모델이 장비 위반 0으로 걸러 냈다.\n';
fs.writeFileSync(path.join(DIR, 'report.md'), md);
console.log(md);
