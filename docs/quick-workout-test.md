# Quick Workout Recommendation API Test

## API Endpoints Implemented

### 1. GET /api/workouts/recommendations/quick
- **Purpose**: Get quick workout recommendations
- **Query Parameters**:
  - `duration` (optional): Duration in minutes (default: 30)
  - `equipment` (optional): Equipment type (dumbbell, barbell, bodyweight, etc.)
  - `targetMuscle` (optional): Target muscle group (chest, back, legs, shoulders, arms, core, full_body)

**Example Request:**
```
GET /api/workouts/recommendations/quick?duration=30&equipment=dumbbells&targetMuscle=full_body
Authorization: Bearer {token}
```

**Example Response:**
```json
{
  "success": true,
  "data": {
    "recommendation": {
      "workout_id": "quick_30min_fullbody_dumbbells",
      "name": "30분 전신 덤벨 운동",
      "duration": 30,
      "difficulty": "intermediate",
      "exercises": [
        {
          "exercise_id": "1",
          "name": "덤벨 스쿼트",
          "sets": 4,
          "reps": "12-15",
          "rest": 90,
          "order": 1
        }
      ],
      "estimated_calories": 250,
      "target_muscles": ["legs", "chest", "shoulders"],
      "equipment": ["dumbbell"]
    },
    "alternatives": [
      {
        "workout_id": "quick_20min_core",
        "name": "20분 코어 집중",
        "duration": 20
      }
    ]
  }
}
```

### 2. POST /api/workouts/start-recommended
- **Purpose**: Start a recommended workout immediately

**Example Request:**
```
POST /api/workouts/start-recommended
Authorization: Bearer {token}
Content-Type: application/json

{
  "recommendation_id": "quick_30min_fullbody_dumbbells",
  "adjustments": {
    "duration": 25,
    "skip_exercises": [],
    "substitute_exercises": {}
  }
}
```

**Example Response:**
```json
{
  "success": true,
  "data": {
    "session_id": "123",
    "workout_name": "30분 전신 덤벨 운동",
    "start_time": "2024-01-20T10:00:00",
    "exercises": [
      {
        "exercise_id": "1",
        "name": "덤벨 스쿼트",
        "planned_sets": 4,
        "planned_reps": "12-15",
        "suggested_weight": 15.0,
        "rest_timer": 90
      }
    ],
    "estimated_duration": 25,
    "started": true
  }
}
```

## Implementation Features

### Filtering Support
- **Duration**: Generates workouts based on available time (20-60 minutes)
- **Equipment**: Filters exercises by available equipment
- **Target Muscle**: Focuses on specific muscle groups or full-body workouts

### Smart Recommendations
- **Exercise Selection**: Chooses appropriate exercises based on muscle group and equipment
- **Set/Rep Schemes**: Varies based on exercise category (strength vs endurance)
- **Rest Times**: Optimized for exercise type and intensity
- **Weight Suggestions**: Based on personal records or estimated starting weights

### Alternatives
- **Duration Alternatives**: Shorter core workouts or longer full-body sessions
- **Equipment Alternatives**: Bodyweight alternatives when equipment not available
- **Muscle Group Variations**: Different focus areas

### User Personalization
- **Weight Suggestions**: Uses 80% of personal best or estimated based on body weight
- **Exercise History**: Considers user's previous performance
- **Fitness Level**: Adapts recommendations to user experience

## Error Handling
- Validates input parameters
- Handles missing user profiles gracefully
- Provides meaningful error messages
- Prevents duplicate active sessions

## Test Plan
1. Test with different duration filters (15, 30, 45, 60 minutes)
2. Test with various equipment types (dumbbell, barbell, bodyweight, machine)
3. Test with different target muscles (chest, back, legs, full_body, etc.)
4. Test starting recommended workouts
5. Test with workout adjustments (duration changes, skipping exercises)
6. Test error cases (invalid parameters, existing sessions)