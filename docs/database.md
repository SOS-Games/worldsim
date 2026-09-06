# Database

## Connection

| Setting | Value |
|---------|-------|
| Host | `localhost:5432` |
| Database | `simulation_db` |
| User | `postgres` |
| Password | `pass` |

Container image (see `db/docker-compose.yml`): `pgrouting/pgrouting:18-3.6-4.0` (PostGIS + pgRouting preinstalled).

Data is stored in a named volume (`pgdata`) mounted at `/var/lib/postgresql` (Postgres 18+ layout), so it survives `podman compose down` and restarts. To wipe the database, run `podman compose down -v`.

## Migrations

Flyway migrations live in `backend/src/main/resources/db/migration/`:

| Migration | Purpose |
|-----------|---------|
| `V1.0.0__init_postgis.sql` | Enables PostGIS |
| `V2.0.0__pgrouting.sql` | Enables pgRouting, creates `routing_edges` |
| `V3.0.0__economics.sql` | Tile resources, agent jobs, inventory table |
| `V4.0.0__scale_path_json.sql` | JSON path column for scaled agent movement |
| `V5.0.0__expand_jobs.sql` | Drops old job check constraint so new jobs can persist |
| `V6.0.0__expand_resource_types.sql` | Drops old resource-type check constraint so new resources can persist |
| `V7.0.0__markets.sql` | City/market tables, tile city_id, trader column; renames old TRADERS to FARMER |
| `V8.0.0__inventory_resource_types.sql` | Drops old inventory resource-type check so stone/iron/herbs can persist |
| `V9.0.0__tile_xy_index.sql` | Unique index on tile (x, y) so physics can find an NPC’s tile without scanning the map |
| `V10.0.0__tile_resource_index.sql` | Partial index on resource tiles so gatherer AI can list stocked patches quickly |
| `V11.0.0__tile_infrastructure.sql` | Tile roads/bridges/villages, movement cost, boat flag |
| `V12.0.0__vehicles.sql` | Vehicles, harbor boat stock, boat/wagon routing costs |

Hibernate also auto-updates entity tables (`Tile`, `Agent`, etc.) on startup via `quarkus.hibernate-orm.database.generation=update`.

## Configuration

Backend settings in `backend/src/main/resources/application.properties`:

- PostGIS dialect for Hibernate Spatial
- CORS enabled for `http://localhost:5173`
- Flyway migrations run at startup
- Dev Services disabled (uses the Podman Compose DB)
