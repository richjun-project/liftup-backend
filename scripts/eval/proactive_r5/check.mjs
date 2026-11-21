#!/usr/bin/env node
// 먼저 말 걸기 실측(proactive_r5): 서버 검사(clean·check) 통과 ≥ 11/12, 통과한 것은 모두 질문으로 끝나는 제안이고 데이터의 숫자를 하나 이상 쓴다(구체성).
// 양성 대조: 검사 실패 표시가 있는 가짜 줄은 통과율을 떨어뜨려야 한다.
import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const g = fs.readFileSync(path.join(HERE, 'data/grades.jsonl'), 'utf8').split('\n').filter(Boolean).map(JSON.parse);
const ask = (t) => /[?？]|ませんか|ましょうか|볼까|할까|어때|텐가|테냐|까요|까\?/.test(t);
const score = (rows) => rows.filter((r) => !r.problem && ask(r.text) && /\d/.test(r.text)).length;
if (score(g.map((r, i) => (i === 0 ? { ...r, problem: 'x' } : r))) >= score(g)) { console.log('CONTROL_FAILED'); process.exit(1); }
if (g.length !== 12) { console.log('RUN_INCOMPLETE ' + g.length); process.exit(1); }
const ok = score(g);
const cases = new Set(g.filter((r) => !r.problem).map((r) => r.case));
if (ok < 11 || cases.size < 4) { console.log(`PROACTIVE_NOT_GOOD ${ok}/12 cases=${cases.size}`); process.exit(1); }
console.log(`PROACTIVE_MESSAGES_OK ${ok}/12 cases=${cases.size}`);
