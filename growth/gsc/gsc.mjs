// Google Search Console — 서비스 계정(liftupai-fastlane, kw play-shared)으로 liftupai.net 소유 확인·사이트맵 제출·색인 확인.
// 2026-11-02: 대표 손 대기였던 "Search Console 등록"을 사람 로그인 없이 하려고 만들었다.
//   - 프로젝트 spring-boot3-mini-project 에 Site Verification API·Search Console API 를 켰다(gcloud, ggprgrkjh2).
//   - 소유 확인은 FILE 방식: 토큰 파일을 랜딩 public/ 에 올려 배포한 뒤 verify. 그 파일을 지우면 소유가 풀린다.
// 사용: kw run play-shared -- node growth/gsc/gsc.mjs <명령>
//   token-file            → FILE 방식 확인 토큰(파일 이름) 출력
//   verify                → 소유 확인(webResource.insert) — 파일이 배포된 뒤
//   owners [이메일…]      → 소유자 목록 보기 / 이메일을 위임 소유자로 추가(대표가 GSC 화면에서 보도록)
//   add                   → Search Console 에 속성 추가
//   sitemap               → sitemap.xml 제출 + 상태
//   status                → 속성·사이트맵 상태
//   inspect <url…>        → URL 검사(색인 여부·마지막 크롤)
//   perf [일수]           → 검색 실적(검색어·페이지별 노출·클릭)
import { readFileSync } from 'node:fs';
import { createSign } from 'node:crypto';

const SITE = 'https://liftupai.net/';
const SITEMAP = 'https://liftupai.net/sitemap.xml';
const SCOPES = [
  'https://www.googleapis.com/auth/siteverification',
  'https://www.googleapis.com/auth/webmasters',
].join(' ');

function b64url(buf) {
  return Buffer.from(buf).toString('base64').replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

async function token() {
  const keyPath = process.env.KW_PLAY_SHARED;
  if (!keyPath) throw new Error('KW_PLAY_SHARED 없음 — kw run play-shared -- 로 실행');
  const sa = JSON.parse(readFileSync(keyPath, 'utf8'));
  const now = Math.floor(Date.now() / 1000);
  const head = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const body = b64url(JSON.stringify({ iss: sa.client_email, scope: SCOPES, aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 }));
  const s = createSign('RSA-SHA256');
  s.update(`${head}.${body}`);
  const assertion = `${head}.${body}.${b64url(s.sign(sa.private_key))}`;
  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }),
  });
  const t = await r.text();
  if (!r.ok) throw new Error(`토큰 실패 ${r.status} ${t.slice(0, 200)}`);
  return { tok: JSON.parse(t).access_token, email: sa.client_email };
}

async function call(tok, method, url, body) {
  const r = await fetch(url, {
    method,
    headers: { Authorization: `Bearer ${tok}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await r.text();
  let json;
  try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text.slice(0, 300) }; }
  return { status: r.status, json };
}

const SV = 'https://www.googleapis.com/siteVerification/v1';
const WM = 'https://www.googleapis.com/webmasters/v3';
const SC = 'https://searchconsole.googleapis.com/v1';
const enc = encodeURIComponent;

const [cmd, ...args] = process.argv.slice(2);
const { tok, email } = await token();

if (cmd === 'token-file') {
  const r = await call(tok, 'POST', `${SV}/token`, { site: { type: 'SITE', identifier: SITE }, verificationMethod: 'FILE' });
  console.log(r.status, JSON.stringify(r.json));
} else if (cmd === 'verify') {
  const r = await call(tok, 'POST', `${SV}/webResource?verificationMethod=FILE`, { site: { type: 'SITE', identifier: SITE } });
  console.log(r.status, JSON.stringify(r.json));
} else if (cmd === 'owners') {
  const id = enc(SITE);
  const cur = await call(tok, 'GET', `${SV}/webResource/${id}`);
  if (cur.status !== 200) { console.log(cur.status, JSON.stringify(cur.json)); process.exit(1); }
  if (args.length) {
    const owners = [...new Set([...(cur.json.owners || []), ...args])];
    const r = await call(tok, 'PUT', `${SV}/webResource/${id}`, { ...cur.json, owners });
    console.log(r.status, JSON.stringify(r.json.owners || r.json));
  } else {
    console.log(JSON.stringify(cur.json.owners));
  }
} else if (cmd === 'add') {
  const r = await call(tok, 'PUT', `${WM}/sites/${enc(SITE)}`);
  console.log(r.status, JSON.stringify(r.json));
} else if (cmd === 'sitemap') {
  const r = await call(tok, 'PUT', `${WM}/sites/${enc(SITE)}/sitemaps/${enc(SITEMAP)}`);
  console.log('submit', r.status, JSON.stringify(r.json));
  const s = await call(tok, 'GET', `${WM}/sites/${enc(SITE)}/sitemaps`);
  console.log(s.status, JSON.stringify(s.json));
} else if (cmd === 'status') {
  console.log('as', email);
  const s = await call(tok, 'GET', `${WM}/sites`);
  console.log('sites', s.status, JSON.stringify(s.json));
  const m = await call(tok, 'GET', `${WM}/sites/${enc(SITE)}/sitemaps`);
  console.log('sitemaps', m.status, JSON.stringify(m.json));
} else if (cmd === 'inspect') {
  for (const url of args) {
    const r = await call(tok, 'POST', `${SC}/urlInspection/index:inspect`, { inspectionUrl: url, siteUrl: SITE });
    const x = r.json.inspectionResult?.indexStatusResult;
    console.log(url, r.status, x ? JSON.stringify({ verdict: x.verdict, coverage: x.coverageState, lastCrawl: x.lastCrawlTime, robots: x.robotsTxtState, fetch: x.pageFetchState }) : JSON.stringify(r.json).slice(0, 300));
  }
} else if (cmd === 'perf') {
  const days = Number(args[0] || 28);
  const end = new Date(Date.now() - 2 * 864e5).toISOString().slice(0, 10);
  const start = new Date(Date.now() - (days + 2) * 864e5).toISOString().slice(0, 10);
  for (const dim of ['query', 'page']) {
    const r = await call(tok, 'POST', `${WM}/sites/${enc(SITE)}/searchAnalytics/query`, { startDate: start, endDate: end, dimensions: [dim], rowLimit: 25 });
    console.log(dim, r.status, JSON.stringify(r.json.rows || r.json).slice(0, 2000));
  }
} else {
  console.log('명령: token-file | verify | owners [이메일…] | add | sitemap | status | inspect <url…> | perf [일수]');
}
