#!/usr/bin/env python3

def split_sql_file(input_file):
    with open(input_file, 'r', encoding='utf-8') as f:
        content = f.read()

    # Split by INSERT statements
    sections = content.split('INSERT INTO exercises')

    for i, section in enumerate(sections[1:], 1):  # Skip empty first element
        # Add back the INSERT statement
        section = 'USE liftupai_db;\n\nINSERT INTO exercises' + section

        # Find the last semicolon in this section
        lines = section.split('\n')
        for j in range(len(lines) - 1, -1, -1):
            if lines[j].rstrip().endswith(';'):
                # Keep only up to this line
                section = '\n'.join(lines[:j+1])
                break

        output_file = f'exercises_part_{i}.sql'
        with open(output_file, 'w', encoding='utf-8') as f:
            f.write(section)
        print(f"Created {output_file}")

# Split the file
split_sql_file('complete_missing_exercises_clean.sql')