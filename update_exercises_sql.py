#!/usr/bin/env python3

# This script updates the comprehensive_exercises.sql file to add muscle_group field

import re

def get_muscle_group_for_category(category):
    """Map exercise category to Korean muscle group name"""
    mapping = {
        'CHEST': '가슴',
        'BACK': '등',
        'LEGS': '하체',
        'SHOULDERS': '어깨',
        'ARMS': '팔',
        'CORE': '복근',
        'CARDIO': '유산소',
        'FULL_BODY': '전신'
    }
    return mapping.get(category, '기타')

def update_sql_file(input_file, output_file):
    with open(input_file, 'r', encoding='utf-8') as f:
        content = f.read()

    # Update INSERT statements to include muscle_group
    content = content.replace(
        "INSERT INTO exercises (name, category, equipment, instructions) VALUES",
        "INSERT INTO exercises (name, category, equipment, instructions, muscle_group) VALUES"
    )

    lines = content.split('\n')
    updated_lines = []
    current_category = None

    for line in lines:
        # Check for category comments
        if '-- CHEST 운동' in line:
            current_category = 'CHEST'
        elif '-- BACK 운동' in line:
            current_category = 'BACK'
        elif '-- LEGS 운동' in line:
            current_category = 'LEGS'
        elif '-- SHOULDERS 운동' in line:
            current_category = 'SHOULDERS'
        elif '-- ARMS 운동' in line:
            current_category = 'ARMS'
        elif '-- CORE 운동' in line:
            current_category = 'CORE'
        elif '-- CARDIO 운동' in line:
            current_category = 'CARDIO'
        elif '-- FULL_BODY 운동' in line:
            current_category = 'FULL_BODY'

        # Update exercise insertions
        if line.strip().startswith('(') and current_category and "'CHEST'" not in line:
            # This is an exercise line that needs muscle_group added
            if line.rstrip().endswith('),'):
                # Remove the closing ),
                line_without_end = line.rstrip()[:-2]
                muscle_group = get_muscle_group_for_category(current_category)
                line = f"{line_without_end}, '{muscle_group}'),"
            elif line.rstrip().endswith(');'):
                # Last item in the INSERT statement
                line_without_end = line.rstrip()[:-2]
                muscle_group = get_muscle_group_for_category(current_category)
                line = f"{line_without_end}, '{muscle_group}');"

        updated_lines.append(line)

    with open(output_file, 'w', encoding='utf-8') as f:
        f.write('\n'.join(updated_lines))

    print(f"Updated {output_file}")

if __name__ == "__main__":
    input_file = "/Users/gimjunhyeong/Develop/liftupai/comprehensive_exercises.sql"
    output_file = "/Users/gimjunhyeong/Develop/liftupai/comprehensive_exercises_updated.sql"
    update_sql_file(input_file, output_file)