-- 최신보다 낮은 앱에 권장 업데이트 안내창을 띄울지(AppVersion.recommendUpdate)
ALTER TABLE app_versions ADD COLUMN recommend_update BIT(1) NOT NULL DEFAULT b'0';
