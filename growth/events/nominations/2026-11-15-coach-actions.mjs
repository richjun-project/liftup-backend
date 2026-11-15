import { ascToken, ascSend } from '/Users/gimjunhyeong/orca/workspaces/liftupai/liftupai/tool/gates/store_api.mjs';
const name = 'LiftUp AI · a coach that changes your plan and shows how';
const description = `LiftUp AI is an AI personal trainer for people who can't afford, or don't want, a gym PT. In version 1.1.44 the coach doesn't just give advice. It changes your plan for you, and shows you exactly what it changed.

Tell it "I have no equipment at home" and it saves that and rebuilds your plan around bodyweight moves. Say "I want to do pull day today" and today's workout switches. Every change the coach makes is listed under "Trainer changes", and the most recent one can be undone in one tap. After each workout it tells you what comes next. Setup now asks one simple question about where you train: gym, home with dumbbells, or home with no equipment.

For beginners, the first workout is kept to four exercises and about 30 minutes, because the first session is the one most people never finish. Messages that mention an emergency, eating-disorder risk or pregnancy get a cautious reply without any plan changes.

Fully localized in Korean, Japanese and English.`;
const notes = `Version 1.1.44 is in App Review now and should be live in mid-November 2026. We think it fits New Year fitness stories: it is built for the first week of a resolution, with a short first workout, a plan that matches the equipment you actually have, and a coach you can talk to instead of settings screens. Free to download; Pro is a subscription with a one-week free trial. Built by a one-person team in Korea; most new users come from Japan.`;
if (description.length > 1000) throw new Error('description ' + description.length);
console.log('lens', name.length, description.length, notes.length);
if (process.argv[2] !== 'go') process.exit(0);
const t = await ascToken();
const body = { data: { type: 'nominations', attributes: {
  name, type: 'APP_ENHANCEMENTS', description, notes, submitted: true,
  publishStartDate: '2026-12-26T00:00:00Z',
  deviceFamilies: ['IPHONE', 'IPAD'],
  locales: ['EN-AU', 'EN-CA', 'EN-GB', 'EN-US', 'JA', 'KO'],
  hasInAppEvents: false, launchInSelectMarketsFirst: false, preOrderEnabled: false,
}, relationships: { relatedApps: { data: [{ type: 'apps', id: '6752910787' }] } } } };
const r = await ascSend('POST', 'https://api.appstoreconnect.apple.com/v1/nominations', t, body, { retries: 1 });
console.log(JSON.stringify(r).slice(0, 600));
