// App Store 앱 기본 언어(primaryLocale) 조회·변경 — 2026-11-09.
// 기본 언어는 "그 나라 언어(같은 계열 포함)의 현지화가 없을 때" 보이는 스토어 페이지 언어다.
// ko 였을 땐 독일·프랑스·브라질·중국·대만 등 대부분 나라가 한국어 페이지를 봤다 → en-US 로 바꿈.
// 버전 심사 없이 바로 바뀐다(PATCH /v1/apps/{id}). fastlane deliver 는 이 값을 건드리지 않는다.
// 사용: kw run asc-liftupai -- node growth/aso/primary_locale.mjs [ko|en-US]   (인자 없으면 조회만)
import { ascToken, ascGet, ascSend } from '/Users/gimjunhyeong/orca/workspaces/liftupai/liftupai/tool/gates/store_api.mjs';
const APP = 'https://api.appstoreconnect.apple.com/v1/apps/6752910787';
const t = await ascToken();
const target = process.argv[2];
const cur = (await ascGet(`${APP}?fields[apps]=primaryLocale`, t)).data.attributes.primaryLocale;
console.log('현재', cur);
if (target && target !== cur) {
  await ascSend('PATCH', APP, t, { data: { type: 'apps', id: '6752910787', attributes: { primaryLocale: target } } }, { retries: 0 });
  await new Promise((r) => setTimeout(r, 15000)); // PATCH 직후 GET 은 옛 값을 돌려준다(15~20초 뒤 반영)
  console.log('변경 후', (await ascGet(`${APP}?fields[apps]=primaryLocale`, t)).data.attributes.primaryLocale);
}
