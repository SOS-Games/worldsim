# Architecture

```
┌─────────────────┐   /world/map (once)        ┌──────────────────────────────┐
│  React + PixiJS │ ◄── ws /world/live ──────► │  Quarkus backend (Java 21)   │
│  localhost:5173 │   /world/state (metadata)  │  localhost:8080              │
└─────────────────┘                            └──────────────┬───────────────┘
                                                          │ JDBC
                                                          ▼
                                         ┌──────────────────────────────┐
                                         │  PostgreSQL + PostGIS        │
                                         │  + pgRouting                 │
                                         │  localhost:5432              │
                                         └──────────────────────────────┘
```

| Layer | Tech |
|-------|------|
| **Database** | PostgreSQL 18, PostGIS, pgRouting |
| **Backend** | Quarkus 3.37, Hibernate Spatial, Panache, Flyway |
| **Frontend** | React 19, Vite 8, PixiJS 8, TypeScript |

## World scale

Configured in `WorldConfig`:

| Setting | Value |
|---------|-------|
| Map | 100×100 tiles |
| Agents | 240 |
| Cities | 9 |
| Resource patches | forests, farms, mines, quarries, iron veins, and meadows |

If the DB still has an older map (wrong size, or no biome tiles), startup wipes and regenerates to match.

## Navigation

Pathfinding runs **in the database**; walking, harvest, and delivery also run **in the database**:

- **PostgreSQL physics** — one SQL statement steps every agent along their JSON path; harvest/delivery are bulk updates too
- **PostgreSQL regen** — every 10 ticks, resource patches gain +1 up to their cap. Depleted patches keep their type and biome so they can grow back
- **Java AI** — `aiTick` time-slices goal choice + `pgr_dijkstra()` for a rotating subset of agents (`WorldConfig.MAX_AI_PER_TICK`)
- Live agent positions are pushed on `ws://localhost:8080/world/live` after each physics tick (id + x/y only)
- `GET /world/state` is still used for jobs, inventory, paths, and the path overlay
- `GET /world/debug` is a compact snapshot for a CLI or another agent (`scripts/world-debug.ps1`)
- `GET /world/debug/sql` breaks the last physics/AI tick into SQL steps plus table scan stats (`./scripts/world-debug.ps1 -Sql`)
- `GET /world/debug/backend` is JVM / tick health: heap, skipped ticks, last Java errors (`./scripts/world-debug.ps1 -Backend`)

## Economics

| Job | Harvests | Patch biome | Cap |
|-----|----------|-------------|-----|
| `LUMBERJACK` | `WOOD` | forest | 80 |
| `MINER` | `GOLD` | mine | 70 |
| `FARMER` | `FOOD` | farm | 90 |
| `STONECUTTER` | `STONE` | quarry | 75 |
| `PROSPECTOR` | `IRON` | vein | 65 |
| `HERBALIST` | `HERBS` | meadow | 55 |
| `TRADER` | — | buys low / sells high between cities | — |

Gatherers travel between **resource patches** and the nearest **city**:

1. Empty inventory → a stocked matching patch that can fill the inventory, spreading across that patch’s tiles when other workers are already headed there
2. Harvest 1/tick until inventory is full (capacity 10) or the tile is depleted
3. Full inventory → nearest city and **sell** into that city’s market
4. Repeat

Each city has a **market listing** per resource. Price is `basePrice * (targetStock / (actualStock + 1))`. Selling raises stock and lowers price; traders buying does the reverse.

Traders:

1. Empty → the city where a resource is cheapest (and another city is more expensive)
2. Buy 1/tick until the pack is full
3. Travel to the city where that resource is most expensive
4. Sell, then look for a new price gap
