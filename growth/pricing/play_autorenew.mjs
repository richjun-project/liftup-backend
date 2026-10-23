// Play 자동 갱신 기본 요금제(monthly-auto·yearly-auto)를 만든다. 기존 선불 요금제(monthly·yearly)는 그대로 둔다.
// 가격·지역은 지금 선불 요금제와 같게 복사한다. --apply 없으면 보여 주기만.
import { playToken, PLAY_BASE } from '/Users/gimjunhyeong/orca/workspaces/liftupai/liftupai/tool/gates/store_api.mjs';
const APPLY = process.argv.includes('--apply');
const PLAN = { pro_monthly: ['monthly', 'monthly-auto', 'P1M'], pro_yearly: ['yearly', 'yearly-auto', 'P1Y'] };
const token = await playToken();
const H = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
async function call(method, url, body) {
  const res = await fetch(url, { method, headers: H, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${res.status} ${url} :: ${text.replace(/\s+/g, ' ').slice(0, 500)}`);
  return text ? JSON.parse(text) : {};
}
for (const [pid, [fromId, newId, period]] of Object.entries(PLAN)) {
  const sub = await call('GET', `${PLAY_BASE}/subscriptions/${pid}`);
  const src = sub.basePlans.find((b) => b.basePlanId === fromId);
  let bp = sub.basePlans.find((b) => b.basePlanId === newId);
  if (!bp) {
    bp = {
      basePlanId: newId,
      autoRenewingBasePlanType: {
        billingPeriodDuration: period,
        gracePeriodDuration: 'P7D',
        resubscribeState: 'RESUBSCRIBE_STATE_ACTIVE',
        prorationMode: 'SUBSCRIPTION_PRORATION_MODE_CHARGE_ON_NEXT_BILLING_DATE',
        legacyCompatible: false,
      },
      regionalConfigs: src.regionalConfigs,
      otherRegionsConfig: src.otherRegionsConfig,
      offerTags: [{ tag: 'auto-renew' }],
    };
    console.log(`${pid}: ${newId} 만들기(${period}, 지역 ${bp.regionalConfigs.length}, KR ${src.regionalConfigs.find((c) => c.regionCode === 'KR').price.units})`);
    if (!APPLY) continue;
    sub.basePlans.push(bp);
    await call('PATCH', `${PLAY_BASE}/subscriptions/${pid}?updateMask=basePlans&regionsVersion.version=2026/01`, sub);
  }
  const now = (await call('GET', `${PLAY_BASE}/subscriptions/${pid}`)).basePlans.find((b) => b.basePlanId === newId);
  console.log(`${pid}/${newId}: ${now?.state}`);
  if (APPLY && now && now.state !== 'ACTIVE') {
    await call('POST', `${PLAY_BASE}/subscriptions/${pid}/basePlans/${newId}:activate`, { packageName: sub.packageName, productId: pid, basePlanId: newId, latencyTolerance: 'PRODUCT_UPDATE_LATENCY_TOLERANCE_LATENCY_SENSITIVE' });
    const after = (await call('GET', `${PLAY_BASE}/subscriptions/${pid}`)).basePlans.find((b) => b.basePlanId === newId);
    console.log(`  활성화 → ${after.state}`);
  }
}
