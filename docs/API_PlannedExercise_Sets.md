# PlannedExercise Sets Format

The `PlannedExercise` DTO now supports flexible sets format. You can send either:

## Format 1: Simple integer (number of sets)
```json
{
  "planned_exercises": [
    {
      "exercise_id": 1,
      "exercise_name": "벤치프레스",
      "sets": 3,
      "target_reps": 10,
      "weight": 60.0
    }
  ]
}
```

## Format 2: Detailed array (sets with specific reps/weight)
```json
{
  "planned_exercises": [
    {
      "exercise_id": 1,
      "exercise_name": "벤치프레스",
      "sets": [
        {"set_number": 1, "reps": 12, "weight": 50.0, "rest": 90},
        {"set_number": 2, "reps": 10, "weight": 55.0, "rest": 120},
        {"set_number": 3, "reps": 8, "weight": 60.0, "rest": 150}
      ],
      "target_reps": 10
    }
  ]
}
```

## Mixed Format
Both formats can be used in the same request:
```json
{
  "planned_exercises": [
    {
      "exercise_id": 1,
      "sets": 3,
      "target_reps": 10
    },
    {
      "exercise_id": 2,
      "sets": [
        {"reps": 12, "weight": 80.0},
        {"reps": 10, "weight": 85.0}
      ]
    }
  ]
}
```

## Accessing Data in Code
- Use `plannedExercise.setsCount` to get the number of sets
- Use `plannedExercise.setDetails` to get the detailed set information (null if sets is an integer)
- The original `sets` property contains the raw data (Int or List)