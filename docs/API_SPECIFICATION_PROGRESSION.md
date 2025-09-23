# 운동 프로그램 진급 시스템 API 명세서

## 개요
운동 프로그램의 진급, 볼륨 최적화, 회복 관리를 위한 API 엔드포인트입니다.
10년차 PT 트레이너의 관점에서 사용자의 운동 발전을 체계적으로 관리합니다.

## Base URL
```
https://api.liftupai.com/api/v2/progression
```

## 인증
모든 엔드포인트는 Bearer Token 인증이 필요합니다.
```
Authorization: Bearer {token}
```

---

## 1. 프로그램 진급 분석
사용자의 운동 기록을 분석하여 프로그램 진급 준비 상태를 평가합니다.

### Endpoint
```
GET /api/v2/progression/analysis
```

### Response
```json
{
  "success": true,
  "data": {
    "current_program": "PPL",
    "current_days_per_week": 3,
    "completed_cycles": 2,
    "current_cycle": 3,
    "consistency_rate": 85.5,
    "recovery_status": "MODERATE",
    "ready_for_progression": true,
    "recommendation": {
      "new_program": "UPPER_LOWER",
      "new_days_per_week": 4,
      "reason": "일관성과 볼륨 증가를 보여 4일 프로그램으로 진급 준비가 되었습니다",
      "expected_benefits": [
        "주당 운동 빈도 증가로 더 많은 볼륨 처리 가능",
        "근육군별 더 집중적인 트레이닝",
        "회복 시간 최적화"
      ]
    },
    "performance_metrics": {
      "volume_increase_percent": 15,
      "strength_gain_percent": 12,
      "average_workout_duration": 65,
      "total_workouts": 24
    }
  }
}
```

### Flutter 구현 예시
```dart
class ProgressionAnalysis {
  final String currentProgram;
  final int currentDaysPerWeek;
  final int completedCycles;
  final int currentCycle;
  final double consistencyRate;
  final String recoveryStatus;
  final bool readyForProgression;
  final ProgressionRecommendation? recommendation;
  final PerformanceMetrics performanceMetrics;

  ProgressionAnalysis.fromJson(Map<String, dynamic> json);
}

// API 호출
Future<ProgressionAnalysis> getProgressionAnalysis() async {
  final response = await http.get(
    Uri.parse('$baseUrl/analysis'),
    headers: {'Authorization': 'Bearer $token'},
  );

  if (response.statusCode == 200) {
    final data = json.decode(response.body);
    return ProgressionAnalysis.fromJson(data['data']);
  }
  throw Exception('Failed to load progression analysis');
}
```

---

## 2. 볼륨 최적화 추천
현재 운동 볼륨을 분석하고 최적화된 볼륨을 추천합니다.

### Endpoint
```
GET /api/v2/progression/volume/optimization
```

### Response
```json
{
  "success": true,
  "data": {
    "current_volume": {
      "weekly_volume": 15000.0,
      "sets_per_week": 60,
      "reps_per_week": 600
    },
    "recommended_volume": {
      "weekly_volume": 16500.0,
      "sets_per_week": 66,
      "reps_per_week": 660
    },
    "adjustment_reason": "RPE가 낮고 회복이 양호하여 볼륨 증가 권장",
    "muscle_group_volumes": {
      "가슴": 12,
      "등": 15,
      "하체": 18,
      "어깨": 8,
      "팔": 7
    },
    "mev_reached": true,
    "mav_exceeded": false
  }
}
```

### Flutter 구현 예시
```dart
class VolumeOptimization {
  final VolumeMetrics currentVolume;
  final VolumeMetrics recommendedVolume;
  final String adjustmentReason;
  final Map<String, int> muscleGroupVolumes;
  final bool mevReached;  // 최소 효과 볼륨 도달
  final bool mavExceeded; // 최대 적응 볼륨 초과

  // UI 표시 예시
  Widget buildVolumeCard() {
    return Card(
      child: Column(
        children: [
          Text('현재 주간 볼륨: ${currentVolume.weeklyVolume}kg'),
          Text('권장 볼륨: ${recommendedVolume.weeklyVolume}kg'),
          if (mavExceeded)
            Text('⚠️ 과훈련 위험! 볼륨을 줄이세요',
                 style: TextStyle(color: Colors.red)),
          Text(adjustmentReason),
        ],
      ),
    );
  }
}
```

---

## 3. 회복 상태 분석
근육군별 회복 상태를 분석하고 다음 운동을 추천합니다.

### Endpoint
```
GET /api/v2/progression/recovery
```

### Response
```json
{
  "success": true,
  "data": {
    "muscle_groups": {
      "가슴": {
        "muscle_name": "가슴",
        "last_workout": "2024-01-20T10:00:00",
        "hours_since_workout": 72,
        "recovery_percentage": 100,
        "ready_for_next_session": true,
        "recommended_rest_hours": 0
      },
      "등": {
        "muscle_name": "등",
        "last_workout": "2024-01-21T10:00:00",
        "hours_since_workout": 48,
        "recovery_percentage": 85,
        "ready_for_next_session": false,
        "recommended_rest_hours": 24
      }
    },
    "overall_recovery_score": 87,
    "needs_deload_week": false,
    "deload_reason": null,
    "next_recommended_muscles": ["가슴", "삼두", "어깨"]
  }
}
```

### Flutter UI 구현 예시
```dart
class RecoveryStatusWidget extends StatelessWidget {
  final RecoveryAnalysis recovery;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        // 전체 회복도 게이지
        CircularProgressIndicator(
          value: recovery.overallRecoveryScore / 100,
          backgroundColor: Colors.grey[300],
          valueColor: AlwaysStoppedAnimation<Color>(
            _getRecoveryColor(recovery.overallRecoveryScore)
          ),
        ),
        Text('전체 회복도: ${recovery.overallRecoveryScore}%'),

        // 근육군별 회복 상태
        ...recovery.muscleGroups.entries.map((entry) {
          final muscle = entry.value;
          return ListTile(
            title: Text(muscle.muscleName),
            subtitle: Text('회복률: ${muscle.recoveryPercentage}%'),
            trailing: muscle.readyForNextSession
              ? Icon(Icons.check_circle, color: Colors.green)
              : Text('${muscle.recommendedRestHours}시간 더 휴식'),
          );
        }),

        // 디로드 주 알림
        if (recovery.needsDeloadWeek)
          Card(
            color: Colors.orange[100],
            child: ListTile(
              leading: Icon(Icons.warning, color: Colors.orange),
              title: Text('회복 주 필요'),
              subtitle: Text(recovery.deloadReason ?? ''),
            ),
          ),

        // 추천 근육군
        Wrap(
          children: recovery.nextRecommendedMuscles.map((muscle) {
            return Chip(label: Text(muscle));
          }).toList(),
        ),
      ],
    );
  }

  Color _getRecoveryColor(int score) {
    if (score >= 90) return Colors.green;
    if (score >= 70) return Colors.yellow[700]!;
    if (score >= 50) return Colors.orange;
    return Colors.red;
  }
}
```

---

## 4. 프로그램 전환 체크
현재 프로그램의 전환 필요성을 평가합니다.

### Endpoint
```
GET /api/v2/progression/transition/check
```

### Response
```json
{
  "success": true,
  "data": {
    "should_transition": true,
    "current_program_weeks": 7,
    "plateau_detected": false,
    "reason": "7주간 같은 프로그램을 수행하여 변화가 필요합니다",
    "goal_completion_rate": 75,
    "suggested_programs": [
      {
        "program_name": "5-Day Bro Split",
        "days_per_week": 5,
        "description": "근육군별 집중 트레이닝",
        "benefits": ["최대 볼륨", "세밀한 발달"],
        "difficulty": "상급"
      },
      {
        "program_name": "PPL x2",
        "days_per_week": 6,
        "description": "주 2회 PPL 반복",
        "benefits": ["높은 빈도", "빠른 성장"],
        "difficulty": "상급"
      }
    ]
  }
}
```

### Flutter 프로그램 선택 UI
```dart
class ProgramTransitionScreen extends StatelessWidget {
  final ProgramTransitionRecommendation recommendation;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('프로그램 변경 추천')),
      body: Column(
        children: [
          // 현재 상태
          Card(
            child: ListTile(
              title: Text('현재 프로그램 ${recommendation.currentProgramWeeks}주차'),
              subtitle: Text(recommendation.reason),
              trailing: recommendation.plateauDetected
                ? Chip(
                    label: Text('정체기 감지'),
                    backgroundColor: Colors.orange,
                  )
                : null,
            ),
          ),

          // 추천 프로그램 목록
          Expanded(
            child: ListView.builder(
              itemCount: recommendation.suggestedPrograms.length,
              itemBuilder: (context, index) {
                final program = recommendation.suggestedPrograms[index];
                return Card(
                  margin: EdgeInsets.all(8),
                  child: InkWell(
                    onTap: () => _selectProgram(program),
                    child: Padding(
                      padding: EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Text(program.programName,
                                   style: Theme.of(context).textTheme.headline6),
                              Chip(label: Text(program.difficulty)),
                            ],
                          ),
                          Text('주 ${program.daysPerWeek}회'),
                          SizedBox(height: 8),
                          Text(program.description),
                          SizedBox(height: 8),
                          Wrap(
                            children: program.benefits.map((benefit) {
                              return Padding(
                                padding: EdgeInsets.only(right: 8),
                                child: Chip(
                                  label: Text(benefit),
                                  backgroundColor: Colors.green[100],
                                ),
                              );
                            }).toList(),
                          ),
                        ],
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
```

---

## 5. 진급 상태 요약 (대시보드용)
대시보드에 표시할 간단한 진급 상태 요약입니다.

### Endpoint
```
GET /api/v2/progression/summary
```

### Response
```json
{
  "success": true,
  "data": {
    "current_level": "PPL - 주 3회",
    "next_milestone": "Upper/Lower - 주 4회",
    "progress_percentage": 67,
    "days_until_progression": 14,
    "recent_achievements": [
      {
        "type": "VOLUME_RECORD",
        "description": "주간 최대 볼륨 달성",
        "achieved_at": "2024-01-20T10:00:00",
        "value": "15,000kg"
      },
      {
        "type": "CONSISTENCY_STREAK",
        "description": "4주 연속 목표 달성",
        "achieved_at": "2024-01-19T10:00:00",
        "value": "28일"
      }
    ]
  }
}
```

### Flutter 대시보드 위젯
```dart
class ProgressionSummaryCard extends StatelessWidget {
  final ProgressionSummary summary;

  @override
  Widget build(BuildContext context) {
    return Card(
      elevation: 4,
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('진급 현황', style: Theme.of(context).textTheme.headline6),
            SizedBox(height: 16),

            // 현재 레벨과 다음 목표
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('현재', style: TextStyle(fontSize: 12)),
                    Text(summary.currentLevel,
                         style: TextStyle(fontWeight: FontWeight.bold)),
                  ],
                ),
                Icon(Icons.arrow_forward),
                Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text('목표', style: TextStyle(fontSize: 12)),
                    Text(summary.nextMilestone,
                         style: TextStyle(fontWeight: FontWeight.bold)),
                  ],
                ),
              ],
            ),

            // 진행률 바
            SizedBox(height: 16),
            LinearProgressIndicator(
              value: summary.progressPercentage / 100,
              minHeight: 8,
              backgroundColor: Colors.grey[300],
              valueColor: AlwaysStoppedAnimation<Color>(Colors.blue),
            ),
            SizedBox(height: 4),
            Text('진급까지 ${summary.daysUntilProgression ?? 0}일',
                 style: TextStyle(fontSize: 12)),

            // 최근 성취
            if (summary.recentAchievements.isNotEmpty) ...[
              SizedBox(height: 16),
              Text('최근 성취', style: TextStyle(fontWeight: FontWeight.bold)),
              ...summary.recentAchievements.map((achievement) {
                return ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: _getAchievementIcon(achievement.type),
                  title: Text(achievement.description),
                  subtitle: Text(achievement.value),
                );
              }).take(2),
            ],
          ],
        ),
      ),
    );
  }

  Icon _getAchievementIcon(String type) {
    switch (type) {
      case 'VOLUME_RECORD':
        return Icon(Icons.fitness_center, color: Colors.blue);
      case 'STRENGTH_PR':
        return Icon(Icons.trending_up, color: Colors.green);
      case 'CONSISTENCY_STREAK':
        return Icon(Icons.calendar_today, color: Colors.orange);
      default:
        return Icon(Icons.star, color: Colors.yellow);
    }
  }
}
```

---

## 6. 진급 추천 적용
시스템이 추천한 프로그램 진급을 적용합니다.

### Endpoint
```
POST /api/v2/progression/apply-recommendation
```

### Request Body
```json
{
  "new_program": "UPPER_LOWER",
  "new_days_per_week": 4
}
```

### Response
```json
{
  "success": true,
  "data": {
    "success": true,
    "message": "프로그램이 UPPER_LOWER(주 4회)로 업그레이드되었습니다",
    "new_program": "UPPER_LOWER",
    "new_days_per_week": 4
  }
}
```

### Flutter 진급 적용
```dart
Future<void> applyProgression(String newProgram, int daysPerWeek) async {
  try {
    final response = await http.post(
      Uri.parse('$baseUrl/apply-recommendation'),
      headers: {
        'Authorization': 'Bearer $token',
        'Content-Type': 'application/json',
      },
      body: json.encode({
        'new_program': newProgram,
        'new_days_per_week': daysPerWeek,
      }),
    );

    if (response.statusCode == 200) {
      final data = json.decode(response.body);

      // 성공 다이얼로그
      showDialog(
        context: context,
        builder: (context) => AlertDialog(
          title: Text('진급 완료! 🎉'),
          content: Text(data['data']['message']),
          actions: [
            TextButton(
              onPressed: () {
                Navigator.of(context).pop();
                // 홈 화면으로 이동
                Navigator.pushReplacementNamed(context, '/home');
              },
              child: Text('확인'),
            ),
          ],
        ),
      );
    }
  } catch (e) {
    // 에러 처리
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('진급 적용 실패: $e')),
    );
  }
}
```

---

## 에러 응답
모든 엔드포인트는 다음과 같은 에러 응답을 반환할 수 있습니다:

```json
{
  "success": false,
  "error": {
    "code": "UNAUTHORIZED",
    "message": "인증이 필요합니다"
  }
}
```

### 에러 코드
- `UNAUTHORIZED` (401): 인증 실패
- `NOT_FOUND` (404): 리소스를 찾을 수 없음
- `BAD_REQUEST` (400): 잘못된 요청
- `INTERNAL_ERROR` (500): 서버 내부 오류

---

## Flutter 통합 예시

### Service 클래스
```dart
class ProgressionService {
  final String baseUrl = 'https://api.liftupai.com/api/v2/progression';
  final String token;

  ProgressionService({required this.token});

  Future<ProgressionAnalysis> getAnalysis() async {
    // API 호출 구현
  }

  Future<VolumeOptimization> getVolumeOptimization() async {
    // API 호출 구현
  }

  Future<RecoveryAnalysis> getRecovery() async {
    // API 호출 구현
  }

  Future<ProgramTransitionRecommendation> checkTransition() async {
    // API 호출 구현
  }

  Future<bool> applyRecommendation(String program, int days) async {
    // API 호출 구현
  }
}
```

### Provider 패턴 사용
```dart
class ProgressionProvider extends ChangeNotifier {
  ProgressionAnalysis? _analysis;
  RecoveryAnalysis? _recovery;
  bool _isLoading = false;

  ProgressionAnalysis? get analysis => _analysis;
  RecoveryAnalysis? get recovery => _recovery;
  bool get isLoading => _isLoading;

  final ProgressionService _service;

  ProgressionProvider(this._service);

  Future<void> loadProgressionData() async {
    _isLoading = true;
    notifyListeners();

    try {
      _analysis = await _service.getAnalysis();
      _recovery = await _service.getRecovery();
    } catch (e) {
      print('Error loading progression data: $e');
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  bool get shouldShowProgressionAlert {
    return _analysis?.readyForProgression ?? false;
  }

  bool get needsDeloadWeek {
    return _recovery?.needsDeloadWeek ?? false;
  }
}
```

---

## 사용 시나리오

### 1. 앱 시작 시 진급 체크
```dart
// 앱 시작 또는 홈 화면 진입 시
await progressionProvider.loadProgressionData();

if (progressionProvider.shouldShowProgressionAlert) {
  // 진급 알림 표시
  showProgressionRecommendation();
}
```

### 2. 운동 시작 전 회복 체크
```dart
// 운동 시작 전
final recovery = await progressionService.getRecovery();

if (recovery.needsDeloadWeek) {
  // 디로드 주 권장 알림
  showDeloadWeekAlert();
} else {
  // 추천 근육군 표시
  showRecommendedMuscles(recovery.nextRecommendedMuscles);
}
```

### 3. 주간 리포트에 진급 정보 포함
```dart
// 주간 리포트 생성
final analysis = await progressionService.getAnalysis();
final volumeOpt = await progressionService.getVolumeOptimization();

// 리포트에 포함
WeeklyReport(
  consistencyRate: analysis.consistencyRate,
  volumeIncrease: analysis.performanceMetrics.volumeIncreasePercent,
  nextGoal: analysis.recommendation?.newProgram ?? '현재 프로그램 유지',
  volumeAdjustment: volumeOpt.adjustmentReason,
);
```