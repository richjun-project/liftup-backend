#!/usr/bin/env node
// 앱 1.1.44 묶음 검사: 전체 테스트 · 정적 분석(오류 0) · 디자인 검사(SLOP_OK) · 화면 촬영(ko·ja·en·SE 넘침 0).
//   node scripts/smoke/app-r2-gates.mjs <앱 작업 공간> → APP_R2_OK
import { spawnSync } from 'node:child_process';
const dir = process.argv[2];
if (!dir) { console.error('usage: app-r2-gates.mjs <app dir>'); process.exit(2); }
const env = { ...process.env, PATH: `${process.env.HOME}/Developments/flutter/bin:${process.env.PATH}` };
const run = (cmd, args, t = 1800_000) => spawnSync(cmd, args, { cwd: dir, env, encoding: 'utf8', maxBuffer: 512 * 1024 * 1024, timeout: t });
const problems = [];
// 화면 촬영(design_shots)은 아래 check_shots 가 언어·크기별로 돌린다. pixel_shots 는 취소된 픽셀 리디자인 보관본이라 뺀다.
import { readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
const walk = (d, out = []) => { for (const n of readdirSync(d)) { const p = join(d, n); if (statSync(p).isDirectory()) walk(p, out); else if (p.endsWith('_test.dart')) out.push(p); } return out; };
const files = walk(join(dir, 'test')).map(p => relative(dir, p)).filter(p => !p.startsWith('test/design_shots/') && !p.startsWith('test/pixel_shots/'));
const test = run('flutter', ['test', '--no-pub', ...files]);
const tail = `${test.stdout ?? ''}${test.stderr ?? ''}`.trim().split('\n').slice(-3).join(' | ');
if (test.status !== 0 || !/All tests passed!/.test(tail)) problems.push(`flutter test: ${tail.slice(0, 300)}`);
const analyze = run('flutter', ['analyze', '--no-pub', '--no-fatal-infos', '--no-fatal-warnings', 'lib', 'test']);
if (analyze.status !== 0) problems.push(`flutter analyze: ${(analyze.stdout ?? '').split('\n').filter(l => /error/.test(l)).slice(0, 5).join(' | ')}`);
const slop = run('node', ['tool/design/slop_check.mjs']);
if (slop.status !== 0 || !/SLOP_OK/.test(slop.stdout ?? '')) problems.push(`slop_check: ${(slop.stdout ?? '').trim().split('\n').slice(-3).join(' | ')}`);
const shots = run('node', ['tool/design_shots/check_shots.mjs'], 3600_000);
if (shots.status !== 0 || !/SHOTS_OK/.test(shots.stdout ?? '')) problems.push(`check_shots: ${`${shots.stdout ?? ''}${shots.stderr ?? ''}`.trim().split('\n').slice(-4).join(' | ').slice(0, 400)}`);
if (problems.length) { console.error(problems.map(p => `FAIL ${p}`).join('\n')); process.exit(1); }
console.log(`flutter test: ${tail.slice(0, 120)}`);
console.log('APP_R2_OK');
