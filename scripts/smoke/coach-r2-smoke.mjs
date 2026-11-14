#!/usr/bin/env node
// coach-autopilot-r2 운영 스모크(2026-11-14, 실제 Gemini). 테스트 기기 계정을 만들고 끝나면 지운다(삭제 실패 = 실패).
//  1) 헬스장 플랜에서 "器具はありません" → 플랜이 맨몸으로 바뀌고 '코치가 한 일'에 기록이 생긴다
//  2) 그 기록을 되돌리면 예전 플랜(같은 plan_id)·헬스장 장비가 돌아온다
//  3) 운동을 마치면 트레이너 메시지가 다음 운동(Day)을 말하고, 무료 사용자에겐 플랜 버튼
//   node scripts/smoke/coach-r2-smoke.mjs → COACH_R2_SMOKE_OK
const BASE = 'https://api.liftupai.net';
const GYM = ['BARBELL', 'DUMBBELL', 'MACHINE', 'CABLE', 'OTHER', 'BODYWEIGHT'];
let token = null;
async function call(method, path, body, extra = {}) {
  const res = await fetch(BASE + path + (path.includes('?') ? '&' : '?') + 'locale=ja', {
    method, headers: { 'content-type': 'application/json', 'x-test-device': '1', 'x-platform': 'ios', 'x-app-version': '1.1.44+61', ...extra, ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null; try { json = await res.json(); } catch {}
  return { status: res.status, json };
}
async function plan() {
  const p = (await call('GET', '/api/v2/plans/active')).json?.data;
  return { id: p?.plan_id, code: p?.source_id, currentDay: p?.current_day };
}
async function equipment() {
  const prof = (await call('GET', '/api/users/profile')).json?.data;
  return (prof?.available_equipment || prof?.workout_preferences?.available_equipment || []).slice().sort();
}
const out = {};
let ok = false;
try {
  const reg = await call('POST', '/api/auth/device/register', {
    device_id: `coach-r2-smoke-${Date.now()}`, nickname: '点検', experience_level: 'expert', goals: ['strength'],
    body_info: { gender: 'male', age: 39, height: 172, weight: 70 },
    workout_preferences: { weekly_days: 5, workout_split: 'auto', workout_duration: 45, available_equipment: GYM },
    notification_enabled: false,
  });
  token = reg.json?.data?.access_token; out.register = reg.status;
  if (!token) throw new Error('register failed ' + reg.status);
  await call('PUT', '/api/users/settings', { app: { language: 'ja', time_zone: 'Asia/Tokyo' } });
  await call('PUT', '/api/users/profile', { available_equipment: GYM });
  await call('POST', '/api/v2/templates/beginner_split_5day/apply', {});
  const before = await plan();
  const beforeEq = await equipment();

  // 1) 장비 → 플랜 맨몸 + 기록
  const c = await call('POST', '/api/ai/chat', { message: '器具はありません' });
  out.chat = { status: c.status, tools: (c.json?.data?.tools || []).map(t => t.name + (t.changed ? ':changed' : '')) };
  const afterChat = await plan();
  const list = (await call('GET', '/api/coach/actions')).json?.data || [];
  out.actions = list.map(a => ({ id: a.id, tool: a.tool, summary: a.summary, undoable: a.undoable }));
  const latest = list[0];

  // 2) 되돌리기
  const u = latest ? await call('POST', `/api/coach/actions/${latest.id}/undo`) : { status: 0, json: null };
  out.undo = { status: u.status, ...(u.json?.data || {}) };
  const restored = await plan();
  const restoredEq = await equipment();
  out.plans = { before: before.id, after_chat: afterChat.id, restored: restored.id, before_code: before.code, after_code: afterChat.code };
  out.equipment = { before: beforeEq, restored: restoredEq };

  // 3) 운동 마치기 → 트레이너 다음 운동 예고
  // 오늘 플랜 Day 의 앞 2종목으로 빈 세션을 시작해 완료한다(완료 API 가 운동 행을 만든다)
  const today = (await call('GET', `/api/v2/plans/active/days/${restored.currentDay || 1}`)).json?.data;
  const ex = (today?.exercises || []).slice(0, 2);
  const start = await call('POST', '/api/v2/workouts/start/new', { workout_type: 'empty' });
  const sid = start.json?.data?.session_id;
  out.start = { status: start.status, session: sid, exercises: ex.length };
  // 세션 시작을 사람 속도로 보이게 서버 시각 기준 70초 기다렸다 6세트 완료
  await new Promise(r => setTimeout(r, 70_000));
  const body = { duration: 2, exercises: ex.map(e => ({ exercise_id: e.exercise_id, sets: [1, 2, 3].map(() => ({ weight: 20, reps: 10, completed: true })) })) };
  const done = sid ? await call('PUT', `/api/v2/workouts/${sid}/complete`, body) : { status: 0 };
  out.complete = done.status;
  const hist = (await call('GET', '/api/chat/history?page=1&limit=5')).json?.data;
  const msgs = hist?.messages || [];
  const trainer = msgs.map(m => ({ text: m.ai_response || '', actions: m.actions })).find(m => /Day \d/.test(m.text));
  out.debrief = trainer ? { text: trainer.text.slice(-80), actions: trainer.actions } : null;

  ok = out.chat.status === 200 && out.chat.tools.includes('set_available_equipment:changed')
    && afterChat.id && afterChat.id !== before.id
    && latest && latest.tool === 'set_available_equipment' && latest.undoable === true
    && out.undo.status === 200 && out.undo.ok === true
    && restored.id === before.id
    && JSON.stringify(restoredEq) === JSON.stringify(beforeEq)
    && out.complete === 200 && out.debrief && /次は Day \d/.test(out.debrief.text);
} catch (e) {
  out.error = String(e);
} finally {
  if (token) { const del = await call('DELETE', '/api/users/account'); out.deleted = del.status; if (del.status !== 200) ok = false; }
}
console.log(JSON.stringify(out, null, 1));
if (ok) console.log('COACH_R2_SMOKE_OK'); else process.exit(1);
