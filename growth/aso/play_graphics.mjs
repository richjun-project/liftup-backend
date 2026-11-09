// Play 등록정보 그림(ko·en·ja)과 기본 언어 — 2026-11-09.
// 그 전: en-US·ja-JP 등록정보에 그림이 하나도 없어 영어·일본어 방문자도 한국어 옛 스크린샷(ko-KR)을 봤고,
// 기본 언어가 ko-KR 이라 번역이 없는 언어(독일어·포르투갈어 등) 사용자는 한국어 페이지를 봤다.
// 이 스크립트: 세 언어 휴대전화 스크린샷 = iOS 현재 스크린샷(1284x2778 → 검은 여백 1392x2778, Play 상한 2:1),
// en-US·ja-JP 아이콘·대표 그래픽 = ko-KR 것(글자 없음), 기본 언어 en-US. 옛 그림은 play-2026-11-09/ko_*.png.
// 기본 언어를 바꾸려면 인앱 상품 번역(growth/pricing/play_listings.mjs)과 en-US 아이콘이 먼저 있어야 한다(Play 403).
// 사용: kw run play-shared -- node growth/aso/play_graphics.mjs [--dry]   (--dry 는 검증까지만, 커밋 안 함)
import fs from 'node:fs';
import { playToken, PLAY_BASE, PLAY_PACKAGE } from '/Users/gimjunhyeong/orca/workspaces/liftupai/liftupai/tool/gates/store_api.mjs';

const DIR = new URL('./play-2026-11-09/', import.meta.url).pathname;
const UPLOAD = `https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/${PLAY_PACKAGE}`;
const SHOTS = { 'ko-KR': 'ko', 'en-US': 'en-US', 'ja-JP': 'ja' };
const dry = process.argv.includes('--dry');

const t = await playToken();
const H = { Authorization: `Bearer ${t}` };
const call = async (method, url, body, headers = {}) => {
  const r = await fetch(url, { method, headers: { ...H, ...headers }, body });
  const text = await r.text();
  return { ok: r.ok, status: r.status, text };
};
const must = (res, what) => { if (!res.ok) throw new Error(`${what} ${res.status} ${res.text.slice(0, 400)}`); return res.text ? JSON.parse(res.text) : null; };
const upload = async (edit, lang, type, file) => must(
  await call('POST', `${UPLOAD}/edits/${edit}/listings/${lang}/${type}?uploadType=media`, fs.readFileSync(file), { 'Content-Type': 'image/png' }),
  `upload ${lang} ${type} ${file}`);

const edit = must(await call('POST', `${PLAY_BASE}/edits`, '{}', { 'Content-Type': 'application/json' }), 'edit').id;
console.log('edit', edit);

for (const lang of ['en-US', 'ja-JP']) {
  for (const [type, file] of [['icon', 'ko_icon_0.png'], ['featureGraphic', 'ko_featureGraphic_0.png']]) {
    must(await call('DELETE', `${PLAY_BASE}/edits/${edit}/listings/${lang}/${type}`), `deleteall ${lang} ${type}`);
    await upload(edit, lang, type, DIR + file);
  }
}
for (const [lang, src] of Object.entries(SHOTS)) {
  must(await call('DELETE', `${PLAY_BASE}/edits/${edit}/listings/${lang}/phoneScreenshots`), `deleteall ${lang} phoneScreenshots`);
  for (const n of ['1_65_home', '2_65_chat', '3_65_recovery']) await upload(edit, lang, 'phoneScreenshots', `${DIR}new/${src}_${n}.png`);
  const list = must(await call('GET', `${PLAY_BASE}/edits/${edit}/listings/${lang}/phoneScreenshots`), 'list');
  console.log(lang, 'phoneScreenshots', list.images.length);
}
must(await call('PATCH', `${PLAY_BASE}/edits/${edit}/details`, JSON.stringify({ defaultLanguage: 'en-US' }), { 'Content-Type': 'application/json' }), 'details');
const v = await call('POST', `${PLAY_BASE}/edits/${edit}:validate`);
console.log('validate', v.status, v.text.replace(/\s+/g, ' ').slice(0, 300));
if (dry || !v.ok) {
  await call('DELETE', `${PLAY_BASE}/edits/${edit}`);
  console.log(dry ? '--dry: 커밋 안 함(편집 삭제)' : '검증 실패 — 편집 삭제');
  process.exit(v.ok ? 0 : 1);
}
let c = await call('POST', `${PLAY_BASE}/edits/${edit}:commit`);
console.log('commit', c.status, c.text.replace(/\s+/g, ' ').slice(0, 300));
if (!c.ok && /sent for review automatically/i.test(c.text)) {
  // 등록정보 변경은 API 로 자동 심사에 못 보낼 때가 있다 → 콘솔 "검토를 위해 변경사항 보내기" 대기로 커밋
  c = await call('POST', `${PLAY_BASE}/edits/${edit}:commit?changesNotSentForReview=true`);
  console.log('commit(changesNotSentForReview)', c.status, c.text.replace(/\s+/g, ' ').slice(0, 300));
}
process.exit(c.ok ? 0 : 1);
