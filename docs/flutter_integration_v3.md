# Flutter Integration Guide v3.0 - 운동 순서 추적 시스템

## 버전 정보
- 작성일: 2025-01-16
- API 버전: 3.0
- 주요 변경사항: 운동 프로그램 순서 추적 기능 추가

## 1. 개요

### 1.1 주요 기능
- 사용자의 운동 프로그램 순서를 자동으로 추적
- 운동을 시작만 하고 완료하지 않아도 다음 순서로 진행
- PPL, Upper/Lower, Bro Split 등 다양한 프로그램 지원
- AI와 일반 추천 모두 순서 기반 추천
- 운동 세션 상태 관리 (PLANNED, IN_PROGRESS, COMPLETED, CANCELLED)

### 1.2 시스템 동작 방식
```
예시: 3일 PPL 프로그램 (Push → Pull → Legs)

요일과 무관한 순서 관리:
- 1일차: Push 운동 추천 → 월요일에 시작
- 2일차: Pull 운동 추천 → 수요일에 운동 (Push 완료 여부와 무관)
- 3일차: Legs 운동 추천 → 토요일에 운동
- 4일차: 다시 Push로 순환 (Cycle 2 시작) → 다음 주 화요일에 운동

핵심: 언제 운동하든 순서만 유지
- 매일 운동해도 OK
- 일주일에 1번만 해도 OK
- 순서는 항상 Push → Pull → Legs → Push...
```

## 2. API 엔드포인트

### 2.1 현재 진행 중인 운동 세션 조회
```dart
GET /api/workouts/current-session
```

**Response:**
```dart
// 진행 중인 운동이 있는 경우
{
  "success": true,
  "data": {
    "sessionId": 123,
    "date": "2025-01-16T10:30:00",
    "duration": 15,  // 현재까지 경과 시간 (분)
    "exercises": [
      {
        "exerciseId": 1,
        "exerciseName": "벤치프레스",
        "sets": [
          {
            "weight": 60.0,
            "reps": 10,
            "rpe": 7,
            "restTime": 90
          }
        ],
        "totalVolume": 600.0
      }
    ],
    "totalVolume": 600.0,
    "caloriesBurned": 75
  }
}

// 진행 중인 운동이 없는 경우
{
  "success": true,
  "data": null
}
```

### 2.2 프로그램 진행 상황 조회
```dart
GET /api/workouts/program-status
```

**Response:**
```dart
{
  "success": true,
  "data": {
    "current_day": 2,              // 현재 2일차
    "total_days": 3,                // 전체 3일 프로그램
    "current_cycle": 1,             // 첫 번째 사이클
    "next_workout_type": "PULL",    // 다음은 당기기 운동
    "next_workout_description": "3일 프로그램 중 2일차: 당기기 운동 (등/이두)",
    "program_type": "PPL",
    "last_workout_date": "2025-01-15T18:30:00",
    "is_new_cycle": false,
    "workout_history": [
      {
        "day_number": 1,
        "workout_type": "PUSH",
        "date": "2025-01-15T18:30:00",
        "status": "COMPLETED",
        "cycle_number": 1
      }
    ]
  }
}
```

### 2.3 Quick 운동 추천 (순서 기반)
```dart
GET /api/workouts/recommendations/quick
```

**Request Parameters:**
```dart
class QuickWorkoutRequest {
  int? duration;        // 운동 시간 (분), optional
  String? equipment;    // 장비 (dumbbell, barbell, bodyweight 등), optional
  String? targetMuscle; // 타겟 근육 (null이면 자동으로 순서 기반 추천)
}
```

**Response:**
```dart
class QuickWorkoutRecommendationResponse {
  WorkoutRecommendationDetail recommendation;
  List<AlternativeWorkout> alternatives;
}

class WorkoutRecommendationDetail {
  String workoutId;
  String name;              // "30분 가슴 덤벨 운동"
  int duration;
  String difficulty;
  List<QuickExerciseDetail> exercises;
  int estimatedCalories;
  List<String> targetMuscles;  // ["가슴", "삼두", "어깨"]
  List<String> equipment;
}
```

### 2.2 AI 운동 추천 (순서 기반)
```dart
GET /api/ai/recommendations/workout
```

**Request Parameters:**
```dart
class AIWorkoutRequest {
  int? duration;
  String? equipment;
  String? targetMuscle;  // null이면 자동으로 순서 기반 추천
  String? difficulty;
}
```

**Response:** QuickWorkoutRecommendationResponse와 동일

### 2.3 추천 운동 시작
```dart
POST /api/workouts/start-recommended
```

**Request Body:**
```dart
class StartRecommendedWorkoutRequest {
  String recommendationId;
  WorkoutAdjustments adjustments;
}

class WorkoutAdjustments {
  int? duration;
  List<String> skipExercises;
  Map<String, String> substituteExercises;
}
```

**Response:**
```dart
class StartRecommendedWorkoutResponse {
  String sessionId;
  String workoutName;
  String startTime;
  List<RecommendedWorkoutExercise> exercises;
  int estimatedDuration;
  bool started;
}
```

## 3. Flutter 구현 가이드

### 3.1 Service Layer 구현

```dart
// workout_service.dart
import 'package:dio/dio.dart';

class WorkoutService {
  final Dio _dio;

  WorkoutService(this._dio);

  // 현재 진행 중인 운동 세션 조회
  Future<WorkoutSession?> getCurrentSession() async {
    try {
      final response = await _dio.get('/api/workouts/current-session');

      if (response.data['data'] == null) {
        return null;
      }

      return WorkoutSession.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  // 프로그램 진행 상황 조회
  Future<ProgramStatus> getProgramStatus() async {
    try {
      final response = await _dio.get('/api/workouts/program-status');
      return ProgramStatus.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  // Quick 운동 추천 (자동 순서 추적)
  Future<QuickWorkoutRecommendation> getQuickRecommendation({
    int? duration,
    String? equipment,
    String? targetMuscle,  // null로 보내면 서버가 자동으로 순서 결정
  }) async {
    try {
      final response = await _dio.get(
        '/api/workouts/recommendations/quick',
        queryParameters: {
          if (duration != null) 'duration': duration,
          if (equipment != null) 'equipment': equipment,
          if (targetMuscle != null) 'targetMuscle': targetMuscle,
        },
      );

      return QuickWorkoutRecommendation.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  // AI 운동 추천 (자동 순서 추적)
  Future<QuickWorkoutRecommendation> getAIRecommendation({
    int? duration,
    String? equipment,
    String? targetMuscle,  // null로 보내면 서버가 자동으로 순서 결정
    String? difficulty,
  }) async {
    try {
      final response = await _dio.get(
        '/api/ai/recommendations/workout',
        queryParameters: {
          if (duration != null) 'duration': duration,
          if (equipment != null) 'equipment': equipment,
          if (targetMuscle != null) 'target_muscle': targetMuscle,
          if (difficulty != null) 'difficulty': difficulty,
        },
      );

      return QuickWorkoutRecommendation.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  // 추천 운동 시작
  Future<WorkoutSession> startRecommendedWorkout(
    String recommendationId, {
    WorkoutAdjustments? adjustments,
  }) async {
    try {
      final response = await _dio.post(
        '/api/workouts/start-recommended',
        data: {
          'recommendation_id': recommendationId,
          'adjustments': adjustments?.toJson() ?? {},
        },
      );

      return WorkoutSession.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }
}
```

### 3.2 State Management (Riverpod)

```dart
// workout_provider.dart
import 'package:flutter_riverpod/flutter_riverpod.dart';

// 현재 진행 중인 세션 상태
final currentSessionProvider = FutureProvider.autoDispose<WorkoutSession?>((ref) async {
  final service = ref.read(workoutServiceProvider);
  return await service.getCurrentSession();
});

// 현재 추천 상태
final currentRecommendationProvider = StateNotifierProvider<
  RecommendationNotifier, AsyncValue<QuickWorkoutRecommendation?>
>((ref) {
  return RecommendationNotifier(ref.read(workoutServiceProvider));
});

class RecommendationNotifier extends StateNotifier<AsyncValue<QuickWorkoutRecommendation?>> {
  final WorkoutService _service;

  RecommendationNotifier(this._service) : super(const AsyncValue.data(null));

  // 자동 추천 가져오기 (순서 기반)
  Future<void> fetchAutoRecommendation({
    int? duration,
    String? equipment,
  }) async {
    state = const AsyncValue.loading();

    try {
      // targetMuscle을 null로 보내서 서버가 순서 기반으로 결정하도록 함
      final recommendation = await _service.getQuickRecommendation(
        duration: duration,
        equipment: equipment,
        targetMuscle: null,  // 자동 순서 추적
      );

      state = AsyncValue.data(recommendation);
    } catch (e, s) {
      state = AsyncValue.error(e, s);
    }
  }

  // AI 추천 가져오기 (순서 기반)
  Future<void> fetchAIRecommendation({
    int? duration,
    String? equipment,
    String? difficulty,
  }) async {
    state = const AsyncValue.loading();

    try {
      final recommendation = await _service.getAIRecommendation(
        duration: duration,
        equipment: equipment,
        targetMuscle: null,  // 자동 순서 추적
        difficulty: difficulty,
      );

      state = AsyncValue.data(recommendation);
    } catch (e, s) {
      state = AsyncValue.error(e, s);
    }
  }

  // 수동으로 특정 근육 선택
  Future<void> fetchTargetedRecommendation({
    required String targetMuscle,
    int? duration,
    String? equipment,
  }) async {
    state = const AsyncValue.loading();

    try {
      final recommendation = await _service.getQuickRecommendation(
        duration: duration,
        equipment: equipment,
        targetMuscle: targetMuscle,  // 특정 근육 지정
      );

      state = AsyncValue.data(recommendation);
    } catch (e, s) {
      state = AsyncValue.error(e, s);
    }
  }
}

// 운동 세션 상태
final workoutSessionProvider = StateNotifierProvider<
  WorkoutSessionNotifier, AsyncValue<WorkoutSession?>
>((ref) {
  return WorkoutSessionNotifier(ref.read(workoutServiceProvider));
});

class WorkoutSessionNotifier extends StateNotifier<AsyncValue<WorkoutSession?>> {
  final WorkoutService _service;

  WorkoutSessionNotifier(this._service) : super(const AsyncValue.data(null));

  Future<void> startRecommendedWorkout(String recommendationId) async {
    state = const AsyncValue.loading();

    try {
      final session = await _service.startRecommendedWorkout(recommendationId);
      state = AsyncValue.data(session);
    } catch (e, s) {
      state = AsyncValue.error(e, s);
    }
  }
}
```

### 3.3 UI 구현

```dart
// home_screen.dart - 메인 화면에서 진행 중인 운동 확인
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final currentSessionAsync = ref.watch(currentSessionProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('LiftUp AI')),
      body: currentSessionAsync.when(
        data: (session) {
          if (session != null) {
            // 진행 중인 운동이 있는 경우
            return Center(
              child: Card(
                margin: const EdgeInsets.all(16),
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(
                        Icons.fitness_center,
                        size: 48,
                        color: Colors.orange,
                      ),
                      const SizedBox(height: 16),
                      Text(
                        '운동 진행 중',
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        '시작 시간: ${_formatTime(session.date)}',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                      Text(
                        '경과 시간: ${session.duration ?? 0}분',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                      Text(
                        '완료한 운동: ${session.exercises.length}개',
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                      const SizedBox(height: 24),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                        children: [
                          ElevatedButton.icon(
                            onPressed: () {
                              Navigator.pushNamed(
                                context,
                                '/workout-session',
                                arguments: session.sessionId,
                              );
                            },
                            icon: const Icon(Icons.play_arrow),
                            label: const Text('이어하기'),
                            style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.green,
                            ),
                          ),
                          OutlinedButton.icon(
                            onPressed: () => _endWorkout(context, ref, session.sessionId),
                            icon: const Icon(Icons.stop),
                            label: const Text('종료하기'),
                            style: OutlinedButton.styleFrom(
                              foregroundColor: Colors.red,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            );
          } else {
            // 진행 중인 운동이 없는 경우
            return Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(
                    Icons.fitness_center,
                    size: 64,
                    color: Colors.grey,
                  ),
                  const SizedBox(height: 16),
                  Text(
                    '진행 중인 운동이 없습니다',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 24),
                  ElevatedButton.icon(
                    onPressed: () {
                      Navigator.pushNamed(context, '/recommendations');
                    },
                    icon: const Icon(Icons.add),
                    label: const Text('새 운동 시작'),
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 32,
                        vertical: 16,
                      ),
                    ),
                  ),
                ],
              ),
            );
          }
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, stack) => Center(
          child: Text('오류: $error'),
        ),
      ),
    );
  }

  String _formatTime(String dateString) {
    final date = DateTime.parse(dateString);
    return '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
  }

  void _endWorkout(BuildContext context, WidgetRef ref, int sessionId) {
    // 운동 종료 로직
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('운동 종료'),
        content: const Text('운동을 종료하시겠습니까?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('취소'),
          ),
          ElevatedButton(
            onPressed: () async {
              // 운동 종료 API 호출
              Navigator.pop(context);
              ref.invalidate(currentSessionProvider);
            },
            child: const Text('종료'),
          ),
        ],
      ),
    );
  }
}
```

```dart
// recommendation_screen.dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class RecommendationScreen extends ConsumerStatefulWidget {
  const RecommendationScreen({Key? key}) : super(key: key);

  @override
  ConsumerState<RecommendationScreen> createState() => _RecommendationScreenState();
}

class _RecommendationScreenState extends ConsumerState<RecommendationScreen> {
  bool _useAI = false;
  int? _selectedDuration;
  String? _selectedEquipment;

  @override
  void initState() {
    super.initState();
    // 초기 로드 시 자동 추천 가져오기
    _fetchRecommendation();
  }

  void _fetchRecommendation() {
    if (_useAI) {
      ref.read(currentRecommendationProvider.notifier).fetchAIRecommendation(
        duration: _selectedDuration,
        equipment: _selectedEquipment,
      );
    } else {
      ref.read(currentRecommendationProvider.notifier).fetchAutoRecommendation(
        duration: _selectedDuration,
        equipment: _selectedEquipment,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final recommendationAsync = ref.watch(currentRecommendationProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('오늘의 운동'),
        actions: [
          // AI/일반 추천 토글
          Switch(
            value: _useAI,
            onChanged: (value) {
              setState(() {
                _useAI = value;
              });
              _fetchRecommendation();
            },
          ),
          const SizedBox(width: 8),
          Text(_useAI ? 'AI' : '일반'),
          const SizedBox(width: 16),
        ],
      ),
      body: Column(
        children: [
          // 필터 옵션
          Container(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                // 운동 시간 선택
                Expanded(
                  child: DropdownButtonFormField<int>(
                    decoration: const InputDecoration(
                      labelText: '운동 시간',
                      border: OutlineInputBorder(),
                    ),
                    value: _selectedDuration,
                    items: [20, 30, 45, 60].map((duration) {
                      return DropdownMenuItem(
                        value: duration,
                        child: Text('$duration분'),
                      );
                    }).toList(),
                    onChanged: (value) {
                      setState(() {
                        _selectedDuration = value;
                      });
                      _fetchRecommendation();
                    },
                  ),
                ),
                const SizedBox(width: 16),
                // 장비 선택
                Expanded(
                  child: DropdownButtonFormField<String>(
                    decoration: const InputDecoration(
                      labelText: '장비',
                      border: OutlineInputBorder(),
                    ),
                    value: _selectedEquipment,
                    items: ['dumbbell', 'barbell', 'bodyweight', 'machine']
                        .map((equipment) {
                      return DropdownMenuItem(
                        value: equipment,
                        child: Text(_getEquipmentName(equipment)),
                      );
                    }).toList(),
                    onChanged: (value) {
                      setState(() {
                        _selectedEquipment = value;
                      });
                      _fetchRecommendation();
                    },
                  ),
                ),
              ],
            ),
          ),

          // 추천 내용
          Expanded(
            child: recommendationAsync.when(
              data: (recommendation) {
                if (recommendation == null) {
                  return const Center(
                    child: Text('추천을 불러오는 중...'),
                  );
                }

                return SingleChildScrollView(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // 운동 정보 카드
                      Card(
                        elevation: 4,
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                recommendation.recommendation.name,
                                style: Theme.of(context).textTheme.headlineSmall,
                              ),
                              const SizedBox(height: 8),
                              Row(
                                children: [
                                  Icon(Icons.timer, size: 16, color: Colors.grey[600]),
                                  const SizedBox(width: 4),
                                  Text('${recommendation.recommendation.duration}분'),
                                  const SizedBox(width: 16),
                                  Icon(Icons.local_fire_department,
                                    size: 16, color: Colors.orange[600]),
                                  const SizedBox(width: 4),
                                  Text('${recommendation.recommendation.estimatedCalories} kcal'),
                                ],
                              ),
                              const SizedBox(height: 8),
                              Wrap(
                                spacing: 8,
                                children: recommendation.recommendation.targetMuscles
                                    .map((muscle) => Chip(
                                      label: Text(muscle),
                                      backgroundColor: Theme.of(context).primaryColor.withOpacity(0.1),
                                    ))
                                    .toList(),
                              ),
                            ],
                          ),
                        ),
                      ),

                      const SizedBox(height: 16),

                      // 운동 리스트
                      Text(
                        '운동 목록',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      const SizedBox(height: 8),
                      ...recommendation.recommendation.exercises.map((exercise) {
                        return Card(
                          margin: const EdgeInsets.only(bottom: 8),
                          child: ListTile(
                            leading: CircleAvatar(
                              child: Text('${exercise.order}'),
                            ),
                            title: Text(exercise.name),
                            subtitle: Text(
                              '${exercise.sets}세트 x ${exercise.reps}회 | 휴식 ${exercise.rest}초',
                            ),
                          ),
                        );
                      }).toList(),

                      const SizedBox(height: 16),

                      // 대체 운동 옵션
                      if (recommendation.alternatives.isNotEmpty) ...[
                        Text(
                          '다른 옵션',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const SizedBox(height: 8),
                        ...recommendation.alternatives.map((alt) {
                          return Card(
                            margin: const EdgeInsets.only(bottom: 8),
                            child: ListTile(
                              title: Text(alt.name),
                              subtitle: Text('${alt.duration}분'),
                              trailing: TextButton(
                                onPressed: () {
                                  // 대체 운동 선택 로직
                                },
                                child: const Text('선택'),
                              ),
                            ),
                          );
                        }).toList(),
                      ],

                      const SizedBox(height: 24),

                      // 시작 버튼
                      SizedBox(
                        width: double.infinity,
                        height: 56,
                        child: ElevatedButton(
                          onPressed: () {
                            _startWorkout(recommendation.recommendation.workoutId);
                          },
                          style: ElevatedButton.styleFrom(
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(12),
                            ),
                          ),
                          child: const Text(
                            '운동 시작하기',
                            style: TextStyle(fontSize: 18),
                          ),
                        ),
                      ),
                    ],
                  ),
                );
              },
              loading: () => const Center(
                child: CircularProgressIndicator(),
              ),
              error: (error, stack) => Center(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    const Icon(Icons.error_outline, size: 48, color: Colors.red),
                    const SizedBox(height: 16),
                    Text('오류: $error'),
                    const SizedBox(height: 16),
                    ElevatedButton(
                      onPressed: _fetchRecommendation,
                      child: const Text('다시 시도'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _getEquipmentName(String equipment) {
    switch (equipment) {
      case 'dumbbell':
        return '덤벨';
      case 'barbell':
        return '바벨';
      case 'bodyweight':
        return '맨몸';
      case 'machine':
        return '머신';
      default:
        return equipment;
    }
  }

  void _startWorkout(String workoutId) async {
    try {
      await ref.read(workoutSessionProvider.notifier)
          .startRecommendedWorkout(workoutId);

      if (!mounted) return;

      // 운동 화면으로 이동
      Navigator.pushReplacementNamed(
        context,
        '/workout-session',
      );
    } catch (e) {
      if (!mounted) return;

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('운동 시작 실패: $e'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }
}
```

### 3.4 Model 클래스

```dart
// models/workout_models.dart
import 'package:freezed_annotation/freezed_annotation.dart';

part 'workout_models.freezed.dart';
part 'workout_models.g.dart';

@freezed
class QuickWorkoutRecommendation with _$QuickWorkoutRecommendation {
  const factory QuickWorkoutRecommendation({
    required WorkoutRecommendationDetail recommendation,
    required List<AlternativeWorkout> alternatives,
  }) = _QuickWorkoutRecommendation;

  factory QuickWorkoutRecommendation.fromJson(Map<String, dynamic> json) =>
      _$QuickWorkoutRecommendationFromJson(json);
}

@freezed
class WorkoutRecommendationDetail with _$WorkoutRecommendationDetail {
  const factory WorkoutRecommendationDetail({
    @JsonKey(name: 'workout_id') required String workoutId,
    required String name,
    required int duration,
    required String difficulty,
    required List<QuickExerciseDetail> exercises,
    @JsonKey(name: 'estimated_calories') required int estimatedCalories,
    @JsonKey(name: 'target_muscles') required List<String> targetMuscles,
    required List<String> equipment,
  }) = _WorkoutRecommendationDetail;

  factory WorkoutRecommendationDetail.fromJson(Map<String, dynamic> json) =>
      _$WorkoutRecommendationDetailFromJson(json);
}

@freezed
class QuickExerciseDetail with _$QuickExerciseDetail {
  const factory QuickExerciseDetail({
    @JsonKey(name: 'exercise_id') required String exerciseId,
    required String name,
    required int sets,
    required String reps,
    required int rest,
    required int order,
  }) = _QuickExerciseDetail;

  factory QuickExerciseDetail.fromJson(Map<String, dynamic> json) =>
      _$QuickExerciseDetailFromJson(json);
}

@freezed
class AlternativeWorkout with _$AlternativeWorkout {
  const factory AlternativeWorkout({
    @JsonKey(name: 'workout_id') required String workoutId,
    required String name,
    required int duration,
  }) = _AlternativeWorkout;

  factory AlternativeWorkout.fromJson(Map<String, dynamic> json) =>
      _$AlternativeWorkoutFromJson(json);
}

@freezed
class WorkoutSession with _$WorkoutSession {
  const factory WorkoutSession({
    @JsonKey(name: 'session_id') required String sessionId,
    @JsonKey(name: 'workout_name') required String workoutName,
    @JsonKey(name: 'start_time') required String startTime,
    required List<RecommendedWorkoutExercise> exercises,
    @JsonKey(name: 'estimated_duration') required int estimatedDuration,
    required bool started,
  }) = _WorkoutSession;

  factory WorkoutSession.fromJson(Map<String, dynamic> json) =>
      _$WorkoutSessionFromJson(json);
}

@freezed
class RecommendedWorkoutExercise with _$RecommendedWorkoutExercise {
  const factory RecommendedWorkoutExercise({
    @JsonKey(name: 'exercise_id') required String exerciseId,
    required String name,
    @JsonKey(name: 'planned_sets') required int plannedSets,
    @JsonKey(name: 'planned_reps') required String plannedReps,
    @JsonKey(name: 'suggested_weight') required double suggestedWeight,
    @JsonKey(name: 'rest_timer') required int restTimer,
  }) = _RecommendedWorkoutExercise;

  factory RecommendedWorkoutExercise.fromJson(Map<String, dynamic> json) =>
      _$RecommendedWorkoutExerciseFromJson(json);
}

@freezed
class WorkoutAdjustments with _$WorkoutAdjustments {
  const factory WorkoutAdjustments({
    int? duration,
    @JsonKey(name: 'skip_exercises') @Default([]) List<String> skipExercises,
    @JsonKey(name: 'substitute_exercises') @Default({}) Map<String, String> substituteExercises,
  }) = _WorkoutAdjustments;

  factory WorkoutAdjustments.fromJson(Map<String, dynamic> json) =>
      _$WorkoutAdjustmentsFromJson(json);
}

@freezed
class ProgramStatus with _$ProgramStatus {
  const factory ProgramStatus({
    @JsonKey(name: 'current_day') required int currentDay,
    @JsonKey(name: 'total_days') required int totalDays,
    @JsonKey(name: 'current_cycle') required int currentCycle,
    @JsonKey(name: 'next_workout_type') required String nextWorkoutType,
    @JsonKey(name: 'next_workout_description') required String nextWorkoutDescription,
    @JsonKey(name: 'program_type') required String programType,
    @JsonKey(name: 'last_workout_date') String? lastWorkoutDate,
    @JsonKey(name: 'is_new_cycle') required bool isNewCycle,
    @JsonKey(name: 'workout_history') required List<WorkoutHistoryItem> workoutHistory,
  }) = _ProgramStatus;

  factory ProgramStatus.fromJson(Map<String, dynamic> json) =>
      _$ProgramStatusFromJson(json);
}

@freezed
class WorkoutHistoryItem with _$WorkoutHistoryItem {
  const factory WorkoutHistoryItem({
    @JsonKey(name: 'day_number') required int dayNumber,
    @JsonKey(name: 'workout_type') required String workoutType,
    required String date,
    required String status,
    @JsonKey(name: 'cycle_number') required int cycleNumber,
  }) = _WorkoutHistoryItem;

  factory WorkoutHistoryItem.fromJson(Map<String, dynamic> json) =>
      _$WorkoutHistoryItemFromJson(json);
}
```

## 4. 프로그램 상태 표시 UI

```dart
// program_status_widget.dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final programStatusProvider = FutureProvider<ProgramStatus>((ref) async {
  final service = ref.read(workoutServiceProvider);
  return await service.getProgramStatus();
});

class ProgramStatusWidget extends ConsumerWidget {
  const ProgramStatusWidget({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final programStatusAsync = ref.watch(programStatusProvider);

    return programStatusAsync.when(
      data: (status) => Card(
        margin: const EdgeInsets.all(16),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '운동 프로그램 진행 상황',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 16),

              // 프로그레스 인디케이터
              LinearProgressIndicator(
                value: status.currentDay / status.totalDays,
                backgroundColor: Colors.grey[300],
                valueColor: AlwaysStoppedAnimation<Color>(
                  Theme.of(context).primaryColor,
                ),
              ),
              const SizedBox(height: 8),

              Text(
                '${status.programType} - 사이클 ${status.currentCycle}',
                style: Theme.of(context).textTheme.bodyMedium,
              ),

              const SizedBox(height: 16),

              // 다음 운동 정보
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Theme.of(context).primaryColor.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.arrow_forward, size: 32),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '다음 운동',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                          Text(
                            status.nextWorkoutDescription,
                            style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 16),

              // 최근 운동 기록
              if (status.workoutHistory.isNotEmpty) ...[
                Text(
                  '최근 운동 기록',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 8),
                ...status.workoutHistory.take(3).map((history) {
                  return ListTile(
                    leading: CircleAvatar(
                      child: Text('${history.dayNumber}'),
                      backgroundColor: history.status == 'COMPLETED'
                        ? Colors.green
                        : Colors.grey,
                    ),
                    title: Text(_getWorkoutTypeName(history.workoutType)),
                    subtitle: Text(_formatDate(history.date)),
                    trailing: Text(
                      '사이클 ${history.cycleNumber}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  );
                }).toList(),
              ],

              // 마지막 운동 날짜
              if (status.lastWorkoutDate != null) ...[
                const SizedBox(height: 8),
                Text(
                  '마지막 운동: ${_getTimeSince(status.lastWorkoutDate!)}',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Colors.grey[600],
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (error, stack) => Center(child: Text('오류: $error')),
    );
  }

  String _getWorkoutTypeName(String type) {
    switch (type) {
      case 'PUSH': return '밀기 운동';
      case 'PULL': return '당기기 운동';
      case 'LEGS': return '하체 운동';
      case 'UPPER': return '상체 운동';
      case 'LOWER': return '하체 운동';
      case 'CHEST': return '가슴 운동';
      case 'BACK': return '등 운동';
      case 'SHOULDERS': return '어깨 운동';
      case 'ARMS': return '팔 운동';
      default: return '운동';
    }
  }

  String _formatDate(String dateString) {
    final date = DateTime.parse(dateString);
    return '${date.month}/${date.day} ${date.hour}:${date.minute.toString().padLeft(2, '0')}';
  }

  String _getTimeSince(String dateString) {
    final date = DateTime.parse(dateString);
    final now = DateTime.now();
    final difference = now.difference(date);

    if (difference.inDays > 0) {
      return '${difference.inDays}일 전';
    } else if (difference.inHours > 0) {
      return '${difference.inHours}시간 전';
    } else {
      return '${difference.inMinutes}분 전';
    }
  }
}
```

## 5. 주요 시나리오

### 5.1 자동 순서 추천
```dart
// 사용자가 앱을 열었을 때
// targetMuscle을 null로 보내면 서버가 자동으로 순서 결정
final recommendation = await workoutService.getQuickRecommendation(
  targetMuscle: null,  // 자동 순서 추적
);

// 서버 응답 예시 (PPL 프로그램 2일차)
{
  "recommendation": {
    "workout_id": "quick_30min_back_general",
    "name": "30분 등 운동",
    "target_muscles": ["등", "이두"],
    // ...
  }
}
```

### 4.2 수동 근육 선택
```dart
// 사용자가 특정 근육을 선택했을 때
final recommendation = await workoutService.getQuickRecommendation(
  targetMuscle: "legs",  // 하체 운동 강제 선택
);
```

### 4.3 운동 시작과 진행
```dart
// 1. 추천받은 운동 시작
final session = await workoutService.startRecommendedWorkout(
  recommendationId: "quick_30min_back_general"
);

// 2. 운동 중 일부만 완료하고 종료
await workoutService.endWorkout(
  sessionId: session.sessionId,
  completed: false,  // 미완료 표시
);

// 3. 다음 날 추천 요청 시 자동으로 다음 순서 (Legs) 추천됨
final nextRecommendation = await workoutService.getQuickRecommendation();
// → 하체 운동 추천
```

## 5. 에러 처리

```dart
class WorkoutErrorHandler {
  static String getErrorMessage(dynamic error) {
    if (error is DioError) {
      switch (error.response?.statusCode) {
        case 400:
          if (error.response?.data['code'] == 'WORKOUT001') {
            return '이미 진행 중인 운동이 있습니다.';
          }
          break;
        case 404:
          return '운동 정보를 찾을 수 없습니다.';
        case 500:
          return '서버 오류가 발생했습니다.';
      }
    }
    return '알 수 없는 오류가 발생했습니다.';
  }
}
```

## 6. 테스트 가이드

```dart
// test/workout_service_test.dart
import 'package:flutter_test/flutter_test.dart';
import 'package:mockito/mockito.dart';

void main() {
  group('WorkoutService Tests', () {
    late MockDio mockDio;
    late WorkoutService service;

    setUp(() {
      mockDio = MockDio();
      service = WorkoutService(mockDio);
    });

    test('자동 추천 테스트', () async {
      // Given
      when(mockDio.get('/api/workouts/recommendations/quick',
        queryParameters: anyNamed('queryParameters')))
        .thenAnswer((_) async => Response(
          data: {
            'success': true,
            'data': mockRecommendationData,
          },
          statusCode: 200,
        ));

      // When
      final result = await service.getQuickRecommendation();

      // Then
      expect(result.recommendation.name, contains('운동'));
      verify(mockDio.get(
        '/api/workouts/recommendations/quick',
        queryParameters: argThat(
          isA<Map>().having((m) => m['targetMuscle'], 'targetMuscle', isNull),
        ),
      )).called(1);
    });
  });
}
```

## 7. 마이그레이션 가이드

### 7.1 기존 코드에서 업그레이드
```dart
// 기존 코드 (v2.x)
final recommendation = await workoutService.getTodayRecommendation();

// 새 코드 (v3.0)
final recommendation = await workoutService.getQuickRecommendation(
  targetMuscle: null,  // 자동 순서 추적
);
```

### 7.2 데이터베이스 마이그레이션
- 서버측에서 자동으로 처리됨
- 클라이언트 변경사항 없음

## 8. 운동 상태 관리

### 8.1 세션 상태 (SessionStatus)
```dart
enum SessionStatus {
  PLANNED,      // 계획됨 (아직 시작 안함)
  IN_PROGRESS,  // 진행 중
  COMPLETED,    // 완료됨
  CANCELLED     // 취소됨
}
```

### 8.2 상태 전환 플로우
```
1. 운동 시작 → IN_PROGRESS
2. 운동 완료 → COMPLETED
3. 운동 취소 → CANCELLED
4. 새 운동 시작 시 기존 IN_PROGRESS → CANCELLED (자동)
```

### 8.3 진행 중 운동 처리
```dart
// 앱 시작 시 진행 중인 운동 확인
void initState() {
  super.initState();
  _checkActiveSession();
}

Future<void> _checkActiveSession() async {
  final session = await workoutService.getCurrentSession();
  if (session != null) {
    // 진행 중인 운동이 있으면 이어하기 또는 종료 선택
    _showContinueOrEndDialog(session);
  } else {
    // 새 운동 추천 표시
    _loadRecommendations();
  }
}
```

## 9. FAQ

**Q: 운동을 건너뛰고 싶을 때는?**
A: targetMuscle 파라미터에 원하는 근육을 지정하면 순서를 무시하고 해당 운동 추천

**Q: 프로그램 순서를 리셋하려면?**
A: 서버에서 자동 관리되며, 새로운 프로그램 시작 시 자동 리셋

**Q: AI 추천과 일반 추천의 차이점?**
A: 둘 다 순서 추적을 지원하며, AI는 더 개인화된 운동 선택 제공

**Q: 진행 중인 운동이 있는데 새 운동을 시작하면?**
A: 기존 IN_PROGRESS 세션은 자동으로 CANCELLED 상태로 변경되고 새 세션이 시작됨

**Q: 운동 상태는 어떻게 관리되나요?**
A: 서버에서 SessionStatus enum으로 관리하며, /api/workouts/current-session으로 확인 가능

## 9. 변경 이력

- v3.0.0 (2025-01-16)
  - 운동 프로그램 순서 자동 추적 기능 추가
  - 미완료 운동 처리 로직 개선
  - AI/일반 추천 통합 응답 포맷

- v2.0.0 (이전 버전)
  - 기본 운동 추천 기능
  - 수동 운동 선택