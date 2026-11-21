#!/usr/bin/env node
// 트레이너 첫 인사 운영 스모크(2026-11-21). 테스트 기기 계정(x-test-device: 1)을 만들고 끝나면 지운다(삭제 실패 = 실패).
// 신규 사용자가 온보딩 뒤 처음 여는 채팅 기록(GET /api/chat/history)에 플랜 소개 인사(START_TODAY_WORKOUT,SET_REMINDER)가
// **첫 응답에** 있어야 한다. 10-06 도입 뒤 줄곧 첫 응답이 비어 앱이 일반 인사("한 개 더! 포기하지 마!")를 띄웠다 —
// 인사는 조회 트랜잭션 안에서 따로 커밋되는데, MySQL REPEATABLE READ 는 바깥 트랜잭션의 첫 읽기 때 잡힌 스냅샷으로
// 메시지를 읽어 방금 만든 인사를 못 본다(H2 테스트로는 안 잡힘 — 운영에서만 확인 가능).
//   node scripts/smoke/first-welcome-smoke.mjs → FIRST_WELCOME_OK
const BASE = 'https://api.liftupai.net';
let token = null;
async function call(method, path, body) {
  const res = await fetch(BASE + path + (path.includes('?') ? '&' : '?') + 'locale=ko', {
    method, headers: { 'content-type': 'application/json', 'x-test-device': '1', 'x-platform': 'ios', 'x-app-version': '1.1.48+65', ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null; try { json = await res.json(); } catch {}
  return { status: res.status, json };
}

const out = {};
let ok = false;
try {
  const reg = await call('POST', '/api/auth/device/register', {
    device_id: `first-welcome-smoke-${Date.now()}`, nickname: '점검', experience_level: 'beginner', goals: ['general_fitness'],
    body_info: { gender: 'male', height: 175, weight: 70 },
    workout_preferences: { weekly_days: 3, workout_split: 'full_body', workout_duration: 30, available_equipment: ['DUMBBELL', 'BODYWEIGHT'] },
    notification_enabled: false,
  });
  token = reg.json?.data?.access_token; out.register = reg.status;
  if (!token) throw new Error('register failed ' + reg.status);
  await call('PUT', '/api/users/settings', { app: { language: 'ko', time_zone: 'Asia/Seoul' } });
  out.apply = (await call('POST', '/api/v2/templates/home_dumbbell_beginner/apply', {})).status;

  const first = await call('GET', '/api/chat/history?page=1&limit=50');
  const msgs = first.json?.data?.messages || [];
  const welcome = msgs.find(m => String(m.actions || '').includes('SET_REMINDER') || (Array.isArray(m.actions) && m.actions.includes('SET_REMINDER')));
  out.first = { status: first.status, count: msgs.length, total: first.json?.data?.total_count ?? first.json?.data?.totalCount, welcome: welcome ? String(welcome.ai_response || welcome.content || welcome.message || '').slice(0, 80) : null };
  const second = await call('GET', '/api/chat/history?page=1&limit=50');
  out.second = { status: second.status, count: (second.json?.data?.messages || []).length };

  ok = out.apply === 200 && first.status === 200 && out.first.count === 1 && !!out.first.welcome && out.second.count === 1;
} catch (e) {
  out.error = String(e);
} finally {
  if (token) { const del = await call('DELETE', '/api/users/account'); out.deleted = del.status; if (del.status !== 200) ok = false; }
}
console.log(JSON.stringify(out, null, 1));
if (ok) console.log('FIRST_WELCOME_OK'); else process.exit(1);
