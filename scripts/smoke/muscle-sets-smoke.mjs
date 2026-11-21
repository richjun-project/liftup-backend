#!/usr/bin/env node
// 통계 디자인 A 운영 스모크(2026-11-21). 테스트 기기 계정을 만들고 끝나면 지운다(삭제 실패 = 실패).
// ① 새 계정: /api/v2/stats/muscle-distribution?period=week 에 groups 6개(모두 0)·muscles 빈 목록 — 새 필드가 배포됐다
// ② 채팅으로 "오늘 벤치프레스 60kg 8회 3세트" 기록(log_completed_workout changed) → chest 묶음 ≥ 3, CHEST 부위 ≥ 3
//   node scripts/smoke/muscle-sets-smoke.mjs → MUSCLE_SETS_SMOKE_OK
const BASE = 'https://api.liftupai.net';
let token = null;
async function call(method, path, body) {
  const res = await fetch(BASE + path + (path.includes('?') ? '&' : '?') + 'locale=ko', {
    method, headers: { 'content-type': 'application/json', 'x-test-device': '1', 'x-platform': 'ios', 'x-app-version': '1.1.49+66', ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null; try { json = await res.json(); } catch {}
  return { status: res.status, json };
}
const dist = async () => (await call('GET', '/api/v2/stats/muscle-distribution?period=week')).json?.data || {};
const out = {};
let ok = false;
try {
  const reg = await call('POST', '/api/auth/device/register', {
    device_id: `muscle-sets-smoke-${Date.now()}`, nickname: '점검', experience_level: 'beginner', goals: ['general_fitness'],
    body_info: { gender: 'male', height: 175, weight: 70 },
    workout_preferences: { weekly_days: 3, workout_split: 'full_body', workout_duration: 45, available_equipment: ['BARBELL', 'DUMBBELL', 'BODYWEIGHT'] },
    notification_enabled: false,
  });
  token = reg.json?.data?.access_token; out.register = reg.status;
  if (!token) throw new Error('register failed ' + reg.status);
  await call('PUT', '/api/users/settings', { app: { language: 'ko', time_zone: 'Asia/Seoul' } });
  const d0 = await dist();
  out.before = { groups: (d0.groups || []).map((g) => `${g.key}:${g.sets}`), muscles: (d0.muscles || []).length };
  const log = await call('POST', '/api/ai/chat', { message: '오늘 바벨 벤치프레스 60kg 8회 3세트 했어. 기록해줘' });
  out.log = { status: log.status, tools: (log.json?.data?.tools || []).map((t) => `${t.name}:${t.changed ? 'changed' : t.ok ? 'ok' : 'fail'}`) };
  const d1 = await dist();
  const g = Object.fromEntries((d1.groups || []).map((x) => [x.key, x.sets]));
  const m = Object.fromEntries((d1.muscles || []).map((x) => [x.key, x.sets]));
  out.after = { groups: g, muscles: m };
  ok = (d0.groups || []).length === 6 && (d0.groups || []).every((x) => x.sets === 0) && (d0.muscles || []).length === 0
    && out.log.tools.includes('log_completed_workout:changed') && (g.chest ?? 0) >= 3 && (m.CHEST ?? 0) >= 3;
} catch (e) {
  out.error = String(e);
} finally {
  if (token) { const d = await call('DELETE', '/api/users/account'); out.deleted = d.status; if (d.status !== 200) ok = false; }
}
console.log(JSON.stringify(out, null, 1));
if (ok) console.log('MUSCLE_SETS_SMOKE_OK'); else process.exit(1);
