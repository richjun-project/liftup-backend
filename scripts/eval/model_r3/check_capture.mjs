#!/usr/bin/env node
// G1: 캡처 14개가 실제 앱 경로의 첫 요청(시스템 프롬프트 + 도구 선언 15개)과 도구 결과를 담았는지 → EVAL_CAPTURE_OK
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
const dir = new URL('./data/capture/', import.meta.url).pathname;
const files = readdirSync(dir).filter((f) => f.endsWith('.json'));
const problems = [];
const locales = new Set();
for (const f of files) {
  const x = JSON.parse(readFileSync(join(dir, f), 'utf8'));
  locales.add(x.locale);
  const text = x.request?.contents?.[0]?.parts?.[0]?.text || '';
  const decls = x.request?.tools?.[0]?.functionDeclarations || [];
  if (!text.includes('## User message') || !text.includes(x.message)) problems.push(`${x.id}: 프롬프트에 사용자 메시지 없음`);
  if (!text.includes('YOU ARE AN AGENT WITH TOOLS')) problems.push(`${x.id}: 에이전트 구역 없음(실제 경로 아님)`);
  if (text.length < 8000) problems.push(`${x.id}: 프롬프트가 짧다 ${text.length}`);
  if (decls.length < 15) problems.push(`${x.id}: 도구 선언 ${decls.length}`);
  if (!x.tool_results?.get_today_workout || !x.expect) problems.push(`${x.id}: 도구 결과·기대 없음`);
}
if (files.length !== 14) problems.push(`캡처 ${files.length}개`);
for (const l of ['ko', 'ja', 'en']) if (!locales.has(l)) problems.push(`${l} 시나리오 없음`);
if (problems.length) { console.error(problems.join('\n')); process.exit(1); }
console.log(`captures=${files.length} locales=${[...locales].sort()}`);
console.log('EVAL_CAPTURE_OK');
