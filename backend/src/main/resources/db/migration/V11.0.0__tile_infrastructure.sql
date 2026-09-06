ALTER TABLE tile ADD COLUMN IF NOT EXISTS infrastructuretype VARCHAR(32) NOT NULL DEFAULT 'none';
ALTER TABLE tile ADD COLUMN IF NOT EXISTS movementcost DOUBLE PRECISION NOT NULL DEFAULT 1.0;
ALTER TABLE tile ADD COLUMN IF NOT EXISTS navigablebyboat BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE tile SET navigablebyboat = TRUE WHERE terraintype = 'water';

CREATE INDEX IF NOT EXISTS tile_infrastructure_idx
    ON tile (infrastructuretype)
    WHERE infrastructuretype <> 'none';
