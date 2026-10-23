// 2026-10-23 가격 재설정에 쓴 스크립트. 실행: START_DATE=<내일 이후> kw run asc-liftupai -- node ios_set.mjs [--apply]
// iOS 구독 가격 재설정(새 구독자만, 기존 구독자는 옛 가격 유지). --apply 없으면 찾기만 한다.
import { ascToken, ascGet, ascSend } from '/Users/gimjunhyeong/orca/workspaces/liftupai/liftupai/tool/gates/store_api.mjs';
const B = 'https://api.appstoreconnect.apple.com/v1';
const APPLY = process.argv.includes('--apply');
const TARGET = {
  '6760938819': { name: 'monthly', KOR: '9900', JPN: '1200', USA: '9.99' },
  '6760938791': { name: 'yearly', KOR: '59000', JPN: '7800', USA: '49.99' },
};
const t = await ascToken();
for (const [subId, tg] of Object.entries(TARGET)) {
  for (const terr of ['KOR', 'JPN', 'USA']) {
    let hit = null;
    for (let n = `${B}/subscriptions/${subId}/pricePoints?filter[territory]=${terr}&limit=8000`; n && !hit; ) {
      const j = await ascGet(n, t);
      hit = j.data.find((p) => Number(p.attributes.customerPrice) === Number(tg[terr]));
      n = j.links?.next;
    }
    if (!hit) { console.log(`FAIL ${tg.name} ${terr} ${tg[terr]} 가격 포인트 없음`); process.exitCode = 1; continue; }
    console.log(`${tg.name} ${terr} → ${hit.attributes.customerPrice} (수익 ${hit.attributes.proceeds}) point=${hit.id.slice(0, 24)}…`);
    if (!APPLY) continue;
    const res = await ascSend('POST', `${B}/subscriptionPrices`, t, {
      data: {
        type: 'subscriptionPrices',
        attributes: { preserveCurrentPrice: true, startDate: process.env.START_DATE },
        relationships: {
          subscription: { data: { type: 'subscriptions', id: subId } },
          territory: { data: { type: 'territories', id: terr } },
          subscriptionPricePoint: { data: { type: 'subscriptionPricePoints', id: hit.id } },
        },
      },
    });
    console.log(`  적용: startDate=${res.data.attributes.startDate} preserved=${res.data.attributes.preserved}`);
  }
}
