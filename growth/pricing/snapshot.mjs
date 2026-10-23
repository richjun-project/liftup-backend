import { ascToken, ascGet, playToken, PLAY_BASE } from '/Users/gimjunhyeong/orca/workspaces/liftupai/liftupai/tool/gates/store_api.mjs';
const B = 'https://api.appstoreconnect.apple.com/v1';
const out = { takenAt: new Date().toISOString(), ios: {}, play: {} };
const t = await ascToken();
for (const id of ['6760938819', '6760938791']) {
  const rows = [];
  for (let n = `${B}/subscriptions/${id}/prices?include=subscriptionPricePoint,territory&limit=200`; n; ) {
    const j = await ascGet(n, t);
    const pp = Object.fromEntries((j.included ?? []).map((x) => [x.id, x.attributes.customerPrice]));
    for (const p of j.data) rows.push({ territory: p.relationships.territory.data.id, start: p.attributes.startDate, point: p.relationships.subscriptionPricePoint.data.id, price: pp[p.relationships.subscriptionPricePoint.data.id] });
    n = j.links?.next;
  }
  out.ios[id] = rows;
}
const pt = await playToken();
const j = await (await fetch(`${PLAY_BASE}/subscriptions`, { headers: { Authorization: `Bearer ${pt}` } })).json();
for (const s of j.subscriptions) out.play[s.productId] = s;
console.log(JSON.stringify(out));
