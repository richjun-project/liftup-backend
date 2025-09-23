# Backend API Requirements V5 - Device-Based Authentication

## 개요
디바이스 ID 기반 자동 로그인 시스템 구현을 위한 백엔드 수정사항 (이메일 없이 디바이스 ID만으로 인증)

## 핵심 변경사항

### 1. 사용자 인증 시스템 개선

#### 1.1 디바이스 ID 기반 인증
- **현재**: 이메일/비밀번호 기반 인증
- **변경**: 디바이스 ID만으로 사용자 식별 및 인증
- **이메일 필드 제거**: 디바이스 기반 계정은 이메일 불필요

#### 1.2 닉네임 Unique 제약 제거
- **이유**: 같은 닉네임을 가진 여러 사용자 허용 (디바이스별 구분)
- **변경사항**:
  ```sql
  ALTER TABLE users DROP INDEX idx_nickname_unique;
  ```

#### 1.3 User 테이블 수정
```sql
-- email 필드를 nullable로 변경 (기존 사용자 호환)
ALTER TABLE users MODIFY COLUMN email VARCHAR(255) NULL;
-- device_id를 primary identifier로 사용
ALTER TABLE users ADD COLUMN device_id VARCHAR(255) UNIQUE;
ALTER TABLE users ADD INDEX idx_device_id (device_id);
```

### 2. 디바이스 정보 저장

#### 2.1 User 테이블 확장
```sql
-- device_id는 위에서 이미 추가됨
ALTER TABLE users ADD COLUMN device_info JSON;

-- device_info JSON 예시:
-- {
--   "platform": "android",
--   "model": "Pixel 6",
--   "manufacturer": "Google",
--   "os_version": "13",
--   "app_version": "1.0.0"
-- }
```

#### 2.2 Device Sessions 테이블 (선택사항)
```sql
CREATE TABLE device_sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    device_info JSON,
    last_login TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_device_user (device_id, user_id)
);
```

### 3. API 엔드포인트 수정

#### 3.1 디바이스 기반 회원가입 API
```
POST /api/auth/device/register
```

**Request Body:**
```json
{
  "device_id": "2b6f0cc904d137be2e1730235f5664094b831186",
  "nickname": "사용자닉네임",
  "device_info": {
    "platform": "android",
    "model": "Pixel 6",
    "manufacturer": "Google",
    "android_version": "13",
    "sdk_int": 33
  },
  "experience_level": "beginner",
  "goals": ["muscle_gain", "weight_loss"],
  "body_info": {
    "height": 175,
    "weight": 70,
    "age": 25,
    "gender": "male"
  },
  "pt_style": "motivating",
  "workout_preferences": {
    "weekly_days": 3,
    "workout_split": "full_body"
  }
}
```

**처리 로직:**
1. device_id로 기존 사용자 확인
2. 신규인 경우 계정 생성 (이메일/비밀번호 없음)
3. JWT 토큰 발급

#### 3.2 디바이스 기반 로그인 API
```
POST /api/auth/device/login
```

**Request Body:**
```json
{
  "device_id": "2b6f0cc904d137be2e1730235f5664094b831186"
}
```

**처리 로직:**
1. device_id로 사용자 조회
2. 존재하면 즉시 JWT 토큰 발급
3. 존재하지 않으면 404 에러 (회원가입 필요)
4. device_sessions 업데이트 (선택사항)

### 4. 비즈니스 로직 변경

#### 4.1 디바이스 인증 서비스
```java
@Service
public class DeviceAuthService {

    public AuthResponse registerDevice(DeviceRegisterRequest request) {
        // 디바이스 ID로 기존 사용자 확인
        Optional<User> existingUser = userRepository.findByDeviceId(request.getDeviceId());

        if (existingUser.isPresent()) {
            throw new DeviceAlreadyRegisteredException("Device already registered");
        }

        // 새 사용자 생성 (이메일/비밀번호 없음)
        User user = new User();
        user.setDeviceId(request.getDeviceId());
        user.setNickname(request.getNickname());
        user.setDeviceInfo(request.getDeviceInfo());
        // 온보딩 정보 저장
        user.setExperienceLevel(request.getExperienceLevel());
        user.setGoals(request.getGoals());
        // ... 기타 정보

        userRepository.save(user);

        // JWT 토큰 생성
        return createAuthResponse(user);
    }

    public AuthResponse loginDevice(String deviceId) {
        User user = userRepository.findByDeviceId(deviceId)
            .orElseThrow(() -> new UserNotFoundException("Device not registered"));

        // 마지막 로그인 시간 업데이트
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        return createAuthResponse(user);
    }
}
```

#### 4.2 중복 가입 방지
```java
public boolean checkExistingDevice(String deviceId) {
    return userRepository.findByDeviceId(deviceId).isPresent();
}
```

### 5. 보안 고려사항

#### 5.1 디바이스 ID 검증
- 클라이언트에서 보낸 디바이스 ID 형식 검증
- 너무 짧거나 긴 ID 거부
- 특수문자 필터링

```java
public boolean isValidDeviceId(String deviceId) {
    if (deviceId == null || deviceId.length() < 16 || deviceId.length() > 255) {
        return false;
    }
    // 알파벳, 숫자, 하이픈, 언더스코어만 허용
    return deviceId.matches("^[a-zA-Z0-9_-]+$");
}
```

#### 5.2 Rate Limiting
- 동일 디바이스에서 과도한 로그인 시도 방지
- IP + 디바이스 ID 조합으로 제한

### 6. 마이그레이션 전략

#### 6.1 기존 사용자 처리
```sql
-- 기존 사용자는 device_id가 NULL
-- 앱 업데이트 후 첫 로그인 시 device_id 업데이트
UPDATE users
SET device_id = ?
WHERE id = ? AND device_id IS NULL;
```

#### 6.2 단계별 적용
1. **Phase 1**: 새 필드 추가, 기존 로직 유지
2. **Phase 2**: 신규 가입자부터 디바이스 기반 적용
3. **Phase 3**: 기존 사용자 마이그레이션

### 7. API Response 수정

#### 7.1 로그인/회원가입 Response
```json
{
  "success": true,
  "data": {
    "user": {
      "id": "123",
      "device_id": "2b6f0cc904d137be2e1730235f5664094b831186",
      "nickname": "운동왕",
      "is_device_account": true,
      "device_registered_at": "2024-01-20T10:00:00Z"
    },
    "access_token": "...",
    "refresh_token": "..."
  }
}
```

### 8. 예외 처리

#### 8.1 디바이스 변경 시나리오
- 사용자가 새 기기로 변경한 경우
- 기존 데이터 마이그레이션 옵션 제공

#### 8.2 에러 코드
```java
public enum AuthError {
    INVALID_DEVICE_ID("E001", "유효하지 않은 디바이스 ID"),
    DEVICE_ALREADY_REGISTERED("E002", "이미 등록된 디바이스"),
    DEVICE_MISMATCH("E003", "디바이스 정보 불일치");
}
```

## 구현 우선순위

1. **필수 (Phase 1)**
   - User 테이블에 device_id 컬럼 추가 (UNIQUE)
   - email 필드 nullable로 변경
   - 닉네임 unique 제약 제거
   - `/api/auth/device/register`, `/api/auth/device/login` API 구현

2. **권장 (Phase 2)**
   - Device 정보 저장 (device_info JSON)
   - 디바이스 기반 계정 특별 처리 로직
   - 중복 가입 방지

3. **선택 (Phase 3)**
   - Device sessions 테이블
   - 디바이스 변경 시나리오 처리
   - 상세 에러 처리

## 테스트 시나리오

1. **신규 디바이스 등록**
   ```
   POST /api/auth/device/register
   {
     "device_id": "test123device",
     "nickname": "테스터",
     "experience_level": "beginner",
     "goals": ["muscle_gain"],
     "pt_style": "motivating"
   }
   ```

2. **기존 디바이스 로그인**
   ```
   POST /api/auth/device/login
   {
     "device_id": "test123device"
   }
   ```

3. **존재하지 않는 디바이스 로그인 시도**
   ```
   POST /api/auth/device/login
   {
     "device_id": "nonexistent_device"
   }

   Expected: 404 Not Found
   ```

## 주의사항

1. **데이터베이스 백업**: 스키마 변경 전 반드시 백업
2. **하위 호환성**: 기존 이메일 기반 사용자 로그인 유지 (별도 엔드포인트)
3. **디바이스 ID 검증**: 클라이언트에서 보낸 device_id 형식 검증 필수
4. **로깅**: 디바이스 기반 인증 관련 상세 로그 남기기
5. **모니터링**: 디바이스별 가입/로그인 통계 수집

## 예상 효과

1. **사용자 경험 개선**
   - 로그인/회원가입 과정 간소화
   - 재설치 시에도 계정 유지

2. **데이터 일관성**
   - 디바이스당 하나의 계정
   - 중복 가입 방지

3. **보안**
   - 디바이스 ID 기반 인증
   - 무작위 비밀번호 생성으로 보안 강화