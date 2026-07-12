CREATE EXTENSION IF NOT EXISTS pgrouting;

CREATE TABLE IF NOT EXISTS routing_edges (
    id BIGSERIAL PRIMARY KEY,
    source BIGINT NOT NULL,
    target BIGINT NOT NULL,
    cost DOUBLE PRECISION NOT NULL,
    reverse_cost DOUBLE PRECISION NOT NULL,
    geom geometry(LineString, 4326)
);

CREATE INDEX IF NOT EXISTS routing_edges_source_idx ON routing_edges (source);
CREATE INDEX IF NOT EXISTS routing_edges_target_idx ON routing_edges (target);
CREATE INDEX IF NOT EXISTS routing_edges_geom_idx ON routing_edges USING GIST (geom);
