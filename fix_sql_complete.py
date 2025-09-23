#!/usr/bin/env python3

def fix_sql_file(input_file, output_file):
    with open(input_file, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    fixed_lines = []
    in_values = False
    has_values = False

    for i, line in enumerate(lines):
        # Skip empty lines
        if line.strip() == '':
            continue

        # Check if this is an INSERT statement
        if line.strip().startswith('INSERT INTO exercises'):
            in_values = True
            has_values = False
            fixed_lines.append(line)
        # Check if this is a comment line
        elif line.strip().startswith('--'):
            # If we're in a VALUES section and haven't seen actual values yet, skip the comment
            if in_values and not has_values:
                continue
            else:
                fixed_lines.append(line)
        # Check if this is a value line
        elif line.strip().startswith('('):
            has_values = True
            # Check if line ends with semicolon (last value of this INSERT)
            if line.rstrip().endswith(';'):
                in_values = False
                has_values = False
            fixed_lines.append(line)
        else:
            fixed_lines.append(line)

    with open(output_file, 'w', encoding='utf-8') as f:
        f.writelines(fixed_lines)

    print(f"Fixed SQL file written to {output_file}")

# Fix the file
fix_sql_file('complete_missing_exercises_fixed.sql', 'complete_missing_exercises_clean.sql')