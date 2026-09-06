-- City markets for supply/demand pricing, and rename FOOD gatherers to FARMER
-- so TRADER can mean buy-low / sell-high.

ALTER TABLE IF EXISTS agent DROP CONSTRAINT IF EXISTS agent_job_check;

UPDATE agent SET job = 'FARMER' WHERE job = 'TRADER';

ALTER TABLE IF EXISTS agent ADD COLUMN IF NOT EXISTS trade_resource VARCHAR(32);

CREATE TABLE IF NOT EXISTS city (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS market (
    id BIGSERIAL PRIMARY KEY,
    city_id BIGINT NOT NULL REFERENCES city (id) ON DELETE CASCADE,
    resource_type VARCHAR(32) NOT NULL,
    stock INTEGER NOT NULL DEFAULT 0,
    base_price INTEGER NOT NULL,
    target_stock INTEGER NOT NULL,
    UNIQUE (city_id, resource_type)
);

ALTER TABLE IF EXISTS tile ADD COLUMN IF NOT EXISTS city_id BIGINT REFERENCES city (id);
