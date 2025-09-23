#!/bin/bash

# Update package declarations
find src/main/kotlin -name "*.kt" -type f | while read file; do
    # Update package declarations based on file location
    if [[ $file == *"/domain/auth/"* ]]; then
        sed -i '' 's/^package com\.richjun\.liftupai\.\(controller\|service\|repository\|entity\|dto\)/package com.richjun.liftupai.domain.auth.\1/' "$file"
    elif [[ $file == *"/domain/chat/"* ]]; then
        sed -i '' 's/^package com\.richjun\.liftupai\.\(controller\|service\|repository\|entity\|dto\)/package com.richjun.liftupai.domain.chat.\1/' "$file"
    elif [[ $file == *"/domain/user/"* ]]; then
        sed -i '' 's/^package com\.richjun\.liftupai\.\(entity\)/package com.richjun.liftupai.domain.user.\1/' "$file"
    elif [[ $file == *"/domain/workout/"* ]]; then
        sed -i '' 's/^package com\.richjun\.liftupai\.\(entity\|repository\)/package com.richjun.liftupai.domain.workout.\1/' "$file"
    elif [[ $file == *"/domain/ai/"* ]]; then
        sed -i '' 's/^package com\.richjun\.liftupai\.\(service\)/package com.richjun.liftupai.domain.ai.\1/' "$file"
    elif [[ $file == *"/global/config/"* ]]; then
        sed -i '' 's/^package com\.richjun\.liftupai\.config/package com.richjun.liftupai.global.config/' "$file"
    elif [[ $file == *"/global/security/"* ]]; then
        sed -i '' 's/^package com\.richjun\.liftupai\.security/package com.richjun.liftupai.global.security/' "$file"
    elif [[ $file == *"/global/exception/"* ]]; then
        sed -i '' 's/^package com\.richjun\.liftupai\.exception/package com.richjun.liftupai.global.exception/' "$file"
    elif [[ $file == *"/global/common/"* ]]; then
        sed -i '' 's/^package com\.richjun\.liftupai\.dto/package com.richjun.liftupai.global.common/' "$file"
    fi
done

# Update imports in all files
find src/main/kotlin -name "*.kt" -type f | while read file; do
    # Auth domain imports
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.User/import com.richjun.liftupai.domain.auth.entity.User/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.repository\.UserRepository/import com.richjun.liftupai.domain.auth.repository.UserRepository/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.service\.AuthService/import com.richjun.liftupai.domain.auth.service.AuthService/g' "$file"

    # Chat domain imports
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.ChatMessage/import com.richjun.liftupai.domain.chat.entity.ChatMessage/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.repository\.ChatMessageRepository/import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.service\.ChatService/import com.richjun.liftupai.domain.chat.service.ChatService/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.dto\.\(ChatMessage\|ChatHistory\|ChatDto\)/import com.richjun.liftupai.domain.chat.dto.\1/g' "$file"

    # User domain imports
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.UserProfile/import com.richjun.liftupai.domain.user.entity.UserProfile/g' "$file"

    # Workout domain imports
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.WorkoutSession/import com.richjun.liftupai.domain.workout.entity.WorkoutSession/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.Exercise/import com.richjun.liftupai.domain.workout.entity.Exercise/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.WorkoutExercise/import com.richjun.liftupai.domain.workout.entity.WorkoutExercise/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.ExerciseSet/import com.richjun.liftupai.domain.workout.entity.ExerciseSet/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.repository\.WorkoutSessionRepository/import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.repository\.ExerciseRepository/import com.richjun.liftupai.domain.workout.repository.ExerciseRepository/g' "$file"

    # AI domain imports
    sed -i '' 's/import com\.richjun\.liftupai\.service\.GeminiAIService/import com.richjun.liftupai.domain.ai.service.GeminiAIService/g' "$file"

    # Global imports
    sed -i '' 's/import com\.richjun\.liftupai\.security\./import com.richjun.liftupai.global.security./g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.exception\./import com.richjun.liftupai.global.exception./g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.config\./import com.richjun.liftupai.global.config./g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.dto\.ApiResponse/import com.richjun.liftupai.global.common.ApiResponse/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.dto\.ErrorResponse/import com.richjun.liftupai.global.common.ErrorResponse/g' "$file"
done

echo "Package updates completed!"