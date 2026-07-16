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

Hibernate also auto-updates entity tables (`Tile`, `Agent`, etc.) on startup via `quarkus.hibernate-orm.database.generation=update`.

## Configuration

Backend settings in `backend/src/main/resources/application.properties`:

- PostGIS dialect for Hibernate Spatial
- CORS enabled for `http://localhost:5173`
- Flyway migrations run at startup
- Dev Services disabled (uses the Podman Compose DB)
