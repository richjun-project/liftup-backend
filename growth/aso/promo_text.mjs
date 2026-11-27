// iOS 프로모션 문구(170자)를 판매 중 버전의 현지화에 바로 반영한다(심사 없음).
// 문구 원본 = 앱 저장소 ios/fastlane/metadata/<locale>/promotional_text.txt — 파일을 먼저 고치고 이걸 돌린다.
// usage: kw run asc-liftupai -- node promo_text.mjs [--apply] [--app <앱 저장소 경로>]
import { readFileSync } from 'node:fs';
import { ascToken, ascGet, ascSend } from '/Users/gimjunhyeong/orca/workspaces/liftupai/liftupai/tool/gates/store_api.mjs';
const args = process.argv.slice(2);
const apply = args.includes('--apply');
const app = args.includes('--app') ? args[args.indexOf('--app') + 1] : '/Users/gimjunhyeong/orca/workspaces/liftupai/liftupai';
const API = 'https://api.appstoreconnect.apple.com/v1';
const t = await ascToken();
const vers = await ascGet(`${API}/apps/6752910787/appStoreVersions?filter[appStoreState]=READY_FOR_SALE&limit=1`, t);
const v = vers.data?.[0];
if (!v) throw new Error('판매 중 버전 없음');
console.log('live', v.attributes.versionString);
const locs = await ascGet(`${API}/appStoreVersions/${v.id}/appStoreVersionLocalizations?limit=50`, t);
let changed = 0;
for (const l of locs.data) {
  const loc = l.attributes.locale;
  let want;
  try { want = readFileSync(`${app}/ios/fastlane/metadata/${loc}/promotional_text.txt`, 'utf8').trim(); } catch { continue; }
  if ([...want].length > 170) throw new Error(`${loc} ${[...want].length}자 > 170`);
  const now = (l.attributes.promotionalText || '').trim();
  if (now === want) { console.log(loc, 'same'); continue; }
  console.log(loc, `${[...want].length}자`, apply ? 'PATCH' : '(dry-run)');
  if (apply) {
    await ascSend('PATCH', `${API}/appStoreVersionLocalizations/${l.id}`, t, { data: { type: 'appStoreVersionLocalizations', id: l.id, attributes: { promotionalText: want } } });
    changed++;
  }
}
if (apply) {
  const after = await ascGet(`${API}/appStoreVersions/${v.id}/appStoreVersionLocalizations?limit=50`, t);
  for (const l of after.data) console.log('now', l.attributes.locale, (l.attributes.promotionalText || '').slice(0, 40));
  console.log(changed ? 'PROMO_TEXT_OK' : 'PROMO_TEXT_UNCHANGED');
}
