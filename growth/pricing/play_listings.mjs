// Play 구독·평생 상품의 표시 이름·혜택(ko·en·ja) — 2026-11-09.
// 그 전엔 세 상품 모두 ko-KR 한 벌, 이름이 ID 그대로(pro_monthly 등)라 Android 결제 창에 'pro_monthly' 가 보였다.
// 또 Play 는 인앱 상품 번역이 다 있어야 앱 기본 언어(ko-KR → en-US)를 바꿀 수 있다.
// 문구는 앱 결제 화면(lib/l10n/app_*.arb 의 proFeature*)과 같게. 변경 전 상태는 play-listings-<날짜>-before.json.
// 사용: kw run play-shared -- node growth/pricing/play_listings.mjs [--apply]   (인자 없으면 비교만)
import fs from 'node:fs';
import { playToken, PLAY_BASE } from '/Users/gimjunhyeong/orca/workspaces/liftupai/liftupai/tool/gates/store_api.mjs';

const REGIONS_VERSION = '2026/01';
const BENEFITS = {
  'ko-KR': ['AI PT 무제한 코칭', 'AI 맞춤 운동 설계', '심층 데이터 분석', '프리미엄 AI 트레이너'],
  'en-US': ['Unlimited AI PT Chat', 'AI Workout Recommendations', 'Advanced Analytics', 'Premium Trainers'],
  'ja-JP': ['無制限AI PTチャット', 'AIワークアウト推薦', '詳細統計分析', 'プレミアムトレーナー'],
};
const SUBS = {
  pro_monthly: { 'ko-KR': 'LIFTUP.AI Pro 월간', 'en-US': 'LIFTUP.AI Pro Monthly', 'ja-JP': 'LIFTUP.AI Pro 月額' },
  pro_yearly: { 'ko-KR': 'LIFTUP.AI Pro 연간', 'en-US': 'LIFTUP.AI Pro Yearly', 'ja-JP': 'LIFTUP.AI Pro 年額' },
};
const LIFETIME = {
  'ko-KR': { title: 'LIFTUP.AI Pro 평생', description: '한 번 결제로 모든 Pro 기능을 평생 이용' },
  'en-US': { title: 'LIFTUP.AI Pro Lifetime', description: 'Pay once and keep every Pro feature for life' },
  'ja-JP': { title: 'LIFTUP.AI Pro 買い切り', description: '一度の購入ですべてのPro機能をずっと利用' },
};

const apply = process.argv.includes('--apply');
const t = await playToken();
const H = { Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' };
const get = async (p) => { const r = await fetch(`${PLAY_BASE}/${p}`, { headers: H }); if (!r.ok) throw new Error(`GET ${p} ${r.status} ${await r.text()}`); return r.json(); };

const before = {};
for (const pid of Object.keys(SUBS)) before[pid] = await get(`subscriptions/${pid}`);
before.pro_lifetime = await get('oneTimeProducts/pro_lifetime');
const stamp = new Date().toISOString().slice(0, 10);
const backup = new URL(`./play-listings-${stamp}-before.json`, import.meta.url);
if (!fs.existsSync(backup)) fs.writeFileSync(backup, JSON.stringify(before, null, 1));

for (const [pid, titles] of Object.entries(SUBS)) {
  const sub = before[pid];
  const listings = Object.entries(titles).map(([languageCode, title]) => ({ languageCode, title, benefits: BENEFITS[languageCode] }));
  console.log(pid, '현재', JSON.stringify(sub.listings), '\n  →', JSON.stringify(listings));
  if (!apply) continue;
  const body = { ...sub, listings };
  const r = await fetch(`${PLAY_BASE}/subscriptions/${pid}?updateMask=listings&regionsVersion.version=${encodeURIComponent(REGIONS_VERSION)}`, { method: 'PATCH', headers: H, body: JSON.stringify(body) });
  const text = await r.text();
  if (!r.ok) throw new Error(`PATCH ${pid} ${r.status} ${text.slice(0, 500)}`);
  const after = JSON.parse(text);
  // 요금제(basePlans)는 그대로여야 한다
  if (JSON.stringify(after.basePlans) !== JSON.stringify(sub.basePlans)) throw new Error(`${pid}: basePlans 가 바뀜 — 백업 ${backup.pathname} 로 되돌릴 것`);
  console.log('  적용, basePlans 동일');
}

{
  const otp = before.pro_lifetime;
  const listings = Object.entries(LIFETIME).map(([languageCode, v]) => ({ languageCode, ...v }));
  console.log('pro_lifetime 현재', JSON.stringify(otp.listings), '\n  →', JSON.stringify(listings));
  if (apply) {
    // 단건 PATCH(oneTimeProducts/{id})는 404 — batchUpdate 만 된다(2026-11-09 확인)
    const body = { ...otp, listings };
    const r = await fetch(`${PLAY_BASE}/oneTimeProducts:batchUpdate`, { method: 'POST', headers: H,
      body: JSON.stringify({ requests: [{ oneTimeProduct: body, updateMask: 'listings', regionsVersion: { version: REGIONS_VERSION } }] }) });
    const text = await r.text();
    if (!r.ok) throw new Error(`batchUpdate pro_lifetime ${r.status} ${text.slice(0, 500)}`);
    const after = JSON.parse(text).oneTimeProducts[0];
    if (JSON.stringify(after.purchaseOptions) !== JSON.stringify(otp.purchaseOptions)) throw new Error('pro_lifetime: purchaseOptions 가 바뀜 — 백업으로 되돌릴 것');
    console.log('  적용, purchaseOptions 동일');
  }
}
