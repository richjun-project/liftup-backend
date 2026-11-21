#!/usr/bin/env node
// 11-21 일괄(ceo-r5) 운영 스모크. 테스트 기기 계정(x-test-device: 1)을 만들고 끝나면 지운다(삭제 실패 = 실패).
// ① 채팅으로 식단 기록("점심에 김치찌개랑 밥 한 공기") → 응답 tools 에 log_meal changed, 오늘 식단 요약에 칼로리가 늘었다
// ② "방금 기록한 거 지워줘" → delete_meal_log changed, 칼로리가 원래대로
// ③ "요즘 몸무게 72kg이야" → update_my_profile changed, 프로필 체중 72
//   node scripts/smoke/ceo-r5-smoke.mjs → CEO_R5_SMOKE_OK
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
const tools = (r) => (r.json?.data?.tools || []).map((t) => `${t.name}:${t.changed ? 'changed' : t.ok ? 'ok' : 'fail'}`);
const kcal = async () => { const s = await call('GET', '/api/nutrition/today-summary'); const d = s.json?.data || {}; return d.consumed_kcal ?? d.consumedKcal ?? null; };
const out = {};
let ok = false;
try {
  const reg = await call('POST', '/api/auth/device/register', {
    device_id: `ceo-r5-smoke-${Date.now()}`, nickname: '점검', experience_level: 'beginner', goals: ['general_fitness'],
    body_info: { gender: 'male', height: 175, weight: 70 },
    workout_preferences: { weekly_days: 3, workout_split: 'full_body', workout_duration: 30, available_equipment: ['DUMBBELL', 'BODYWEIGHT'] },
    notification_enabled: false,
  });
  token = reg.json?.data?.access_token; out.register = reg.status;
  if (!token) throw new Error('register failed ' + reg.status);
  await call('PUT', '/api/users/settings', { app: { language: 'ko', time_zone: 'Asia/Seoul' } });
  out.apply = (await call('POST', '/api/v2/templates/home_dumbbell_beginner/apply', {})).status;
  out.kcal0 = await kcal();
  const t0 = Date.now();
  const meal = await call('POST', '/api/ai/chat', { message: '점심에 김치찌개랑 밥 한 공기 먹었어' });
  out.meal = { status: meal.status, ms: Date.now() - t0, tools: tools(meal), reply: String(meal.json?.data?.reply || meal.json?.data?.response || '').slice(0, 120) };
  out.kcal1 = await kcal();
  const del = await call('POST', '/api/ai/chat', { message: '방금 기록한 점심 지워줘. 잘못 넣었어' });
  out.del = { status: del.status, tools: tools(del) };
  out.kcal2 = await kcal();
  const w = await call('POST', '/api/ai/chat', { message: '요즘 몸무게 72kg이야' });
  out.weight = { status: w.status, tools: tools(w) };
  const prof = await call('GET', '/api/users/profile');
  out.profileWeight = prof.json?.data?.weight ?? prof.json?.data?.body_info?.weight ?? prof.json?.data?.bodyInfo?.weight;
  ok = out.apply === 200 && meal.status === 200 && out.meal.tools.includes('log_meal:changed') && (out.kcal1 ?? 0) > (out.kcal0 ?? 0)
    && del.status === 200 && out.del.tools.includes('delete_meal_log:changed') && out.kcal2 === out.kcal0
    && w.status === 200 && out.weight.tools.includes('update_my_profile:changed') && Number(out.profileWeight) === 72;
} catch (e) {
  out.error = String(e);
} finally {
  if (token) { const d = await call('DELETE', '/api/users/account'); out.deleted = d.status; if (d.status !== 200) ok = false; }
}
console.log(JSON.stringify(out, null, 1));
if (ok) console.log('CEO_R5_SMOKE_OK'); else process.exit(1);
