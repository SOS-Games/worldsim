-- Speed up “best patch” lookups used by gatherer AI.
CREATE INDEX IF NOT EXISTS tile_resource_qty_idx
    ON tile (resourcetype)
    WHERE resourcetype IS NOT NULL AND quantity > 0;
