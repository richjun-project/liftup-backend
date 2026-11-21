#!/usr/bin/env node
// 답변 품질 재측정 채점기(ceo-r5, model_r3 복사).
//   node grade.mjs                 → data/results.jsonl 을 채점해 data/report.md 와 표를 출력
//   node grade.mjs --self-test     → 가짜 결과로 채점기를 먼저 검사(맞는 답은 통과, 틀린 답은 실패) 뒤 실제 결과 채점 → MODEL_GRADE_OK
// 시나리오 기대는 캡처(data/capture/<id>.json 의 expect)에 있다(ModelEvalCaptureTest 가 정한 것).
import { readFileSync, readdirSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const DIR = new URL('./data/', import.meta.url).pathname;
const capture = Object.fromEntries(
  readdirSync(join(DIR, 'capture')).filter((f) => f.endsWith('.json'))
    .map((f) => { const x = JSON.parse(readFileSync(join(DIR, 'capture', f), 'utf8')); return [x.id, x]; }),
);

// 가격(공식 aistudio.google.com/docs/pricing, 2026-11-21 읽음) — USD / 100만 토큰, 출력엔 생각 토큰 포함
const PRICE = {
  'gemini-3.8-flash': { now: [0.75, 3.75], later: [1.5, 7.5], laterNote: '2027-01-01' },
  'gemini-4-hydrogen': { now: [1.5, 7.5] },
  'gemini-4-neon': { now: [3, 15] },
  'gemini-4-carbon': { now: [2, 10], later: [4, 20], laterNote: '2026-12-01' },
  'gemini-4-argon': { now: [2, 10], later: [4, 20], laterNote: '2026-12-01' },
};

const MUTATING = ['swap_exercise_in_plan', 'set_today_workout_day', 'set_available_equipment', 'set_workout_time', 'set_coach_messages', 'remember', 'forget'];
const HANGUL = /[가-힣]/;
const KANA = /[぀-ヿ]/;
const CJK = /[一-鿿]/;
// ChatSafetyService.DEFLECTION_PATTERNS 와 같은 뜻(설정·플랜 화면에서 직접 바꾸라는 안내)
const DEFLECT = [
  /(설정|플랜\s?설정|프로필|플랜\s?화면|플랜\s?탭|설정\s?화면|설정\s?메뉴|마이\s?페이지)[^.!?\n]{0,8}(에서|으로|에 들어가|에 가서|을 열|를 열)[^.!?\n]{0,24}(바꾸|바꿔|변경|수정|선택|설정|저장|고르|골라|조정|교체|새 플랜)/,
  /\b(in|from|go to|open|head to|visit|under)\b[^.!?\n]{0,14}\b(settings|plan settings|your profile|profile screen|plan screen|plan tab)\b/i,
  /(設定|プラン設定|プロフィール|プラン画面|マイページ)[^。！？\n]{0,6}(から|で|画面|を開)[^。！？\n]{0,24}(変更|変え|選|設定|保存|調整|入れ替え|差し替え|新しいプラン)/,
];

function langOk(locale, text) {
  if (locale === 'ko') return HANGUL.test(text) && !KANA.test(text);
  if (locale === 'ja') return (KANA.test(text) || CJK.test(text)) && !HANGUL.test(text);
  return !HANGUL.test(text) && !KANA.test(text);
}

function stringsIn(v, out = []) {
  if (typeof v === 'string') { if (v.length >= 2 && v.length <= 40) out.push(v); }
  else if (Array.isArray(v)) v.forEach((x) => stringsIn(x, out));
  else if (v && typeof v === 'object') Object.entries(v).forEach(([k, x]) => { if (!['plan', 'note', 'detail'].includes(k)) stringsIn(x, out); });
  return out;
}

function argOk(rule, value, args, sc) {
  switch (rule) {
    case 'equipment_only': {
      const got = (args.equipment || []).map((s) => String(s).toUpperCase()).sort();
      return JSON.stringify(got) === JSON.stringify([...value].sort());
    }
    case 'day': return Number(args.day) === value;
    case 'from_is_squat': return sc.squat_ids.map(String).includes(String(args.from_exercise_id));
    case 'enabled': return args.enabled === value;
    case 'time': return String(args.time).padStart(5, '0') === value;
    default: return false;
  }
}

/** 대화 하나를 채점 — 항목별 통과 여부와 실패 이유 */
export function gradeRecord(rec, sc) {
  const e = sc.expect;
  const calls = (rec.calls || []).flatMap((c) => c.tool_calls || []);
  const names = calls.map((c) => c.name);
  const text = rec.final_text || '';
  const checks = {};
  const why = [];
  checks.answered = !rec.error && text.trim().length > 0;
  if (!checks.answered) why.push(rec.error ? `error ${rec.error.slice(0, 60)}` : 'empty answer');
  checks.required = (e.required || []).every((g) => g.some((n) => names.includes(n)));
  if (!checks.required) why.push(`missing ${JSON.stringify(e.required)} got ${JSON.stringify(names)}`);
  const bad = names.filter((n) => (e.forbidden || []).includes(n));
  checks.forbidden = bad.length === 0;
  if (!checks.forbidden) why.push(`forbidden ${bad}`);
  if (e.noTools) { checks.noTools = names.length === 0; if (!checks.noTools) why.push(`tools on greeting ${names}`); }
  checks.args = Object.entries(e.args || {}).every(([tool, rules]) => {
    const used = calls.filter((c) => c.name === tool);
    return used.length > 0 && used.some((c) => Object.entries(rules).every(([r, v]) => argOk(r, v, c.args || {}, sc)));
  });
  if (!checks.args) why.push(`args ${JSON.stringify(calls.filter((c) => Object.keys(e.args || {}).includes(c.name)).map((c) => c.args))}`);
  checks.language = langOk(sc.locale, text);
  if (!checks.language) why.push('language');
  // 떠넘기기: 서버 규칙과 같게 — 도구로 할 수 있는 요청(기대한 행동 도구가 있는 시나리오)에서만 보고,
  // 그 행동 도구를 실제로 불렀으면 면제한다(체중·부상처럼 도구가 없는 설정 안내는 정답일 수 있다)
  const actionTools = (e.required || []).flat().filter((n) => MUTATING.includes(n) && n !== 'remember');
  if (actionTools.length) {
    const acted = names.some((n) => actionTools.includes(n));
    checks.noDeflection = acted || !DEFLECT.some((p) => p.test(text));
    if (!checks.noDeflection) why.push('deflection');
  }
  if ((e.groundingFrom || []).length || (e.groundingAny || []).length) {
    const tokens = [...(e.groundingAny || [])];
    for (const tool of e.groundingFrom || []) {
      tokens.push(...stringsIn(sc.mutating_results?.[tool]));
      if (tool === 'set_today_workout_day') {
        const day = calls.find((c) => c.name === tool)?.args?.day;
        tokens.push(...stringsIn(sc.days?.[String(day)]));
      }
    }
    checks.grounded = tokens.some((t) => text.includes(t));
    if (!checks.grounded) why.push('not grounded in tool data');
  }
  if (e.needsStartButton) { checks.startButton = text.includes('[[START_TODAY_WORKOUT]]'); if (!checks.startButton) why.push('no start button'); }
  const pass = Object.values(checks).every(Boolean);
  return { pass, checks, why };
}

function pct(xs, p) { if (!xs.length) return 0; const s = [...xs].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p * (s.length - 1)))]; }

export function report(records) {
  const byModel = {};
  const fails = {};
  for (const r of records) {
    const sc = capture[r.scenario];
    const g = gradeRecord(r, sc);
    const m = (byModel[r.model] ||= { n: 0, pass: 0, checks: {}, totals: [], firsts: [], over21: 0, firstOver12: 0, inTok: 0, outTok: 0, think: 0, rounds: 0, scen: {} });
    m.n++; if (g.pass) m.pass++;
    for (const [k, v] of Object.entries(g.checks)) { const c = (m.checks[k] ||= [0, 0]); c[1]++; if (v) c[0]++; }
    m.totals.push(r.total_ms || 0);
    m.firsts.push(r.calls?.[0]?.ms || 0);
    if ((r.total_ms || 0) > 21000) m.over21++;
    if ((r.calls?.[0]?.ms || 0) > 12000) m.firstOver12++;
    for (const c of r.calls || []) { m.inTok += c.prompt_tokens || 0; m.outTok += (c.out || 0) + (c.thoughts || 0); m.think += c.thoughts || 0; m.rounds++; }
    const s = (m.scen[r.scenario] ||= [0, 0]); s[1]++; if (g.pass) s[0]++;
    if (!g.pass) (fails[r.model] ||= []).push(`${r.scenario}#${r.rep}: ${g.why.join('; ')}`);
  }
  const models = Object.keys(byModel).sort((a, b) => byModel[b].pass / byModel[b].n - byModel[a].pass / byModel[a].n);
  const checkNames = ['required', 'forbidden', 'args', 'language', 'noDeflection', 'grounded', 'startButton', 'noTools'];
  let md = '| 모델 | 통과 | ' + checkNames.join(' | ') + ' | 전체 p50/p95 초 | 첫 호출 p50 | 21초 초과 | 채팅 1건 $ (지금 / 인상 뒤) |\n';
  md += '|' + '---|'.repeat(5 + checkNames.length + 3) + '\n';
  for (const name of models) {
    const m = byModel[name];
    const pr = PRICE[name] || { now: [0, 0] };
    const perChat = (p) => ((m.inTok / m.n) * p[0] + (m.outTok / m.n) * p[1]) / 1e6;
    const cost = `${perChat(pr.now).toFixed(4)}${pr.later ? ` / ${perChat(pr.later).toFixed(4)} (${pr.laterNote})` : ''}`;
    md += `| ${name} | ${m.pass}/${m.n} (${Math.round((m.pass / m.n) * 100)}%) | ` +
      checkNames.map((k) => (m.checks[k] ? `${m.checks[k][0]}/${m.checks[k][1]}` : '-')).join(' | ') +
      ` | ${(pct(m.totals, 0.5) / 1000).toFixed(1)}/${(pct(m.totals, 0.95) / 1000).toFixed(1)} | ${(pct(m.firsts, 0.5) / 1000).toFixed(1)} | ${m.over21}/${m.n} | ${cost} |\n`;
  }
  md += '\n시나리오별 통과(2회 중):\n\n| 시나리오 | ' + models.join(' | ') + ' |\n|' + '---|'.repeat(models.length + 1) + '\n';
  for (const id of Object.keys(capture).sort()) md += `| ${id} | ` + models.map((n) => { const s = byModel[n].scen[id]; return s ? `${s[0]}/${s[1]}` : '-'; }).join(' | ') + ' |\n';
  md += '\n실패 이유:\n\n';
  for (const n of models) for (const f of (fails[n] || [])) md += `- ${n} — ${f}\n`;
  md += `\n토큰(대화 평균): ` + models.map((n) => `${n} 입력 ${Math.round(byModel[n].inTok / byModel[n].n)}·출력+생각 ${Math.round(byModel[n].outTok / byModel[n].n)}·호출 ${(byModel[n].rounds / byModel[n].n).toFixed(1)}`).join(' / ') + '\n';
  return { md, byModel };
}

function selfTest() {
  const problems = [];
  for (const sc of Object.values(capture)) {
    const e = sc.expect;
    // 기대대로 행동한 가짜 대화: 필수 도구를 기대 인자로 부르고, 근거 값을 넣어 그 언어로 답한다
    const tool_calls = [];
    for (const g of e.required || []) {
      const name = g[0];
      const args = {};
      for (const [r, v] of Object.entries(e.args?.[name] || {})) {
        if (r === 'equipment_only') args.equipment = v;
        if (r === 'day') args.day = v;
        if (r === 'from_is_squat') { args.from_exercise_id = Number(sc.squat_ids[0]); args.to_exercise_id = 1; }
        if (r === 'enabled') args.enabled = v;
        if (r === 'time') args.time = v;
      }
      tool_calls.push({ name, args });
    }
    let ground = (e.groundingAny || [])[0] || '';
    for (const tool of e.groundingFrom || []) {
      if (tool === 'set_today_workout_day') ground = sc.days?.[String(tool_calls.find((c) => c.name === tool)?.args?.day)]?.day_name || ground;
      else ground = stringsIn(sc.mutating_results?.[tool])[0] || ground;
    }
    const word = { ko: '좋아요, 확인했어요', ja: 'わかりました、確認しました', en: 'Got it, checked' }[sc.locale];
    const good = { calls: [{ ms: 1000, tool_calls }], final_text: `${word} ${ground}${e.needsStartButton ? '\n[[START_TODAY_WORKOUT]]' : ''}`, total_ms: 2000 };
    if (!gradeRecord(good, sc).pass) problems.push(`양성 대조 실패 ${sc.id}: ${gradeRecord(good, sc).why}`);
    // 틀린 가짜: 다른 언어로, 설정 화면으로 떠넘기고, 필수 도구 없이(또는 금지 도구를 불러) 답한다
    const wrongLang = sc.locale === 'en' ? '설정 화면에서 바꿔 주세요' : 'Please change it in Settings.';
    const bad = { calls: [{ ms: 1000, tool_calls: (e.required || []).length ? [] : [{ name: 'swap_exercise_in_plan', args: {} }] }], final_text: wrongLang, total_ms: 2000 };
    const g = gradeRecord(bad, sc);
    if (g.pass) problems.push(`음성 대조가 통과했다 ${sc.id}`);
    if (g.checks.language) problems.push(`언어 검사가 틀린 언어를 놓쳤다 ${sc.id}`);
    const acts = (e.required || []).flat().filter((n) => MUTATING.includes(n) && n !== 'remember');
    if (acts.length && g.checks.noDeflection) problems.push(`떠넘기기를 놓쳤다 ${sc.id}`);
  }
  // 떠넘기기 검사 자체: 일본어 떠넘기기 문장을 잡고 정상 문장은 통과
  if (!DEFLECT.some((p) => p.test('プラン設定から自重のプランを選んでください。'))) problems.push('일본어 떠넘기기를 못 잡았다');
  if (DEFLECT.some((p) => p.test('器具なしのメニューに変更しました。'))) problems.push('정상 일본어 답을 떠넘기기로 잡았다');
  return problems;
}

const selfTestMode = process.argv.includes('--self-test');
if (Object.keys(capture).length < 14) { console.error(`캡처가 ${Object.keys(capture).length}개뿐`); process.exit(1); }
if (selfTestMode) {
  const p = selfTest();
  if (p.length) { console.error(p.join('\n')); process.exit(1); }
  console.log(`self-test: ${Object.keys(capture).length} 시나리오 양성·음성 대조 통과`);
}
const resultsPath = join(DIR, 'results.jsonl');
if (!existsSync(resultsPath)) { console.error('results.jsonl 없음'); process.exit(1); }
const records = readFileSync(resultsPath, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
const { md } = report(records);
writeFileSync(join(DIR, 'report.md'), md);
console.log(md);
if (selfTestMode) console.log('MODEL_GRADE_OK');
