#!/usr/bin/env node
// coach-autopilot-r1 운영 스모크(2026-11-14, 실제 Gemini). 테스트 기기 계정(x-test-device: 1)을 만들고 끝나면 지운다(삭제 실패 = 실패).
//  1) 304 조건(전문가·39세·집·기구 없음·주 4일·30분) 추천 → 상급 맨몸 템플릿, 적용한 플랜에 철봉·낮은 바 동작 없음·날마다 등 위쪽
//  2) 헬스장 플랜에서 "器具はありません" → set_available_equipment 를 부르고 설정 화면으로 떠넘기지 않는다
//  3) "今日は脚の日にしたい" → set_today_workout_day, 떠넘기지 않는다
//  4) "トレーナーからのメッセージはもういらない" → set_coach_messages 로 ai_messages 가 꺼진다
//   node scripts/smoke/coach-r1-smoke.mjs → COACH_R1_SMOKE_OK
const BASE = 'https://api.liftupai.net';
const GYM = ['BARBELL', 'DUMBBELL', 'MACHINE', 'CABLE', 'OTHER', 'BODYWEIGHT'];
const BAR_SLUGS = /^(pull-up|chin-up|inverted-row|hanging-)/;
// ChatSafetyService.DEFLECTION_PATTERNS 의 일본어·영어 형태(설정·플랜 화면에서 직접 바꾸라는 안내)
const DEFLECT = [/(設定|プラン設定|プロフィール|プラン画面|マイページ)[^。！？\n]{0,6}(から|で|画面|を開)[^。！？\n]{0,24}(変更|変え|選|設定|保存|調整|入れ替え|差し替え|新しいプラン)/,
  /\b(in|from|go to|open|head to|visit|under)\b[^.!?\n]{0,14}\b(settings|plan settings|your profile|profile screen|plan screen|plan tab)\b/i];
let token = null;
async function call(method, path, body, extra = {}) {
  const res = await fetch(BASE + path + (path.includes('?') ? '&' : '?') + 'locale=ja', {
    method, headers: { 'content-type': 'application/json', 'x-test-device': '1', 'x-platform': 'ios', 'x-app-version': '1.1.43+60', ...extra, ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null; try { json = await res.json(); } catch {}
  return { status: res.status, json };
}
async function planDays() {
  const plan = (await call('GET', '/api/v2/plans/active')).json?.data;
  const days = [];
  for (let d = 1; d <= (plan?.total_days || 0); d++) {
    const day = (await call('GET', `/api/v2/plans/active/days/${d}`)).json?.data;
    days.push({ type: day?.workout_type, exercises: (day?.exercises || []).map(e => ({ id: e.exercise_id, name: e.exercise_name, equipment: e.equipment })) });
  }
  return { id: plan?.plan_id, code: plan?.source_id, name: plan?.plan_name, days };
}
const tools = r => (r.json?.data?.tools || []).map(t => t.name + (t.changed ? ':changed' : '') + (t.ok ? '' : ':fail'));
async function chat(message) {
  const t0 = Date.now();
  const r = await call('POST', '/api/ai/chat', { message });
  const reply = r.json?.data?.reply || '';
  return { status: r.status, ms: Date.now() - t0, tools: tools(r), deflects: DEFLECT.some(p => p.test(reply)), reply: reply.slice(0, 160) };
}

const out = {};
let ok = false;
try {
  const reg = await call('POST', '/api/auth/device/register', {
    device_id: `coach-r1-smoke-${Date.now()}`, nickname: '点検', experience_level: 'expert', goals: ['bulk_up', 'strength'],
    body_info: { gender: 'male', age: 39, height: 172, weight: 70 },
    workout_preferences: { weekly_days: 4, workout_split: 'auto', workout_duration: 30, available_equipment: ['BODYWEIGHT'] },
    notification_enabled: false,
  });
  token = reg.json?.data?.access_token; out.register = reg.status;
  if (!token) throw new Error('register failed ' + reg.status);
  await call('PUT', '/api/users/settings', { app: { language: 'ja', time_zone: 'Asia/Tokyo' } });

  // 1) 304 조건 추천 → 상급 맨몸 템플릿
  const rec = await call('POST', '/api/v2/templates/recommend', {
    experience_level: 'expert', goals: ['bulk_up', 'strength'], age: 39, gender: 'male', weekly_days: 4, session_duration: 30, equipment: ['BODYWEIGHT'],
  });
  out.recommend = { status: rec.status, code: rec.json?.data?.template_code };
  const apply = await call('POST', `/api/v2/templates/${out.recommend.code}/apply`, {});
  const p1 = await planDays();
  const allEx = p1.days.flatMap(d => d.exercises);
  out.p1 = { apply: apply.status, code: p1.code, days: p1.days.length, exercises: allEx.length,
    gear: allEx.filter(e => !['BODYWEIGHT', 'OTHER', null, undefined].includes(e.equipment)).length,
    names: p1.days.map(d => d.exercises.map(e => e.name).join('·')) };

  // 2) 헬스장 플랜에서 "器具はありません"
  await call('PUT', '/api/users/profile', { available_equipment: GYM });
  await call('POST', '/api/v2/templates/beginner_split_5day/apply', {});
  out.c_equipment = await chat('器具はありません');

  // 3) 오늘 Day 바꾸기
  await call('PUT', '/api/users/profile', { available_equipment: ['BODYWEIGHT'] });
  await call('POST', `/api/v2/templates/${out.recommend.code}/apply`, {});
  out.c_today = await chat('今日は脚の日にしたい');

  // 4) 트레이너 메시지 끄기
  const before = (await call('GET', '/api/notifications/settings')).json?.data?.ai_messages;
  out.c_quiet = await chat('トレーナーからのメッセージはもういらない');
  const after = (await call('GET', '/api/notifications/settings')).json?.data?.ai_messages;
  out.ai_messages = { before, after };

  ok = out.recommend.status === 200 && out.recommend.code === 'home_bodyweight_advanced'
    && out.p1.apply === 200 && out.p1.days === 4 && out.p1.gear === 0
    && out.c_equipment.status === 200 && out.c_equipment.tools.includes('set_available_equipment:changed') && !out.c_equipment.deflects
    && out.c_today.status === 200 && out.c_today.tools.includes('set_today_workout_day:changed') && !out.c_today.deflects
    && out.c_quiet.status === 200 && out.c_quiet.tools.includes('set_coach_messages:changed') && before === true && after === false
    && [out.c_equipment, out.c_today, out.c_quiet].every(c => c.ms < 25000);
} catch (e) {
  out.error = String(e);
} finally {
  if (token) { const del = await call('DELETE', '/api/users/account'); out.deleted = del.status; if (del.status !== 200) ok = false; }
}
console.log(JSON.stringify(out, null, 1));
if (ok) console.log('COACH_R1_SMOKE_OK'); else process.exit(1);
