# Flutter API 연동 가이드

## 📱 Flutter 클라이언트 API 업데이트 가이드

### 1. 기본 설정

#### API Base URL 설정
```dart
class ApiConfig {
  static const String baseUrl = 'https://api.liftupai.com';
  static const String apiVersion = '/api/v2';  // V2로 변경

  static String get apiUrl => '$baseUrl$apiVersion';
}
```

#### HTTP Client 설정
```dart
import 'package:dio/dio.dart';

class ApiClient {
  late Dio _dio;

  ApiClient() {
    _dio = Dio(BaseOptions(
      baseUrl: ApiConfig.apiUrl,
      connectTimeout: const Duration(seconds: 30),
      receiveTimeout: const Duration(seconds: 30),
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
    ));

    // Interceptor 추가
    _dio.interceptors.add(AuthInterceptor());
    _dio.interceptors.add(LoggingInterceptor());
  }
}
```

#### 인증 Interceptor
```dart
class AuthInterceptor extends Interceptor {
  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final token = LocalStorage.getToken();
    if (token != null) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    super.onRequest(options, handler);
  }
}
```

---

## 2. API 엔드포인트 변경 사항

### ❌ 제거해야 할 V1 엔드포인트
```dart
// 이전 (V1) - 제거
static const String startWorkout = '/api/workouts/start';
static const String endWorkout = '/api/workouts/{id}/end';
static const String getStats = '/api/stats/overview';
static const String getExercises = '/api/exercises';
```

### ✅ 새로운 V2 엔드포인트
```dart
// 새로운 (V2)
class ApiEndpoints {
  // Workout APIs
  static const String startNewWorkout = '/workouts/start/new';
  static const String continueWorkout = '/workouts/start/continue';
  static const String completeWorkout = '/workouts/{sessionId}/complete';
  static const String currentSession = '/workouts/current-session';  // ✅ V2에 추가됨
  static const String workoutHistory = '/workouts/history';         // ✅ V2에 추가됨
  static const String workoutDetail = '/workouts/{sessionId}';      // ✅ V2에 추가됨
  static const String programStatus = '/workouts/program-status';   // ✅ V2에 추가됨

  // Exercise APIs
  static const String exercises = '/exercises';
  static const String exerciseDetail = '/exercises/{exerciseId}';

  // Stats APIs
  static const String statsOverview = '/stats/overview';
  static const String statsVolume = '/stats/volume';
  static const String statsPersonalRecords = '/stats/personal-records';
  static const String statsCalendar = '/stats/calendar';
  static const String statsWeekly = '/stats/weekly';

  // Program APIs
  static const String generateProgram = '/workouts/generate-program';
  static const String updatePlan = '/workouts/plan';
  static const String todayRecommendation = '/workouts/recommendations/today';
  static const String quickRecommendation = '/workouts/recommendations/quick';  // ✅ V2에 추가됨
  static const String startRecommended = '/workouts/start-recommended';         // ✅ V2에 추가됨
}
```

---

## 3. Service 클래스 업데이트

### WorkoutService 수정
```dart
class WorkoutService {
  final ApiClient _apiClient;

  WorkoutService(this._apiClient);

  // 새 운동 시작 (V2)
  Future<StartWorkoutResponse> startNewWorkout({
    required List<PlannedExercise> exercises,
    String workoutType = 'CUSTOM',
  }) async {
    try {
      final response = await _apiClient.post(
        ApiEndpoints.startNewWorkout,
        data: {
          'workout_type': workoutType,
          'exercises': exercises.map((e) => e.toJson()).toList(),
        },
      );

      return StartWorkoutResponse.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  // 진행 중인 운동 이어하기 (V2)
  Future<StartWorkoutResponse> continueWorkout() async {
    try {
      final response = await _apiClient.post(
        ApiEndpoints.continueWorkout,
      );

      return StartWorkoutResponse.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  // 운동 완료 (V2)
  Future<CompleteWorkoutResponse> completeWorkout({
    required int sessionId,
    required int duration,
    required List<CompletedExercise> exercises,
    String? notes,
  }) async {
    try {
      final response = await _apiClient.put(
        ApiEndpoints.completeWorkout.replaceAll('{sessionId}', '$sessionId'),
        data: {
          'duration': duration,
          'notes': notes,
          'exercises': exercises.map((e) => e.toJson()).toList(),
        },
      );

      return CompleteWorkoutResponse.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  // 현재 세션 조회
  Future<WorkoutSession?> getCurrentSession() async {
    try {
      final response = await _apiClient.get(ApiEndpoints.currentSession);

      if (response.data['data'] == null) return null;
      return WorkoutSession.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }
}
```

---

## 4. Model 클래스 업데이트

### PlannedExercise 모델 (order_index 필수 추가)
```dart
class PlannedExercise {
  final int exerciseId;
  final int sets;
  final int targetReps;
  final double? weight;
  final int orderIndex;  // V2에서 필수

  PlannedExercise({
    required this.exerciseId,
    this.sets = 3,
    this.targetReps = 10,
    this.weight,
    required this.orderIndex,  // 필수로 변경
  });

  Map<String, dynamic> toJson() {
    return {
      'exercise_id': exerciseId,
      'sets': sets,
      'target_reps': targetReps,
      'weight': weight,
      'order_index': orderIndex,  // 필수 추가
    };
  }
}
```

### CompletedExercise 모델
```dart
class CompletedExercise {
  final int exerciseId;
  final List<ExerciseSet> sets;

  CompletedExercise({
    required this.exerciseId,
    required this.sets,
  });

  Map<String, dynamic> toJson() {
    return {
      'exercise_id': exerciseId,
      'sets': sets.map((s) => s.toJson()).toList(),
    };
  }
}
```

### ExerciseSet 모델
```dart
class ExerciseSet {
  final int reps;
  final double? weight;
  final int? restTime;
  final bool completed;
  final String? notes;

  ExerciseSet({
    required this.reps,
    this.weight,
    this.restTime,
    this.completed = false,
    this.notes,
  });

  Map<String, dynamic> toJson() {
    return {
      'reps': reps,
      'weight': weight,
      'rest_time': restTime,
      'completed': completed,
      'notes': notes,
    };
  }
}
```

---

## 5. 에러 처리

### API 에러 핸들링
```dart
class ApiException implements Exception {
  final String code;
  final String message;
  final int? statusCode;

  ApiException({
    required this.code,
    required this.message,
    this.statusCode,
  });
}

Exception _handleError(dynamic error) {
  if (error is DioException) {
    switch (error.response?.statusCode) {
      case 400:
        return ApiException(
          code: error.response?.data['code'] ?? 'BAD_REQUEST',
          message: error.response?.data['message'] ?? '잘못된 요청입니다',
          statusCode: 400,
        );
      case 401:
        // 토큰 만료 처리
        _handleTokenExpired();
        return ApiException(
          code: 'UNAUTHORIZED',
          message: '인증이 필요합니다',
          statusCode: 401,
        );
      case 409:
        // 충돌 (예: 이미 진행 중인 운동이 있음)
        return ApiException(
          code: error.response?.data['code'] ?? 'CONFLICT',
          message: error.response?.data['message'] ?? '리소스 충돌',
          statusCode: 409,
        );
      default:
        return ApiException(
          code: 'UNKNOWN',
          message: '알 수 없는 오류가 발생했습니다',
          statusCode: error.response?.statusCode,
        );
    }
  }
  return Exception('네트워크 오류가 발생했습니다');
}
```

---

## 6. 상태 관리 (Provider/Riverpod 예시)

### WorkoutProvider
```dart
class WorkoutNotifier extends StateNotifier<WorkoutState> {
  final WorkoutService _workoutService;

  WorkoutNotifier(this._workoutService) : super(WorkoutState.initial());

  // 새 운동 시작
  Future<void> startNewWorkout(List<PlannedExercise> exercises) async {
    state = state.copyWith(isLoading: true);

    try {
      // order_index 자동 설정
      final exercisesWithOrder = exercises.asMap().entries.map((e) {
        return PlannedExercise(
          exerciseId: e.value.exerciseId,
          sets: e.value.sets,
          targetReps: e.value.targetReps,
          weight: e.value.weight,
          orderIndex: e.key,  // 인덱스를 order_index로 사용
        );
      }).toList();

      final response = await _workoutService.startNewWorkout(
        exercises: exercisesWithOrder,
      );

      state = state.copyWith(
        isLoading: false,
        currentSession: response,
      );
    } catch (e) {
      state = state.copyWith(
        isLoading: false,
        error: e.toString(),
      );
    }
  }

  // 진행 중인 운동 확인 및 처리
  Future<void> checkAndHandleExistingSession() async {
    try {
      final currentSession = await _workoutService.getCurrentSession();

      if (currentSession != null) {
        // 진행 중인 운동이 있으면 선택 다이얼로그 표시
        final shouldContinue = await _showContinueDialog();

        if (shouldContinue) {
          await continueWorkout();
        } else {
          // 새로 시작하려면 먼저 기존 세션 완료 처리
          await completeWorkout(currentSession.id, [], 0);
          // 그 다음 새 운동 시작
        }
      }
    } catch (e) {
      // 에러 처리
    }
  }
}
```

---

## 7. UI 업데이트 예시

### 운동 시작 화면
```dart
class WorkoutStartScreen extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final workoutState = ref.watch(workoutProvider);

    return Scaffold(
      body: Column(
        children: [
          // 운동 시작 버튼
          ElevatedButton(
            onPressed: () async {
              // 먼저 진행 중인 세션 확인
              final currentSession = await ref
                  .read(workoutServiceProvider)
                  .getCurrentSession();

              if (currentSession != null) {
                // 진행 중인 운동이 있으면 선택
                showDialog(
                  context: context,
                  builder: (_) => AlertDialog(
                    title: Text('진행 중인 운동'),
                    content: Text('진행 중인 운동이 있습니다. 어떻게 하시겠습니까?'),
                    actions: [
                      TextButton(
                        onPressed: () {
                          Navigator.pop(context);
                          ref.read(workoutProvider.notifier)
                              .continueWorkout();
                        },
                        child: Text('이어하기'),
                      ),
                      TextButton(
                        onPressed: () {
                          Navigator.pop(context);
                          ref.read(workoutProvider.notifier)
                              .startNewWorkout(selectedExercises);
                        },
                        child: Text('새로 시작'),
                      ),
                    ],
                  ),
                );
              } else {
                // 진행 중인 운동이 없으면 바로 시작
                ref.read(workoutProvider.notifier)
                    .startNewWorkout(selectedExercises);
              }
            },
            child: Text('운동 시작'),
          ),
        ],
      ),
    );
  }
}
```

---

## 8. 마이그레이션 체크리스트

### ✅ 필수 변경사항
- [ ] API Base URL을 V2로 변경 (`/api` → `/api/v2`)
- [ ] 모든 엔드포인트 경로 업데이트
- [ ] PlannedExercise에 `order_index` 필드 추가
- [ ] startWorkout → startNewWorkout/continueWorkout 로직 분리
- [ ] endWorkout → completeWorkout 메서드명 변경

### ✅ 권장 변경사항
- [ ] 에러 핸들링 개선 (409 Conflict 처리 추가)
- [ ] 진행 중인 운동 세션 확인 로직 추가
- [ ] API 응답 캐싱 구현
- [ ] 오프라인 지원 추가

### ✅ 테스트
- [ ] 새 운동 시작 테스트
- [ ] 진행 중인 운동 이어하기 테스트
- [ ] 운동 완료 테스트
- [ ] 통계 API 테스트
- [ ] 에러 시나리오 테스트

---

## 9. 문제 해결

### 자주 발생하는 에러

#### 1. "진행 중인 운동 세션이 이미 있습니다" (409)
```dart
// 해결: continueWorkout 사용 또는 기존 세션 완료 후 새로 시작
if (error.code == 'WORKOUT001') {
  final shouldContinue = await showContinueDialog();
  if (shouldContinue) {
    await workoutService.continueWorkout();
  }
}
```

#### 2. "order_index is required" (400)
```dart
// 해결: PlannedExercise에 order_index 추가
exercises.asMap().entries.map((e) => PlannedExercise(
  // ... 다른 필드들
  orderIndex: e.key,  // 필수!
));
```

#### 3. Token 만료 (401)
```dart
// 해결: 리프레시 토큰으로 재인증
if (error.statusCode == 401) {
  await authService.refreshToken();
  // 요청 재시도
}
```

---

## 10. 성능 최적화

### API 호출 최적화
```dart
// 병렬 호출로 성능 개선
Future<void> loadDashboard() async {
  final results = await Future.wait([
    workoutService.getCurrentSession(),
    statsService.getWeeklyStats(),
    statsService.getPersonalRecords(),
  ]);

  // 결과 처리
}
```

### 캐싱 구현
```dart
class CachedApiClient {
  final _cache = <String, CacheEntry>{};

  Future<T> getCached<T>(
    String key,
    Future<T> Function() fetcher, {
    Duration? ttl,
  }) async {
    final cached = _cache[key];

    if (cached != null && !cached.isExpired) {
      return cached.data as T;
    }

    final data = await fetcher();
    _cache[key] = CacheEntry(data, ttl: ttl);

    return data;
  }
}
```

---

## 📚 참고 자료
- [Dio Package](https://pub.dev/packages/dio)
- [Riverpod](https://riverpod.dev/)
- [Flutter HTTP Best Practices](https://flutter.dev/docs/cookbook/networking)
- API 문서: `/API_DOCUMENTATION.md`