-- Economic layer: tile resources and agent jobs/inventory.
-- Tables themselves are created by Hibernate; this migration is safe on a fresh DB
-- where those relations do not exist yet.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'tile'
    ) THEN
        ALTER TABLE tile ADD COLUMN IF NOT EXISTS resourcetype VARCHAR(32);
        ALTER TABLE tile ADD COLUMN IF NOT EXISTS quantity INTEGER NOT NULL DEFAULT 0;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'agent'
    ) THEN
        ALTER TABLE agent ADD COLUMN IF NOT EXISTS job VARCHAR(32);

        CREATE TABLE IF NOT EXISTS agent_inventory (
            agent_id BIGINT NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
            resource_type VARCHAR(32) NOT NULL,
            quantity INTEGER NOT NULL,
            PRIMARY KEY (agent_id, resource_type)
        );
    END IF;
END $$;
