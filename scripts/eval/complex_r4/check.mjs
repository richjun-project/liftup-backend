#!/usr/bin/env node
// complex-model-r4 게이트 검사기. node check.mjs <capture|results|grades|judge>
// 성공하면 COMPLEX_*_OK 를 출력하고 0 으로 끝난다. 실패하면 이유를 적고 1.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'data');
const MODELS = ['gemini-3.8-flash', 'gemini-3.1-pro-preview', 'gemini-4-carbon', 'gemini-4-neon', 'gemini-4-hydrogen'];
const fail = (m) => { console.log('FAIL ' + m); process.exit(1); };
const jsonl = (f) => fs.readFileSync(path.join(DIR, f), 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
const captures = () => {
  const dir = path.join(DIR, 'capture');
  return fs.readdirSync(dir).filter((f) => f.endsWith('.json')).map((f) => JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')));
};

function capture() {
  const cs = captures();
  if (cs.length !== 16) fail(`캡처 ${cs.length}개 (16개여야 함)`);
  const plans = cs.filter((c) => c.kind === 'plan'), recos = cs.filter((c) => c.kind === 'reco');
  if (plans.length !== 8 || recos.length !== 8) fail(`plan ${plans.length} / reco ${recos.length}`);
  for (const c of cs) {
    if (!c.prompt || c.prompt.length < 2000) fail(`${c.id}: 프롬프트가 비었거나 짧다 (${c.prompt?.length})`);
    const g = c.generationConfig;
    if (!g || !g.maxOutputTokens || g.temperature == null) fail(`${c.id}: 생성 설정 없음`);
  }
  for (const c of recos) if ((c.prompt.match(/\[ID: \d+]/g) || []).length < 10) fail(`${c.id}: 추천 후보 목록이 10개 미만`);
  for (const c of plans) if (!/exerciseId|"id"|ID:/.test(c.prompt)) fail(`${c.id}: 플랜 운동 풀이 프롬프트에 없다`);
  const ps = plans.map((c) => c.persona);
  const locales = new Set(ps.map((p) => p.locale));
  for (const l of ['ko', 'ja', 'en']) if (!locales.has(l)) fail(`언어 ${l} 없음`);
  if (!ps.some((p) => p.injuries.length)) fail('부상 사용자 없음');
  if (!ps.some((p) => p.equipment.length === 1 && p.equipment[0] === 'BODYWEIGHT')) fail('맨몸 사용자 없음');
  if (!ps.some((p) => p.age >= 65)) fail('고령 사용자 없음');
  if (!ps.some((p) => p.age < 18)) fail('미성년 사용자 없음');
  // 플랜 프롬프트가 사용자 조건을 실제로 담았는지(캡처가 실제 서비스 경로라는 증거)
  for (const c of plans) if (!c.prompt.includes(String(c.persona.days))) fail(`${c.id}: 주당 일수가 프롬프트에 없다`);
  console.log(`COMPLEX_CAPTURE_OK plans=8 recos=8 locales=${[...locales].join(',')}`);
}

function results() {
  const rs = jsonl('results.jsonl');
  const cases = captures().map((c) => c.id);
  const keys = new Set(rs.map((r) => `${r.model}|${r.case}|${r.rep}`));
  const want = MODELS.flatMap((m) => cases.flatMap((c) => [1, 2].map((rep) => `${m}|${c}|${rep}`)));
  const missing = want.filter((k) => !keys.has(k));
  if (missing.length) fail(`빠진 호출 ${missing.length}개: ${missing.slice(0, 3).join(' ')}`);
  if (rs.length !== 160) fail(`줄 수 ${rs.length} (160이어야 함)`);
  const errs = rs.filter((r) => r.error).length;
  if (errs / rs.length >= 0.05) fail(`전송 오류 ${errs}/${rs.length}`);
  for (const r of rs.filter((x) => !x.error)) {
    if (!r.text || r.text.length < 50) fail(`${r.model} ${r.case}#${r.rep}: 원문이 비었다`);
    if (!(r.ms > 0) || !(r.in > 0) || !(r.out > 0)) fail(`${r.model} ${r.case}#${r.rep}: 지연·토큰 기록 없음`);
    if (r.model_version && !r.model_version.startsWith(r.model.replace(/-preview$/, ''))) fail(`${r.model}: 응답 모델 ${r.model_version}`);
  }
  console.log(`COMPLEX_RUN_OK calls=${rs.length} errors=${errs}`);
}

function grades() {
  const rs = jsonl('results.jsonl'), gs = jsonl('grades.jsonl');
  const rk = new Set(rs.map((r) => `${r.model}|${r.case}|${r.rep}`)), gk = new Set(gs.map((g) => `${g.model}|${g.case}|${g.rep}`));
  if (gs.length !== rs.length || [...rk].some((k) => !gk.has(k))) fail(`채점 ${gs.length}줄이 결과 ${rs.length}줄과 맞지 않는다`);
  for (const g of gs) {
    const x = g.grade;
    if (typeof x.parsed !== 'boolean' || typeof x.valid !== 'boolean') fail(`${g.model} ${g.case}: parsed/valid 없음`);
    if (g.case.startsWith('plan__') && x.parsed && !('usable' in x && 'repairs' in x && 'warning_messages' in x)) fail(`${g.case}: 플랜 채점 항목 없음`);
    if (g.case.startsWith('reco__') && x.parsed && !('outside_candidates' in x && 'prompt_minutes' in x)) fail(`${g.case}: 추천 채점 항목 없음`);
  }
  const meta = JSON.parse(fs.readFileSync(path.join(DIR, 'grades.meta.json'), 'utf8'));
  if (meta.neg_broken_json_parsed !== false) fail('음성 대조: 깨진 JSON 이 파싱됐다고 나왔다');
  if (!(meta.neg_barbell_disallowed > 0) || meta.neg_barbell_valid !== false) fail('음성 대조: 맨몸 사용자의 바벨 종목을 못 잡았다');
  if (meta.pos_reco_valid !== true) fail('양성 대조: 정상 추천이 실패로 나왔다');
  const gMtime = fs.statSync(path.join(DIR, 'grades.jsonl')).mtimeMs, rMtime = fs.statSync(path.join(DIR, 'results.jsonl')).mtimeMs;
  if (gMtime < rMtime) fail('채점 파일이 결과 파일보다 오래됐다');
  console.log(`COMPLEX_GRADE_OK graded=${gs.length} negative_controls=2 positive_controls=1`);
}

function judge() {
  const key = JSON.parse(fs.readFileSync(path.join(DIR, 'judge_key.json'), 'utf8'));
  let n = 0;
  for (const kind of ['plan', 'reco']) {
    const f = path.join(DIR, `judge_scores_${kind}.json`);
    if (!fs.existsSync(f)) fail(`${kind} 심사 점수 파일 없음`);
    const s = JSON.parse(fs.readFileSync(f, 'utf8'));
    for (const [cid, letters] of Object.entries(key).filter(([c]) => c.startsWith(kind + '__'))) {
      const got = s[cid];
      if (!got) fail(`${cid}: 심사 없음`);
      for (const L of Object.keys(letters)) {
        const v = got[L];
        if (!v) fail(`${cid} ${L}: 점수 없음`);
        for (const k of ['fit', 'structure', 'safety', 'coaching']) if (!Number.isInteger(v[k]) || v[k] < 1 || v[k] > 5) fail(`${cid} ${L}.${k}=${v[k]}`);
        if (!v.reason || v.reason.length < 10) fail(`${cid} ${L}: 이유 없음`);
        n++;
      }
      if (!got.best || !letters[got.best]) fail(`${cid}: best 없음`);
    }
  }
  if (n !== 80) fail(`심사 ${n}개 (80개여야 함)`);
  if (!fs.existsSync(path.join(DIR, 'judge_report.md'))) fail('judge_report.md 없음 (judge.mjs --report)');
  console.log(`COMPLEX_JUDGE_OK scored=${n}`);
}

const cmd = process.argv[2];
const fn = { capture, results, grades, judge }[cmd];
if (!fn) fail(`알 수 없는 검사 ${cmd}`);
fn();
