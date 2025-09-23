#!/bin/bash

echo "Fixing imports and package declarations..."

# Fix all entity imports
find src/main/kotlin -name "*.kt" -type f | while read file; do
    # Update entity enum imports
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.UserRole/import com.richjun.liftupai.domain.auth.entity.UserRole/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.UserStatus/import com.richjun.liftupai.domain.auth.entity.UserStatus/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.MessageType/import com.richjun.liftupai.domain.chat.entity.MessageType/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.MessageStatus/import com.richjun.liftupai.domain.chat.entity.MessageStatus/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.SessionStatus/import com.richjun.liftupai.domain.workout.entity.SessionStatus/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.ExerciseCategory/import com.richjun.liftupai.domain.workout.entity.ExerciseCategory/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.Equipment/import com.richjun.liftupai.domain.workout.entity.Equipment/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.MuscleGroup/import com.richjun.liftupai.domain.workout.entity.MuscleGroup/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.ExperienceLevel/import com.richjun.liftupai.domain.user.entity.ExperienceLevel/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.FitnessGoal/import com.richjun.liftupai.domain.user.entity.FitnessGoal/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.PTStyle/import com.richjun.liftupai.domain.user.entity.PTStyle/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.Gender/import com.richjun.liftupai.domain.user.entity.Gender/g' "$file"

    # Fix import .*
    sed -i '' 's/import com\.richjun\.liftupai\.entity\.\*/import com.richjun.liftupai.domain.auth.entity.*/g' "$file"
    sed -i '' 's/import com\.richjun\.liftupai\.dto\.\*/import com.richjun.liftupai.domain.auth.dto.*/g' "$file"
done

# Fix package declarations for files that haven't been fixed
find src/main/kotlin/com/richjun/liftupai/domain/auth -name "*.kt" | while read file; do
    sed -i '' 's/^package com\.richjun\.liftupai\.service$/package com.richjun.liftupai.domain.auth.service/' "$file"
    sed -i '' 's/^package com\.richjun\.liftupai\.controller$/package com.richjun.liftupai.domain.auth.controller/' "$file"
    sed -i '' 's/^package com\.richjun\.liftupai\.repository$/package com.richjun.liftupai.domain.auth.repository/' "$file"
    sed -i '' 's/^package com\.richjun\.liftupai\.entity$/package com.richjun.liftupai.domain.auth.entity/' "$file"
done

find src/main/kotlin/com/richjun/liftupai/domain/chat -name "*.kt" | while read file; do
    sed -i '' 's/^package com\.richjun\.liftupai\.service$/package com.richjun.liftupai.domain.chat.service/' "$file"
    sed -i '' 's/^package com\.richjun\.liftupai\.controller$/package com.richjun.liftupai.domain.chat.controller/' "$file"
    sed -i '' 's/^package com\.richjun\.liftupai\.repository$/package com.richjun.liftupai.domain.chat.repository/' "$file"
    sed -i '' 's/^package com\.richjun\.liftupai\.entity$/package com.richjun.liftupai.domain.chat.entity/' "$file"
done

find src/main/kotlin/com/richjun/liftupai/domain/workout -name "*.kt" | while read file; do
    sed -i '' 's/^package com\.richjun\.liftupai\.repository$/package com.richjun.liftupai.domain.workout.repository/' "$file"
    sed -i '' 's/^package com\.richjun\.liftupai\.entity$/package com.richjun.liftupai.domain.workout.entity/' "$file"
done

# Fix GeminiAIService imports
find src/main/kotlin -name "*.kt" | while read file; do
    sed -i '' 's/import com\.richjun\.liftupai\.service\.GeminiAIService/import com.richjun.liftupai.domain.ai.service.GeminiAIService/g' "$file"
done

echo "Import and package fixes completed!"