#!/usr/bin/env node
// 리프레시 토큰 '가족' 규칙 운영 스모크(2026-11-22). 테스트 기기 계정(x-test-device: 1)을 만들고 끝나면 지운다(삭제 실패 = 실패).
// 재현: 앱이 갱신 응답을 못 받으면(백그라운드 실행 중 iOS 가 앱을 재움) 서버는 새 토큰으로 바꿨는데 앱은 직전 토큰을 들고 있다.
// 예전엔 그 토큰으로 다시 오면 401 → 앱이 로그아웃했다(339, 11-19). 이제는
//   ① 가입 토큰 t0 로 갱신(응답은 버린다) ② t0 로 다시 갱신 → 200 ③ 액세스 토큰을 리프레시로 → 401
//   ④ 로그아웃 → t0 로 갱신 → 401 ⑤ 계정 삭제
//   node scripts/smoke/refresh-family-smoke.mjs → REFRESH_FAMILY_OK   (배포 전 운영에서는 ② 가 401 로 실패해야 정상)
const BASE = 'https://api.liftupai.net';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function call(method, path, body, token) {
  const res = await fetch(BASE + path + (path.includes('?') ? '&' : '?') + 'locale=ko', {
    method, headers: { 'content-type': 'application/json', 'x-test-device': '1', 'x-platform': 'ios', 'x-app-version': '1.1.50+67', ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null; try { json = await res.json(); } catch {}
  return { status: res.status, json };
}
const refresh = (rt) => call('POST', '/api/auth/refresh', { refresh_token: rt });

const out = {};
let ok = false;
let access = null;
try {
  const deviceId = `refresh-family-smoke-${Date.now()}`;
  const reg = await call('POST', '/api/auth/device/register', {
    device_id: deviceId, nickname: '점검', experience_level: 'beginner', goals: ['general_fitness'],
    body_info: { gender: 'male', height: 175, weight: 70 },
    workout_preferences: { weekly_days: 3, workout_split: 'full_body', workout_duration: 30, available_equipment: ['BODYWEIGHT'] },
    notification_enabled: false,
  });
  access = reg.json?.data?.access_token;
  const t0 = reg.json?.data?.refresh_token;
  out.register = reg.status;
  if (!access || !t0) throw new Error('register failed ' + reg.status);

  await sleep(1500); // JWT iat 는 초 단위 — 새 토큰이 t0 와 다른 문자열이 되게
  const r1 = await refresh(t0); // 앱이 이 응답을 못 받았다고 친다
  out.first = r1.status;
  const t1 = r1.json?.data?.refresh_token;
  out.rotated = !!t1 && t1 !== t0;

  await sleep(1500);
  const r2 = await refresh(t0); // 핵심: 직전 토큰으로 다시
  out.retryWithPrevious = r2.status;
  if (r2.json?.data?.access_token) access = r2.json.data.access_token;

  const r3 = await refresh(access); // 액세스 토큰은 리프레시로 못 쓴다
  out.accessAsRefresh = r3.status;

  const lo = await call('POST', '/api/auth/logout', {}, access);
  out.logout = lo.status;
  const r4 = await refresh(t0);
  out.afterLogout = r4.status;

  // 기기 로그인은 새 세션 — 로그아웃 전 토큰(t0)은 여전히 못 쓴다(로그인이 floor 를 비움)
  const login = await call('POST', '/api/auth/device/login', { device_id: deviceId });
  if (login.json?.data?.access_token) access = login.json.data.access_token;
  out.relogin = login.status;
  out.afterRelogin = (await refresh(t0)).status;

  ok = out.first === 200 && out.rotated && out.retryWithPrevious === 200 && out.accessAsRefresh === 401
    && out.afterLogout === 401 && out.relogin === 200 && out.afterRelogin === 401;
} catch (e) {
  out.error = String(e);
} finally {
  if (access) { const del = await call('DELETE', '/api/users/account', null, access); out.deleted = del.status; if (del.status !== 200) ok = false; }
}
console.log(JSON.stringify(out, null, 1));
if (ok) console.log('REFRESH_FAMILY_OK'); else process.exit(1);
