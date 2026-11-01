"""App Store 인앱 이벤트(4주 12회 챌린지) 이미지 — 카드 1920×1080, 상세 1080×1920. 언어별(ko·ja·en-US).

앱과 같은 검정 바탕·플랫 색(그라데이션·후광 없음). 스토어가 이벤트 이름·짧은 설명을 아래쪽에 얹으므로 아래 1/3 은 비운다.
그림: 4주(열) × 3회(행) 진행 칸(앞 5칸 완료) + 현지화된 홈 화면 위쪽(인사·연속 기록·오늘의 운동).
사용: python3 make_event_media.py <앱 저장소>/ios/fastlane/screenshots <출력 폴더>
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

BG = (0, 0, 0)
BLUE = (10, 132, 255)
EMPTY = (28, 28, 30)
EDGE = (58, 58, 60)
WHITE = (255, 255, 255)
LOCALES = ['ko', 'ja', 'en-US']
DONE = 5  # 진행 예시: 1주 3회 + 2주 2회


def grid(draw, x0, y0, cell, gap, radius):
    for col in range(4):
        for row in range(3):
            x = x0 + col * (cell + gap)
            y = y0 + row * (cell + gap)
            done = col * 3 + row < DONE
            box = [x, y, x + cell, y + cell]
            if done:
                draw.rounded_rectangle(box, radius, fill=BLUE)
                w = max(6, cell // 14)
                pts = [(x + cell * 0.28, y + cell * 0.52), (x + cell * 0.44, y + cell * 0.67), (x + cell * 0.73, y + cell * 0.36)]
                draw.line(pts, fill=WHITE, width=w, joint='curve')
            else:
                draw.rounded_rectangle(box, radius, fill=EMPTY, outline=EDGE, width=3)
    return 4 * cell + 3 * gap, 3 * cell + 2 * gap


def phone_crop(src):
    """스토어 스크린샷(1284×2778)의 휴대폰 틀 위쪽 — 인사·연속 기록·플랜·오늘의 운동 2~3개"""
    im = Image.open(src).convert('RGB')
    return im.crop((152, 566, 1132, 1408))  # 원래 틀 선 안쪽만 — 틀은 rounded() 가 다시 그린다


def rounded(im, shot, width, xy):
    """화면 조각을 네 모서리 둥근 카드로 붙인다(아래가 잘린 자국 없이)"""
    ph = shot.resize((width, round(shot.height * width / shot.width)), Image.LANCZOS)
    r = width // 18
    mask = Image.new('L', ph.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, ph.width - 1, ph.height - 1], r, fill=255)
    im.paste(ph, xy, mask)
    ImageDraw.Draw(im).rounded_rectangle([xy[0], xy[1], xy[0] + ph.width - 1, xy[1] + ph.height - 1], r, outline=EDGE, width=3)
    return ph.height


def card(shot, out):
    im = Image.new('RGB', (1920, 1080), BG)
    d = ImageDraw.Draw(im)
    grid(d, 170, 150, 118, 30, 26)
    rounded(im, shot, 700, (1050, 120))
    im.save(out, optimize=True)


def details(shot, out):
    im = Image.new('RGB', (1080, 1920), BG)
    d = ImageDraw.Draw(im)
    gw, gh = 4 * 132 + 3 * 34, 3 * 132 + 2 * 34
    grid(d, (1080 - gw) // 2, 200, 132, 34, 28)
    rounded(im, shot, 860, ((1080 - 860) // 2, 200 + gh + 100))
    im.save(out, optimize=True)


def main():
    shots, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for loc in LOCALES:
        shot = phone_crop(shots / loc / '1_65_home.png')
        card(shot, out / f'{loc}_card.png')
        details(shot, out / f'{loc}_details.png')
        print(loc, 'ok')


if __name__ == '__main__':
    main()
