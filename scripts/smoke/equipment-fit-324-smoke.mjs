#!/usr/bin/env node
// 324 운영 스모크(2026-11-17, 실제 Gemini). 테스트 기기 계정(x-test-device: 1)을 만들고 끝나면 지운다(삭제 실패 = 실패).
// 324 = 한국어·초보·주 5일·30분·집·기구 없음·목표 4개. 11-16 운영에서 헬스장 5일 분할을 받았고, 장비를 말하자 재추천이
// 같은 템플릿을 골라 19종목이 제자리 교체 — '당기기 등·이두' 날이 런지·크런치·플랭크가 됐다.
//  1) 같은 조건 추천 → 헬스장 5일 분할이 아니라 맨몸 템플릿
//  2) 맨몸으로 채워진 beginner_split_5day(324 상태) → 앱 실행 때 설정 동기화(PUT /api/users/settings)가 맨몸 템플릿으로 바꾼다, 두 번째 동기화는 그대로
//  3) 헬스장 분할에서 "맨몸. 아무기계없어" → set_available_equipment 로 새 맨몸 템플릿(제자리 교체 아님)
//   node scripts/smoke/equipment-fit-324-smoke.mjs → EQUIPMENT_FIT_324_OK
const BASE = 'https://api.liftupai.net';
const GYM = ['BARBELL', 'DUMBBELL', 'MACHINE', 'CABLE', 'OTHER', 'BODYWEIGHT'];
const GOALS = ['weight_loss', 'strength', 'general_fitness', 'bulk_up'];
let token = null;
async function call(method, path, body) {
  const res = await fetch(BASE + path + (path.includes('?') ? '&' : '?') + 'locale=ko', {
    method, headers: { 'content-type': 'application/json', 'x-test-device': '1', 'x-platform': 'ios', 'x-app-version': '1.1.41+58', ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null; try { json = await res.json(); } catch {}
  return { status: res.status, json };
}
async function plan() {
  const p = (await call('GET', '/api/v2/plans/active')).json?.data;
  const days = [];
  for (let d = 1; d <= (p?.total_days || 0); d++) {
    const day = (await call('GET', `/api/v2/plans/active/days/${d}`)).json?.data;
    days.push((day?.exercises || []).map(e => e.equipment));
  }
  return { id: p?.plan_id, name: p?.plan_name, gear: days.flat().filter(e => !['BODYWEIGHT', 'OTHER', null, undefined].includes(e)).length };
}
const SPLIT_NAME = '초급자 5일 분할'; // beginner_split_5day 의 ko 이름 — 플랜 응답엔 템플릿 코드가 없다
const home = code => !!code && code !== 'beginner_split_5day' && (code.startsWith('home_') || code === 'glute_leg_home');
const homeName = name => !!name && name !== SPLIT_NAME && /맨몸|홈트/.test(name);

const out = {};
let ok = false;
try {
  const reg = await call('POST', '/api/auth/device/register', {
    device_id: `equip-324-smoke-${Date.now()}`, nickname: '점검', experience_level: 'beginner', goals: GOALS,
    body_info: { gender: 'female', height: 160, weight: 55 },
    workout_preferences: { weekly_days: 5, workout_split: 'full_body', workout_duration: 30, available_equipment: ['BODYWEIGHT'] },
    notification_enabled: false,
  });
  token = reg.json?.data?.access_token; out.register = reg.status;
  if (!token) throw new Error('register failed ' + reg.status);
  await call('PUT', '/api/users/settings', { app: { language: 'ko', time_zone: 'Asia/Seoul' } });

  // 1) 추천
  const rec = await call('POST', '/api/v2/templates/recommend', {
    experience_level: 'beginner', goals: GOALS, gender: 'female', weekly_days: 5, session_duration: 30, equipment: ['BODYWEIGHT'],
  });
  out.recommend = { status: rec.status, code: rec.json?.data?.template_code };

  // 2) 324 상태: 맨몸 장비로 헬스장 5일 분할을 적용(빈자리는 맨몸으로 채워짐) → 앱 실행 동기화
  out.apply_split = (await call('POST', '/api/v2/templates/beginner_split_5day/apply', {})).status;
  const drifted = await plan();
  out.drifted = { name: drifted.name, gear: drifted.gear };
  out.sync1 = (await call('PUT', '/api/users/settings', { app: { language: 'ko' } })).status;
  const fixed = await plan();
  out.fixed = { name: fixed.name, gear: fixed.gear, id: fixed.id };
  await call('PUT', '/api/users/settings', { app: { language: 'ko' } });
  out.sync2_same = (await plan()).id === fixed.id;

  // 3) 헬스장 분할에서 "맨몸. 아무기계없어"
  await call('PUT', '/api/users/profile', { available_equipment: GYM });
  await call('POST', '/api/v2/templates/beginner_split_5day/apply', {});
  const t0 = Date.now();
  const c = await call('POST', '/api/ai/chat', { message: '맨몸. 아무기계없어' });
  const after = await plan();
  out.chat = {
    status: c.status, ms: Date.now() - t0,
    tools: (c.json?.data?.tools || []).map(t => t.name + (t.changed ? ':changed' : '')),
    reply: (c.json?.data?.reply || '').slice(0, 160), name: after.name, gear: after.gear,
  };

  ok = out.recommend.status === 200 && home(out.recommend.code)
    && out.apply_split === 200 && out.drifted.name === SPLIT_NAME && out.drifted.gear === 0
    && out.sync1 === 200 && homeName(out.fixed.name) && out.fixed.gear === 0 && out.sync2_same
    && out.chat.status === 200 && out.chat.tools.includes('set_available_equipment:changed') && homeName(out.chat.name) && out.chat.gear === 0
    && out.chat.ms < 25000;
} catch (e) {
  out.error = String(e);
} finally {
  if (token) { const del = await call('DELETE', '/api/users/account'); out.deleted = del.status; if (del.status !== 200) ok = false; }
}
console.log(JSON.stringify(out, null, 1));
if (ok) console.log('EQUIPMENT_FIT_324_OK'); else process.exit(1);
