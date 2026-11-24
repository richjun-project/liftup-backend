#!/usr/bin/env node
// 11-24 운영 스모크 — 11-23 대표가 겪은 채팅 500 재현 경로. 테스트 기기 계정(x-test-device: 1)을 만들고 끝나면 지운다(삭제 실패 = 실패).
// ① 한 메시지에 세 끼("아침 오트밀, 점심 닭가슴살 샐러드, 저녁 김치찌개") → log_meal 여러 번, 200
// ② 다음 메시지(새 요청 — 앞 요청의 meal_log_id 는 대화에 없음)에서 "점심 샐러드만 지워줘" → 200 + delete_meal_log changed,
//    오늘 기록이 하나 줄고 칼로리도 준다. 고치기 전엔 모델이 id 를 지어내 → 없는 id 의 예외가 채팅 트랜잭션을 rollback-only 로 만들어 500 이었다.
// ③ 답이 도구 이름('delete_meal_log' 등)이 아니다
//   node scripts/smoke/meal-multi-delete-smoke.mjs → MEAL_MULTI_DELETE_OK
const BASE = 'https://api.liftupai.net';
let token = null;
async function call(method, path, body) {
  const res = await fetch(BASE + path + (path.includes('?') ? '&' : '?') + 'locale=ko', {
    method, headers: { 'content-type': 'application/json', 'x-test-device': '1', 'x-platform': 'android', 'x-app-version': '1.1.51+68', ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null; try { json = await res.json(); } catch {}
  return { status: res.status, json };
}
const tools = (r) => (r.json?.data?.tools || []).map((t) => `${t.name}:${t.changed ? 'changed' : t.ok ? 'ok' : 'fail'}`);
const reply = (r) => String(r.json?.data?.reply || r.json?.data?.response || '');
const today = async () => { const s = await call('GET', '/api/nutrition/today-summary'); const d = s.json?.data || {}; return { kcal: d.consumed_kcal ?? d.consumedKcal ?? null, meals: (d.meals || d.meal_logs || []).length }; };
const out = {};
let ok = false;
try {
  const reg = await call('POST', '/api/auth/device/register', {
    device_id: `meal-multi-smoke-${Date.now()}`, nickname: '점검', experience_level: 'beginner', goals: ['general_fitness'],
    body_info: { gender: 'male', height: 175, weight: 70 },
    workout_preferences: { weekly_days: 3, workout_split: 'full_body', workout_duration: 30, available_equipment: ['DUMBBELL', 'BODYWEIGHT'] },
    notification_enabled: false,
  });
  token = reg.json?.data?.access_token; out.register = reg.status;
  if (!token) throw new Error('register failed ' + reg.status);
  await call('PUT', '/api/users/settings', { app: { language: 'ko', time_zone: 'Asia/Seoul' } });
  out.today0 = await today();
  let t0 = Date.now();
  const log = await call('POST', '/api/ai/chat', { message: '오늘 아침에 오트밀 한 그릇, 점심에 닭가슴살 샐러드, 저녁에 김치찌개랑 밥 한 공기 먹었어' });
  out.log = { status: log.status, ms: Date.now() - t0, tools: tools(log), reply: reply(log).slice(0, 100) };
  out.today1 = await today();
  t0 = Date.now();
  const del = await call('POST', '/api/ai/chat', { message: '아 점심 샐러드는 안 먹었어. 그것만 지워줘' });
  out.del = { status: del.status, ms: Date.now() - t0, tools: tools(del), reply: reply(del).slice(0, 100) };
  out.today2 = await today();
  const toolNameReply = /\b(log_meal|delete_meal_log|update_my_profile|change_plan)\b/.test(reply(log) + reply(del));
  out.toolNameReply = toolNameReply;
  ok = log.status === 200 && out.log.tools.filter((t) => t === 'log_meal:changed').length >= 2
    && del.status === 200 && out.del.tools.includes('delete_meal_log:changed')
    && (out.today2.kcal ?? 0) < (out.today1.kcal ?? 0) && (out.today2.kcal ?? 0) > (out.today0.kcal ?? 0)
    && !toolNameReply;
} catch (e) {
  out.error = String(e);
} finally {
  if (token) { const d = await call('DELETE', '/api/users/account'); out.deleted = d.status; if (d.status !== 200) ok = false; }
}
console.log(JSON.stringify(out, null, 1));
if (ok) console.log('MEAL_MULTI_DELETE_OK'); else process.exit(1);
