#!/usr/bin/env node
// G5: 운영 설정은 그대로 — 실행 중인 JAR 의 채팅·플랜 모델 설정이 별칭 그대로이고, 모델을 바꾸는 환경변수가 없고, 서비스가 응답한다 → PROD_UNTOUCHED_OK
import { spawnSync } from 'node:child_process';
const remote = `
# 운영 컨테이너는 ~/liftup-backend/build/libs 의 JAR 로 만든 이미지다 — 그 JAR 의 application.yml 을 읽는다(컨테이너엔 unzip 이 없다)
python3 -c "import zipfile,re; y=zipfile.ZipFile('/home/ubuntu/liftup-backend/build/libs/liftupai-0.0.1-SNAPSHOT.jar').read('BOOT-INF/classes/application.yml').decode(); print('\\n'.join(l for l in y.splitlines() if re.match(r'^  (model|plan-model):', l)))"
echo JAR_IN_USE $(docker inspect -f '{{.Created}}' $(docker inspect -f '{{.Image}}' liftupai-app-prod)) vs $(stat -c %y /home/ubuntu/liftup-backend/build/libs/liftupai-0.0.1-SNAPSHOT.jar | cut -c1-19)
echo ENV; docker exec liftupai-app-prod printenv | grep -iE '^GEMINI_(MODEL|PLAN_MODEL)|AI_AGENT_MODE' | sed -E 's/=.*/=<set>/'
echo HTTP $(curl -s -o /dev/null -w '%{http_code}' -m 10 https://api.liftupai.net/api/v2/templates)
`;
const r = spawnSync('kw', ['run', 'ssh-liftupai', '--', 'sh', '-c', 'ssh -o BatchMode=yes -o ConnectTimeout=20 -i "$KW_SSH_LIFTUPAI" ubuntu@api.liftupai.net bash -s'], { input: remote, encoding: 'utf8', timeout: 120000 });
const out = `${r.stdout}${r.stderr}`;
console.log(out.trim().split('\n').filter((l) => !/password/i.test(l)).join('\n'));
const ok = /model:\s*gemini-flash-latest/.test(out) && /plan-model:\s*gemini-pro-latest/.test(out)
  && !/GEMINI_(MODEL|PLAN_MODEL)=/.test(out) && /HTTP (200|401|403)/.test(out);
if (!ok) { console.error('운영 설정이 평가 전과 다르거나 확인 실패'); process.exit(1); }
console.log('PROD_UNTOUCHED_OK');
