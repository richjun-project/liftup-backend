#!/usr/bin/env node
// G4: 나머지 시안 6장(회복 R1·R2, 식단 N1·N2, 분석 카드 M1·M2)을 ko·ja 로 새로 그려 넘침·오류가 없고, 비교 이미지로 합친다 → PROPOSAL_SHOTS_OK
import { spawnSync } from 'node:child_process';
import { statSync, existsSync } from 'node:fs';
const app = '/Users/gimjunhyeong/orca/workspaces/liftupai/liftupai-design';
const env = { ...process.env, PATH: `${process.env.HOME}/Developments/flutter/bin:${process.env.PATH}` };
const names = ['proposal_recovery_R1', 'proposal_recovery_R2', 'proposal_nutrition_N1', 'proposal_nutrition_N2', 'proposal_meal_M1', 'proposal_meal_M2'];
const started = Date.now();
const problems = [];
for (const lang of ['ko', 'ja']) {
  const r = spawnSync('flutter', ['test', '--no-pub', 'test/design_shots/proposal_more_shot.dart', '--update-goldens', `--dart-define=SHOT_LANG=${lang}`], { cwd: app, env, encoding: 'utf8', timeout: 900000, maxBuffer: 64 * 1024 * 1024 });
  const out = `${r.stdout}${r.stderr}`;
  if (r.status !== 0 || !/All tests passed!/.test(out)) problems.push(`${lang}: flutter test 실패`);
  for (const e of new Set(out.match(/SHOT_ERROR: [^\n]*/g) ?? [])) problems.push(`${lang}: ${e.slice(0, 160)}`);
  for (const n of names) {
    const f = `${app}/test/design_shots/out/${n}${lang === 'ko' ? '' : '_ja'}.png`;
    if (!existsSync(f) || statSync(f).mtimeMs < started) problems.push(`${lang}: ${n} 새로 안 그려짐`);
  }
}
const compose = spawnSync('python3', ['-I', '-c', `
from PIL import Image, ImageDraw, ImageFont
import sys
names = ${JSON.stringify(names.map((n) => [n, n.replace('proposal_', '')]))}
font = ImageFont.truetype('/System/Library/Fonts/AppleSDGothicNeo.ttc', 36)
H = 2360; imgs = []
for n, l in names:
    im = Image.open('${app}/test/design_shots/out/' + n + '.png').convert('RGB'); s = min(1, H / im.height)
    imgs.append((l, im.resize((round(im.width * s), round(im.height * s)))))
gap, top = 26, 64
W = sum(i.width for _, i in imgs) + gap * (len(imgs) + 1)
c = Image.new('RGB', (W, H + top + gap), (38, 38, 42)); d = ImageDraw.Draw(c); x = gap
for l, im in imgs:
    d.text((x, 12), l, font=font, fill=(235, 235, 240)); c.paste(im, (x, top)); x += im.width + gap
c.save(sys.argv[1], quality=86); print(c.size)
`, '/private/tmp/claude-501/-Users-gimjunhyeong-Documents/aa939aa2-dc05-46ed-a29a-edc8894e5213/scratchpad/more_proposals.jpg'], { encoding: 'utf8' });
if (compose.status !== 0) problems.push(`비교 이미지 실패 ${compose.stderr.slice(0, 200)}`);
if (problems.length) { console.error(problems.join('\n')); process.exit(1); }
console.log(`shots=${names.length * 2} compose=${compose.stdout.trim()}`);
console.log('PROPOSAL_SHOTS_OK');
