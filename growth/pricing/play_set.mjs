// Play 기본 요금제 가격 재설정(KR·JP·US). 기존 구독자는 Play 기본 동작대로 옛 가격 그대로(가격 이전 안 함).
import { playToken, PLAY_BASE } from '/Users/gimjunhyeong/orca/workspaces/liftupai/liftupai/tool/gates/store_api.mjs';
const APPLY = process.argv.includes('--apply');
const TARGET = {
  pro_monthly: { KR: ['KRW', 9900, 0], JP: ['JPY', 1200, 0], US: ['USD', 9, 990000000] },
  pro_yearly: { KR: ['KRW', 59000, 0], JP: ['JPY', 7800, 0], US: ['USD', 49, 990000000] },
};
const REGION_VERSIONS = ["2026/01"]; // 2026-10 최신. 옛 버전은 AR 통화 불일치로 400
const token = await playToken();
const H = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
for (const [pid, tg] of Object.entries(TARGET)) {
  const sub = await (await fetch(`${PLAY_BASE}/subscriptions/${pid}`, { headers: H })).json();
  for (const bp of sub.basePlans) for (const c of bp.regionalConfigs) {
    const want = tg[c.regionCode];
    if (!want) continue;
    if (c.price.currencyCode !== want[0]) throw new Error(`${pid} ${c.regionCode} 통화가 ${c.price.currencyCode}`);
    console.log(`${pid}/${bp.basePlanId} ${c.regionCode}: ${c.price.units}.${c.price.nanos ?? 0} → ${want[1]}.${want[2]} ${want[0]}`);
    c.price = { currencyCode: want[0], units: String(want[1]), ...(want[2] ? { nanos: want[2] } : {}) };
  }
  if (!APPLY) continue;
  let done = false;
  for (const v of REGION_VERSIONS) {
    const res = await fetch(`${PLAY_BASE}/subscriptions/${pid}?updateMask=basePlans&regionsVersion.version=${encodeURIComponent(v)}`, { method: 'PATCH', headers: H, body: JSON.stringify(sub) });
    const text = await res.text();
    if (res.ok) { console.log(`  적용(regionsVersion ${v})`); done = true; break; }
    console.log(`  ${v}: ${res.status} ${text.replace(/\s+/g, " ").slice(0, 600)}`); if (!/regions? version|not billable/i.test(text)) throw new Error(`PATCH ${res.status} ${text.slice(0, 500)}`);
  }
  if (!done) throw new Error(`${pid}: 맞는 regionsVersion 없음`);
}
