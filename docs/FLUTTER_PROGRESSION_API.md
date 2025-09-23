# Flutter 진급 시스템 API 명세서

## 🚀 진급 시스템 API 엔드포인트

### Base URL
```
http://localhost:8080  (개발)
https://api.liftupai.com  (프로덕션)
```

### 인증
```
Authorization: Bearer {token}
```

---

## 1. 진급 분석 API

### GET /api/v2/progression/analysis
프로그램 진급 분석 데이터를 조회합니다.

**Response:**
```json
{
  "success": true,
  "data": {
    "current_program": "UPPER_LOWER",
    "current_days_per_week": 4,
    "completed_cycles": 2,
    "current_cycle": 3,
    "recommendation": {
      "new_program": "PUSH_PULL_LEGS",
      "new_days_per_week": 6,
      "reason": "일관성과 볼륨 진행이 우수합니다",
      "expected_benefits": [
        "더 높은 빈도로 각 근육군 자극",
        "향상된 회복 관리",
        "더 세분화된 볼륨 분배"
      ]
    },
    "performance_metrics": {
      "volume_increase_percent": 25,
      "strength_gain_percent": 15,
      "average_workout_duration": 65,
      "total_workouts": 48
    },
    "ready_for_progression": true,
    "consistency_rate": 92.5,
    "recovery_status": "GOOD"
  }
}
```

---

## 2. 볼륨 최적화 API

### GET /api/v2/progression/volume/optimization
현재 볼륨 분석 및 최적화 추천을 받습니다.

**Response:**
```json
{
  "success": true,
  "data": {
    "current_volume": {
      "weekly_volume": 15000.0,
      "sets_per_week": 45,
      "reps_per_week": 420
    },
    "recommended_volume": {
      "weekly_volume": 18000.0,
      "sets_per_week": 52,
      "reps_per_week": 480
    },
    "adjustment_reason": "현재 회복 상태가 양호하고 진행률이 우수하여 볼륨 증가 가능",
    "muscle_group_volumes": {
      "chest": 12,
      "back": 15,
      "shoulders": 9,
      "legs": 16
    },
    "mev_reached": true,
    "mav_exceeded": false
  }
}
```

---

## 3. 회복 분석 API

### GET /api/v2/progression/recovery
근육군별 회복 상태를 분석합니다.

**Response:**
```json
{
  "success": true,
  "data": {
    "muscle_groups": {
      "chest": {
        "muscle_name": "chest",
        "last_workout": "2025-01-18T10:30:00",
        "hours_since_workout": 48,
        "recovery_percentage": 95,
        "ready_for_next_session": true,
        "recommended_rest_hours": 0
      },
      "legs": {
        "muscle_name": "legs",
        "last_workout": "2025-01-19T14:00:00",
        "hours_since_workout": 24,
        "recovery_percentage": 60,
        "ready_for_next_session": false,
        "recommended_rest_hours": 24
      }
    },
    "overall_recovery_score": 75,
    "needs_deload_week": false,
    "deload_reason": null,
    "next_recommended_muscles": ["chest", "shoulders", "triceps"]
  }
}
```

---

## 4. 프로그램 전환 체크 API

### GET /api/v2/progression/transition/check
프로그램 전환이 필요한지 확인합니다.

**Response:**
```json
{
  "success": true,
  "data": {
    "should_transition": true,
    "current_program_weeks": 12,
    "plateau_detected": false,
    "reason": "현재 프로그램을 3사이클 완료했고 진행률이 우수합니다",
    "suggested_programs": [
      {
        "program_name": "PUSH_PULL_LEGS",
        "days_per_week": 6,
        "description": "푸시/풀/레그 분할 프로그램",
        "benefits": [
          "높은 빈도로 각 근육군 자극",
          "더 나은 회복 관리"
        ],
        "difficulty": "INTERMEDIATE"
      }
    ],
    "goal_completion_rate": 85
  }
}
```

---

## 5. 진급 요약 API

### GET /api/v2/progression/summary
진급 상태 요약 정보를 조회합니다.

**Response:**
```json
{
  "success": true,
  "data": {
    "current_level": "UPPER_LOWER - 주 4회",
    "next_milestone": "PUSH_PULL_LEGS",
    "progress_percentage": 66,
    "days_until_progression": 28,
    "recent_achievements": [
      {
        "type": "VOLUME_RECORD",
        "description": "주간 볼륨 신기록 달성",
        "achieved_at": "2025-01-15T10:00:00",
        "value": "18000kg"
      }
    ]
  }
}
```

---

## 6. 진급 적용 API

### POST /api/v2/progression/apply-recommendation
추천된 진급 사항을 적용합니다.

**Request:**
```json
{
  "new_program": "PUSH_PULL_LEGS",
  "new_days_per_week": 6
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "success": true,
    "message": "프로그램이 PUSH_PULL_LEGS(주 6회)로 업그레이드되었습니다",
    "new_program": "PUSH_PULL_LEGS",
    "new_days_per_week": 6
  }
}
```

---

## Flutter 구현 예시

### 1. Data Models

```dart
// lib/models/progression_models.dart

class ProgramProgressionAnalysis {
  final String currentProgram;
  final int currentDaysPerWeek;
  final int completedCycles;
  final int currentCycle;
  final ProgressionRecommendation? recommendation;
  final PerformanceMetrics performanceMetrics;
  final bool readyForProgression;
  final double consistencyRate;
  final String recoveryStatus;

  ProgramProgressionAnalysis({
    required this.currentProgram,
    required this.currentDaysPerWeek,
    required this.completedCycles,
    this.currentCycle = 1,
    this.recommendation,
    required this.performanceMetrics,
    required this.readyForProgression,
    this.consistencyRate = 0.0,
    this.recoveryStatus = 'MODERATE',
  });

  factory ProgramProgressionAnalysis.fromJson(Map<String, dynamic> json) {
    return ProgramProgressionAnalysis(
      currentProgram: json['current_program'],
      currentDaysPerWeek: json['current_days_per_week'],
      completedCycles: json['completed_cycles'],
      currentCycle: json['current_cycle'] ?? 1,
      recommendation: json['recommendation'] != null
          ? ProgressionRecommendation.fromJson(json['recommendation'])
          : null,
      performanceMetrics: PerformanceMetrics.fromJson(json['performance_metrics']),
      readyForProgression: json['ready_for_progression'],
      consistencyRate: json['consistency_rate']?.toDouble() ?? 0.0,
      recoveryStatus: json['recovery_status'] ?? 'MODERATE',
    );
  }
}

class ProgressionRecommendation {
  final String newProgram;
  final int newDaysPerWeek;
  final String reason;
  final List<String> expectedBenefits;

  ProgressionRecommendation({
    required this.newProgram,
    required this.newDaysPerWeek,
    required this.reason,
    required this.expectedBenefits,
  });

  factory ProgressionRecommendation.fromJson(Map<String, dynamic> json) {
    return ProgressionRecommendation(
      newProgram: json['new_program'],
      newDaysPerWeek: json['new_days_per_week'],
      reason: json['reason'],
      expectedBenefits: List<String>.from(json['expected_benefits']),
    );
  }
}

class VolumeOptimizationRecommendation {
  final VolumeMetrics currentVolume;
  final VolumeMetrics recommendedVolume;
  final String adjustmentReason;
  final Map<String, int> muscleGroupVolumes;
  final bool mevReached;
  final bool mavExceeded;

  VolumeOptimizationRecommendation({
    required this.currentVolume,
    required this.recommendedVolume,
    required this.adjustmentReason,
    required this.muscleGroupVolumes,
    this.mevReached = false,
    this.mavExceeded = false,
  });

  factory VolumeOptimizationRecommendation.fromJson(Map<String, dynamic> json) {
    return VolumeOptimizationRecommendation(
      currentVolume: VolumeMetrics.fromJson(json['current_volume']),
      recommendedVolume: VolumeMetrics.fromJson(json['recommended_volume']),
      adjustmentReason: json['adjustment_reason'],
      muscleGroupVolumes: Map<String, int>.from(json['muscle_group_volumes']),
      mevReached: json['mev_reached'] ?? false,
      mavExceeded: json['mav_exceeded'] ?? false,
    );
  }
}

class RecoveryAnalysis {
  final Map<String, MuscleRecoveryStatus> muscleGroups;
  final int overallRecoveryScore;
  final bool needsDeloadWeek;
  final String? deloadReason;
  final List<String> nextRecommendedMuscles;

  RecoveryAnalysis({
    required this.muscleGroups,
    required this.overallRecoveryScore,
    required this.needsDeloadWeek,
    this.deloadReason,
    required this.nextRecommendedMuscles,
  });

  factory RecoveryAnalysis.fromJson(Map<String, dynamic> json) {
    final muscleGroupsMap = <String, MuscleRecoveryStatus>{};
    (json['muscle_groups'] as Map<String, dynamic>).forEach((key, value) {
      muscleGroupsMap[key] = MuscleRecoveryStatus.fromJson(value);
    });

    return RecoveryAnalysis(
      muscleGroups: muscleGroupsMap,
      overallRecoveryScore: json['overall_recovery_score'],
      needsDeloadWeek: json['needs_deload_week'],
      deloadReason: json['deload_reason'],
      nextRecommendedMuscles: List<String>.from(json['next_recommended_muscles']),
    );
  }
}

class ProgressionSummary {
  final String currentLevel;
  final String nextMilestone;
  final int progressPercentage;
  final int? daysUntilProgression;
  final List<ProgressionAchievement> recentAchievements;

  ProgressionSummary({
    required this.currentLevel,
    required this.nextMilestone,
    required this.progressPercentage,
    this.daysUntilProgression,
    required this.recentAchievements,
  });

  factory ProgressionSummary.fromJson(Map<String, dynamic> json) {
    return ProgressionSummary(
      currentLevel: json['current_level'],
      nextMilestone: json['next_milestone'],
      progressPercentage: json['progress_percentage'],
      daysUntilProgression: json['days_until_progression'],
      recentAchievements: (json['recent_achievements'] as List)
          .map((e) => ProgressionAchievement.fromJson(e))
          .toList(),
    );
  }
}
```

### 2. API Service

```dart
// lib/services/progression_service.dart

import 'package:dio/dio.dart';
import '../models/progression_models.dart';

class ProgressionService {
  static const String baseUrl = 'http://localhost:8080';
  final Dio dio;

  ProgressionService() : dio = Dio() {
    dio.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) {
        final token = getStoredToken(); // 토큰 가져오기 로직
        if (token != null) {
          options.headers['Authorization'] = 'Bearer $token';
        }
        handler.next(options);
      },
    ));
  }

  /// 진급 분석 조회
  Future<ProgramProgressionAnalysis> getProgressionAnalysis() async {
    try {
      final response = await dio.get('$baseUrl/api/v2/progression/analysis');
      return ProgramProgressionAnalysis.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  /// 볼륨 최적화 추천 조회
  Future<VolumeOptimizationRecommendation> getVolumeOptimization() async {
    try {
      final response = await dio.get('$baseUrl/api/v2/progression/volume/optimization');
      return VolumeOptimizationRecommendation.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  /// 회복 분석 조회
  Future<RecoveryAnalysis> getRecoveryAnalysis() async {
    try {
      final response = await dio.get('$baseUrl/api/v2/progression/recovery');
      return RecoveryAnalysis.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  /// 프로그램 전환 체크
  Future<ProgramTransitionRecommendation> checkProgramTransition() async {
    try {
      final response = await dio.get('$baseUrl/api/v2/progression/transition/check');
      return ProgramTransitionRecommendation.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  /// 진급 요약 조회
  Future<ProgressionSummary> getProgressionSummary() async {
    try {
      final response = await dio.get('$baseUrl/api/v2/progression/summary');
      return ProgressionSummary.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  /// 진급 추천 적용
  Future<ApplyProgressionResponse> applyProgression({
    required String newProgram,
    required int newDaysPerWeek,
  }) async {
    try {
      final response = await dio.post(
        '$baseUrl/api/v2/progression/apply-recommendation',
        data: {
          'new_program': newProgram,
          'new_days_per_week': newDaysPerWeek,
        },
      );
      return ApplyProgressionResponse.fromJson(response.data['data']);
    } catch (e) {
      throw _handleError(e);
    }
  }

  Exception _handleError(dynamic error) {
    if (error is DioException) {
      switch (error.response?.statusCode) {
        case 401:
          return Exception('인증이 필요합니다');
        case 404:
          return Exception('사용자 정보를 찾을 수 없습니다');
        case 500:
          return Exception('서버 오류가 발생했습니다');
        default:
          return Exception('요청 처리 중 오류가 발생했습니다');
      }
    }
    return Exception('알 수 없는 오류가 발생했습니다');
  }
}
```

### 3. UI 구현 예시

```dart
// lib/screens/progression_screen.dart

import 'package:flutter/material.dart';
import '../services/progression_service.dart';
import '../models/progression_models.dart';

class ProgressionScreen extends StatefulWidget {
  @override
  _ProgressionScreenState createState() => _ProgressionScreenState();
}

class _ProgressionScreenState extends State<ProgressionScreen> {
  final ProgressionService _service = ProgressionService();
  ProgramProgressionAnalysis? _analysis;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadProgressionData();
  }

  Future<void> _loadProgressionData() async {
    try {
      final analysis = await _service.getProgressionAnalysis();
      setState(() {
        _analysis = analysis;
        _isLoading = false;
      });
    } catch (e) {
      setState(() => _isLoading = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('데이터를 불러오는데 실패했습니다')),
      );
    }
  }

  Future<void> _applyProgression() async {
    if (_analysis?.recommendation == null) return;

    try {
      final result = await _service.applyProgression(
        newProgram: _analysis!.recommendation!.newProgram,
        newDaysPerWeek: _analysis!.recommendation!.newDaysPerWeek,
      );

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(result.message)),
      );

      // 데이터 새로고침
      _loadProgressionData();
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('진급 적용에 실패했습니다')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }

    if (_analysis == null) {
      return Scaffold(
        body: Center(child: Text('데이터를 불러올 수 없습니다')),
      );
    }

    return Scaffold(
      appBar: AppBar(title: Text('프로그램 진급 분석')),
      body: SingleChildScrollView(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 현재 프로그램 정보
            Card(
              child: Padding(
                padding: EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('현재 프로그램', style: Theme.of(context).textTheme.titleLarge),
                    SizedBox(height: 8),
                    Text('${_analysis!.currentProgram} - 주 ${_analysis!.currentDaysPerWeek}회'),
                    Text('완료 사이클: ${_analysis!.completedCycles}/3'),
                    Text('일관성: ${_analysis!.consistencyRate.toStringAsFixed(1)}%'),
                  ],
                ),
              ),
            ),

            // 진급 추천
            if (_analysis!.recommendation != null) ...[
              SizedBox(height: 16),
              Card(
                color: Colors.blue.shade50,
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Icon(Icons.trending_up, color: Colors.blue),
                          SizedBox(width: 8),
                          Text('진급 추천', style: Theme.of(context).textTheme.titleLarge),
                        ],
                      ),
                      SizedBox(height: 12),
                      Text(
                        '${_analysis!.recommendation!.newProgram} - 주 ${_analysis!.recommendation!.newDaysPerWeek}회',
                        style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                      ),
                      SizedBox(height: 8),
                      Text(_analysis!.recommendation!.reason),
                      SizedBox(height: 12),
                      Text('기대 효과:', style: TextStyle(fontWeight: FontWeight.bold)),
                      ..._analysis!.recommendation!.expectedBenefits
                          .map((benefit) => Padding(
                                padding: EdgeInsets.only(left: 8, top: 4),
                                child: Text('• $benefit'),
                              )),
                      SizedBox(height: 16),
                      ElevatedButton(
                        onPressed: _applyProgression,
                        child: Text('진급 적용하기'),
                        style: ElevatedButton.styleFrom(
                          minimumSize: Size(double.infinity, 48),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],

            // 성과 지표
            SizedBox(height: 16),
            Card(
              child: Padding(
                padding: EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('성과 지표', style: Theme.of(context).textTheme.titleLarge),
                    SizedBox(height: 12),
                    _buildMetricRow('볼륨 증가', '+${_analysis!.performanceMetrics.volumeIncreasePercent}%'),
                    _buildMetricRow('근력 향상', '+${_analysis!.performanceMetrics.strengthGainPercent}%'),
                    _buildMetricRow('평균 운동 시간', '${_analysis!.performanceMetrics.averageWorkoutDuration}분'),
                    _buildMetricRow('총 운동 횟수', '${_analysis!.performanceMetrics.totalWorkouts}회'),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildMetricRow(String label, String value) {
    return Padding(
      padding: EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label),
          Text(value, style: TextStyle(fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }
}
```

---

## 에러 처리 가이드

### 401 Unauthorized
- 토큰이 만료되었거나 유효하지 않음
- 해결: 토큰 갱신 또는 재로그인

### 404 Not Found
- 사용자 프로필이 없음
- 해결: 프로필 생성 후 재시도

### 500 Internal Server Error
- 서버 내부 오류
- 해결: 로그 확인 및 데이터 검증

---

## 테스트 체크리스트

- [ ] 진급 분석 API 호출 및 데이터 파싱
- [ ] 볼륨 최적화 추천 조회
- [ ] 회복 상태 분석 확인
- [ ] 프로그램 전환 체크
- [ ] 진급 적용 기능 테스트
- [ ] 에러 상황 처리 확인