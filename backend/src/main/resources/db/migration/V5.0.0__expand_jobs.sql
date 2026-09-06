-- Allow additional jobs (STONECUTTER, PROSPECTOR, HERBALIST, …).
-- Hibernate may have created a check constraint from the original enum.

ALTER TABLE IF EXISTS agent DROP CONSTRAINT IF EXISTS agent_job_check;
