#!/usr/bin/env node
// 오늘 추천 집중 부위 몫 운영 스모크(2026-11-22). 테스트 기기 계정 + 앱과 같은 온보딩 저장(goals: big_arms·glutes, 덤벨·밴드·맨몸), 끝나면 지운다.
// 첫 세션(30분 4종목)이라도 집중 부위가 둘이면 몫 2 — 팔(ARMS) 1개 이상 + 글루트(GLUTES 근육) 1개 이상이어야 MUSCLE 통과.
// 종목 분류는 운영 DB(category·muscle_groups)에서 읽는다.  node scripts/smoke/reco-focus-smoke.mjs → RECO_FOCUS_SMOKE_OK
import { execSync } from 'node:child_process';
const BASE = 'https://api.liftupai.net';
let token = null;
async function call(method, path, body) {
  const res = await fetch(BASE + path + (path.includes('?') ? '&' : '?') + 'locale=ko', {
    method, headers: { 'content-type': 'application/json', 'x-test-device': '1', 'x-platform': 'ios', 'x-app-version': '1.1.50+67', ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null; try { json = await res.json(); } catch {}
  return { status: res.status, json };
}
function classify(ids) {
  if (!ids.length) return {};
  const sql = `SELECT e.id, e.category, GROUP_CONCAT(m.muscle_groups) FROM exercises e LEFT JOIN exercise_muscle_groups m ON m.exercise_id=e.id WHERE e.id IN (${ids.map(Number).join(',')}) GROUP BY e.id;`;
  const out = execSync(`kw run ssh-liftupai -- sh -c 'ssh -o BatchMode=yes -i "$KW_SSH_LIFTUPAI" ubuntu@api.liftupai.net "docker exec -i liftupai-mysql-prod sh -c \\"MYSQL_PWD=\\\\\\$MYSQL_ROOT_PASSWORD mysql -uroot -N liftupai_db\\""'`, { input: sql, encoding: 'utf8' });
  return Object.fromEntries(out.trim().split('\n').filter(Boolean).map((l) => { const [id, cat, mus] = l.split('\t'); return [id, { cat, mus: (mus || '').split(',') }]; }));
}
const out = {}; let ok = false;
try {
  const reg = await call('POST', '/api/auth/device/register', {
    device_id: `reco-focus-smoke-${Date.now()}`, nickname: '점검', experience_level: 'intermediate', goals: ['big_arms', 'glutes'],
    body_info: { gender: 'female', height: 165, weight: 58 },
    workout_preferences: { weekly_days: 3, workout_split: 'full_body', workout_duration: 45, available_equipment: ['DUMBBELL', 'RESISTANCE_BAND', 'BODYWEIGHT'] },
    notification_enabled: false,
  });
  token = reg.json?.data?.access_token; out.register = reg.status;
  if (!token) throw new Error('register failed ' + reg.status);
  // 실제 앱은 가입 뒤 온보딩 결과를 /api/users/onboarding 으로 보낸다 — 세부 집중 목표(big_arms·glutes)는 여기서 저장된다
  const ob = await call('POST', '/api/users/onboarding', {
    nickname: '점검', experience_level: 'intermediate', goals: ['big_arms', 'glutes'], body_info: { gender: 'female', height: 165, weight: 58 },
    pt_style: 'FRIENDLY', notification_enabled: false, weekly_workout_days: 3, workout_split: 'full_body', workout_duration: 45,
    available_equipment: ['DUMBBELL', 'RESISTANCE_BAND', 'BODYWEIGHT'],
  });
  out.onboarding = ob.status;
  const r = await call('GET', '/api/chat/recommendations/workout');
  out.status = r.status;
  const rec = r.json?.data?.workout_recommendation?.recommendation ?? r.json?.data?.workoutRecommendation?.recommendation ?? {};
  const ex = (rec.exercises || []).map((e) => ({ id: String(e.exercise_id ?? e.exerciseId), name: e.name }));
  const cls = classify(ex.map((e) => e.id));
  const arms = ex.filter((e) => cls[e.id]?.cat === 'ARMS').length;
  const glutes = ex.filter((e) => cls[e.id]?.mus.includes('GLUTES')).length;
  out.exercises = ex.map((e) => `${e.name}(${cls[e.id]?.cat}/${cls[e.id]?.mus.join('+')})`);
  out.arms = arms; out.glutes = glutes;
  ok = out.onboarding === 200 && r.status === 200 && ex.length >= 3 && arms >= 1 && glutes >= 1;
} catch (e) { out.error = String(e); }
finally { if (token) { const d = await call('DELETE', '/api/users/account'); out.deleted = d.status; if (d.status !== 200) ok = false; } }
console.log(JSON.stringify(out, null, 1));
if (ok) console.log('RECO_FOCUS_SMOKE_OK'); else process.exit(1);
