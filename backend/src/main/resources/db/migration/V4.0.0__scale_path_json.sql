-- Scale-up: JSON path storage on agents (Hibernate also maps jsonb via update).
-- Safe if agent table does not exist yet.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'agent'
    ) THEN
        ALTER TABLE agent ADD COLUMN IF NOT EXISTS currentpath jsonb;
    END IF;
END $$;
