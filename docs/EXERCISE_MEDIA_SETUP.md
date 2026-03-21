# Exercise Media Setup

운동 참고 영상은 `S3 + CloudFront` 조합으로 운영한다. 백엔드는 운동 `slug` 기준의 고정 경로를 만들어 내려주고, 실제 파일은 해당 경로 규칙에 맞춰 S3에 업로드한다.

## 1. 권장 구조

- 저장소: 기존 S3 버킷 사용 가능
- 배포: CloudFront distribution 1개
- URL 규칙:
  - `https://<cloudfront-domain>/exercises/{exerciseSlug}/video.mp4`
  - `https://<cloudfront-domain>/exercises/{exerciseSlug}/thumb.jpg`
  - `https://<cloudfront-domain>/exercises/{exerciseSlug}/animation.gif`

현재 앱 설정은 `APP_EXERCISE_MEDIA_BASE_URL` 값을 기준으로 위 URL을 생성한다.

## 2. S3 권장 키 구조

기존 버킷의 `liftupai/` prefix를 그대로 쓴다면 아래처럼 맞춘다.

```text
s3://<bucket>/liftupai/exercises/barbell-bench-press/video.mp4
s3://<bucket>/liftupai/exercises/barbell-bench-press/thumb.jpg
s3://<bucket>/liftupai/exercises/barbell-bench-press/animation.gif
```

CloudFront origin path는 `/liftupai` 로 잡으면 앱 URL은 `/exercises/...` 형태를 그대로 유지할 수 있다.

## 3. AWS 설정 순서

1. S3에 운동별 폴더 구조로 파일 업로드
2. CloudFront distribution 생성
3. Origin은 해당 S3 버킷으로 지정
4. Origin Access Control(OAC) 연결
5. Origin path를 `/liftupai` 로 설정
6. Alternate domain이 있으면 `cdn.liftupai.com` 같은 서브도메인 연결
7. 배포 완료 후 `APP_EXERCISE_MEDIA_BASE_URL` 를 CloudFront 도메인으로 설정

## 4. S3 업로드 시 메타데이터

- `video.mp4`
  - `Content-Type: video/mp4`
  - `Cache-Control: public, max-age=31536000, immutable`
- `thumb.jpg`
  - `Content-Type: image/jpeg`
  - `Cache-Control: public, max-age=31536000, immutable`
- `animation.gif`
  - `Content-Type: image/gif`
  - `Cache-Control: public, max-age=31536000, immutable`

파일을 교체할 수 있다면 같은 이름 덮어쓰기보다 버저닝이 더 안전하다. 지금 코드 규칙은 고정 파일명을 전제로 하므로, 교체 시 CloudFront invalidation이 필요하다.

## 5. 앱/백엔드 운영 원칙

- 목록 API에서는 `thumb.jpg` 중심으로 사용
- 상세 API 진입 시에만 `video.mp4` 로드
- 목록에서 자동재생, 선로딩, 다중 비디오 로드는 피함
- `animation.gif` 는 꼭 필요한 화면에만 제한적으로 사용

## 6. 환경 변수

```bash
APP_EXERCISE_MEDIA_BASE_URL=https://<cloudfront-domain>
```

예시:

```bash
APP_EXERCISE_MEDIA_BASE_URL=https://d123example.cloudfront.net
```

## 7. 원본 파일 정리 방법

원본 폴더는 그대로 두고, 매니페스트를 먼저 만든다.

```bash
python3 scripts/generate_exercise_media_manifest.py \
  --output-dir build/exercise-media
```

생성 결과:

- `build/exercise-media/exercise-media-manifest.csv`
- `build/exercise-media/exercise-media-summary.txt`

원하면 최종 업로드 트리를 별도 디렉터리에 복사할 수 있다.

```bash
python3 scripts/generate_exercise_media_manifest.py \
  --output-dir build/exercise-media \
  --materialize-dir build/exercise-media-upload
```

그러면 아래 구조가 만들어진다.

```text
build/exercise-media-upload/exercises/<exercise-slug>/thumb.jpg
build/exercise-media-upload/exercises/<exercise-slug>/video.mp4
```

그 다음 S3로 올리면 된다.

```bash
aws s3 sync build/exercise-media-upload s3://<bucket>/liftupai
```

## 8. 언제 추가 개선이 필요한가

아래 조건이면 HLS나 별도 인코딩 파이프라인을 검토한다.

- 영상이 10MB 이상으로 커짐
- 한 운동당 여러 해상도를 제공해야 함
- 시청 시작 속도 최적화가 매우 중요함
- 웹에서 탐색형 프리뷰를 대량으로 보여줘야 함

현재처럼 운동별 짧은 참고 영상이고 파일당 2~3MB 수준이면 `MP4 + CloudFront` 로 충분하다.
