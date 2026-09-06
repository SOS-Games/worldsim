-- Fast agent-on-tile lookups. Joins must use FLOOR(ST_X(..))::int (not float).

CREATE UNIQUE INDEX IF NOT EXISTS tile_x_y_uidx ON tile (x, y);
