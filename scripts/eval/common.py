"""로컬 e2e 서버(http://localhost:8081)와 통신하는 얇은 HTTP 클라이언트.

프로덕션(api.liftupai.net)은 절대 호출하지 않는다 — base_url 에 그 호스트가 들어오면 거부한다.
"""
from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
import uuid

FORBIDDEN_HOSTS = ("liftupai.net",)


class ApiError(Exception):
    def __init__(self, status: int, body: str, path: str):
        super().__init__(f"{path} -> HTTP {status}: {body[:300]}")
        self.status = status
        self.body = body
        self.path = path


class Client:
    def __init__(self, base_url: str, timeout: int = 300):
        if any(h in base_url for h in FORBIDDEN_HOSTS):
            raise ValueError(f"프로덕션 호출 금지: {base_url}")
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.token: str | None = None
        self.user_id = None
        self.calls = 0

    def call(self, method: str, path: str, body=None, locale: str = "ko", retry: int = 1):
        """(status, parsed_json_or_text, elapsed_ms). 4xx/5xx 도 예외 대신 반환한다."""
        headers = {"Content-Type": "application/json", "Accept-Language": locale}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        last = None
        for attempt in range(retry + 1):
            req = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
            t0 = time.time()
            self.calls += 1
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    raw = r.read()
                    ms = int((time.time() - t0) * 1000)
                    try:
                        return r.status, json.loads(raw or b"{}"), ms
                    except json.JSONDecodeError:
                        return r.status, raw.decode(errors="replace"), ms
            except urllib.error.HTTPError as e:
                ms = int((time.time() - t0) * 1000)
                raw = e.read().decode(errors="replace")
                try:
                    parsed = json.loads(raw)
                except json.JSONDecodeError:
                    parsed = raw
                last = (e.code, parsed, ms)
                if e.code < 500 or attempt == retry:
                    return last
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                ms = int((time.time() - t0) * 1000)
                last = (0, str(e), ms)
                if attempt == retry:
                    return last
            time.sleep(1.5)
        return last

    # ── 앱이 실제로 쓰는 호출들 ──

    def register(self, profile: dict, tag: str = "eval") -> dict:
        """디바이스 가입. 프로필 JSON(profiles.json 항목)을 API 요청으로 바꾼다."""
        req = {
            "device_id": f"{tag}-{uuid.uuid4().hex[:12]}",
            "nickname": profile.get("nickname", "평가유저"),
            "experience_level": profile["experience_level"],
            "goals": profile["goals"],
            "pt_style": profile["pt_style"],
            "body_info": profile["body_info"],
            "workout_preferences": {
                "weekly_days": profile.get("weekly_days", 3),
                "workout_split": profile.get("workout_split", "full_body"),
                "workout_duration": profile.get("session_duration", 45),
                "available_equipment": profile.get("equipment", []),
            },
            "notification_enabled": False,
        }
        injuries = profile.get("injuries") or []
        if injuries:
            # 기준선 서버는 severity 가 없으면 500 을 낸다(앱 버그, 수정 중). 하네스는 채워 보낸다.
            req["injuries"] = [
                {"body_part": i, "severity": "moderate"} if isinstance(i, str) else i for i in injuries
            ]
        if profile.get("strength_assessment"):
            req["strength_assessment"] = profile["strength_assessment"]
        st, res, ms = self.call("POST", "/api/auth/device/register", req, retry=0)
        if st not in (200, 201) or not isinstance(res, dict) or "data" not in res:
            raise ApiError(st, json.dumps(res, ensure_ascii=False) if not isinstance(res, str) else res, "register")
        d = res["data"]
        self.token = d["access_token"]
        self.user_id = d.get("user_id")
        return d

    def set_language(self, locale: str):
        st, res, _ = self.call(
            "PUT", "/api/users/settings", {"app": {"language": locale, "time_zone": "Asia/Seoul"}}, locale=locale
        )
        return st

    def chat(self, message: str, locale: str = "ko"):
        st, res, ms = self.call("POST", "/api/ai/chat", {"message": message}, locale=locale)
        reply = None
        if isinstance(res, dict):
            reply = (res.get("data") or {}).get("reply") if isinstance(res.get("data"), dict) else None
        return st, reply, res, ms

    def reco_basic(self, duration: int | None, locale: str = "ko"):
        q = f"?locale={locale}" + (f"&duration={duration}" if duration else "")
        return self.call("GET", "/api/v2/workouts/recommendations/basic" + q, locale=locale)

    def reco_personalized(self, duration: int | None, locale: str = "ko"):
        # 앱의 chat_service.dart 가 '/api/ai/recommendations/personalized' 를 GET 으로 호출한다.
        q = f"?locale={locale}" + (f"&duration={duration}" if duration else "")
        return self.call("GET", "/api/ai/recommendations/personalized" + q, locale=locale)

    def generate_ai_plan(self, profile: dict, locale: str = "ko"):
        b = profile["body_info"]
        body = {
            "experience_level": profile["experience_level"],
            "goals": profile["goals"],
            "gender": b.get("gender"),
            "age": b.get("age"),
            "height": b.get("height"),
            "weight": b.get("weight"),
            "weekly_days": profile.get("weekly_days", 3),
            "session_duration": profile.get("session_duration", 45),
            "equipment": profile.get("equipment", []),
            "injuries": profile.get("injuries") or None,
            "pt_style": profile["pt_style"],
            "locale": locale,
        }
        return self.call("POST", f"/api/v2/plans/generate-ai?locale={locale}", body, locale=locale)

    def active_plan(self, locale: str = "ko"):
        return self.call("GET", f"/api/v2/plans/active?locale={locale}", locale=locale)

    def plan_day(self, day: int, locale: str = "ko"):
        return self.call("GET", f"/api/v2/plans/active/days/{day}?locale={locale}", locale=locale)

    def exercise_details(self, exercise_id, locale: str = "ko"):
        return self.call("GET", f"/api/v2/exercises/{exercise_id}/details?locale={locale}", locale=locale)


def wait_for_server(base_url: str, max_wait_sec: int = 600, interval: int = 20) -> bool:
    c = Client(base_url, timeout=10)
    deadline = time.time() + max_wait_sec
    while time.time() < deadline:
        st, _, _ = c.call(
            "POST", "/api/version/check",
            {"current_version": "1.1.15", "version_code": 31, "platform": "ios"}, retry=0,
        )
        if st == 200:
            return True
        time.sleep(interval)
    return False
