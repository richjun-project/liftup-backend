-- Remove all duplicate exercises, keeping only one copy per unique name
USE liftupai_db;

-- Disable foreign key checks to allow deletion
SET FOREIGN_KEY_CHECKS = 0;

-- Delete duplicates, keeping only the one with minimum ID for each name
DELETE e1 FROM exercises e1
INNER JOIN exercises e2
WHERE e1.name = e2.name
AND e1.id > e2.id;

-- Re-enable foreign key checks
SET FOREIGN_KEY_CHECKS = 1;

-- Verify no more duplicates
SELECT name, COUNT(*) as count, GROUP_CONCAT(category) as categories
FROM exercises
GROUP BY name
HAVING COUNT(*) > 1;