# Architecture

```
┌─────────────────┐     REST (poll)      ┌──────────────────────────────┐
│  React + PixiJS │ ◄──────────────────► │  Quarkus backend (Java 21)   │
│  localhost:5173 │                      │  localhost:8080              │
└─────────────────┘                      └──────────────┬───────────────┘
                                                        │
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

## Navigation

Pathfinding runs **in the database**; movement logic runs **in Java**:

- **PostgreSQL** — `routing_edges` graph is built from the tile grid; `pgr_dijkstra()` computes shortest paths around impassable terrain (mountains).
- **Java** — `SimulationEngine` ticks every second; `BehaviorService` chooses goals (resource vs city); agents move one tile along their path each tick.

## Economics

| Job | Harvests |
|-----|----------|
| `LUMBERJACK` | `WOOD` |
| `MINER` | `GOLD` |
| `TRADER` | `FOOD` |

Agents travel between **resource patches** and a central **city**:

1. Empty inventory → path to the nearest tile with a matching resource
2. Stand on that tile and harvest 1 unit/tick until inventory is full (capacity 10) or the tile is depleted
3. Full inventory → path to the nearest city tile and deposit (clear inventory)
4. Repeat

Depleted resource tiles clear their type and return to grass color. The city is a 3×3 blue block just south of the mountain pass.
