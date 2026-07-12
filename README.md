# Worldsim

A tile-based world simulation where NPCs navigate a grid using PostgreSQL pathfinding, with a React + PixiJS frontend that renders the map in real time.

## Architecture

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

### How navigation works

Pathfinding runs **in the database**; movement logic runs **in Java**:

- **PostgreSQL** — `routing_edges` graph is built from the tile grid; `pgr_dijkstra()` computes shortest paths around impassable terrain (mountains).
- **Java** — `SimulationEngine` ticks every second, replans when a path is empty, moves agents one tile along their path, and picks the next waypoint on arrival.

## What's implemented

- 20×20 tile grid with a mountain barrier (agents route around it)
- Five NPCs (Walker, Scout, Trader, Guard, Ranger) with distinct start/destination pairs
- pgRouting-based pathfinding via `PathfindingService`
- REST API exposing world state, agent paths, and destinations
- PixiJS renderer with colored agents, path lines, and destination markers
- Toggle button to show/hide paths

## Prerequisites

- **Java 21**
- **Node.js** (for the frontend)
- **Docker** (for PostgreSQL)
- **Quarkus CLI** or the Maven wrapper in `backend/`

## Quick start

### 1. Start the database

```bash
cd db
docker compose up -d
```

Uses the `pgrouting/pgrouting:18-3.6-4.0` image (PostGIS + pgRouting preinstalled).

### 2. Start the backend

```bash
cd backend
quarkus dev
# or: ./mvnw quarkus:dev
```

On first startup, Flyway runs migrations and the app seeds a 20×20 map, builds the routing graph, and spawns five agents.

### 3. Start the frontend

```bash
cd ui
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173).

## API

### `GET /world/state`

Returns the full world snapshot:

```json
{
  "tiles": [
    { "id": 1, "x": 0, "y": 0, "terrainType": "grass", "location": { "x": 0.5, "y": 0.5 } }
  ],
  "agents": [
    {
      "id": 1,
      "name": "Walker",
      "location": { "x": 0.5, "y": 0.5 },
      "speed": 1.0,
      "targetLocation": { "x": 19.5, "y": 19.5 },
      "path": [
        { "x": 0.5, "y": 0.5 },
        { "x": 1.5, "y": 0.5 },
        { "x": 19.5, "y": 19.5 }
      ]
    }
  ]
}
```

The frontend polls this endpoint every second.

## Project structure

```
worldsim/
├── backend/          # Quarkus simulation server
│   └── src/main/java/worldsim/
│       ├── Agent.java              # NPC entity (location, path, target)
│       ├── Tile.java               # Grid tile with PostGIS Point
│       ├── MapService.java         # Grid generation, agent spawning
│       ├── RoutingGraphService.java # Builds routing_edges from tiles
│       ├── PathfindingService.java  # Calls pgr_dijkstra()
│       ├── SimulationEngine.java   # 1s tick loop
│       ├── WorldResource.java      # REST API
│       └── dto/                    # JSON serialization
├── ui/               # React + PixiJS viewer
│   └── src/
│       ├── Viewport.tsx            # Map renderer, path overlay
│       └── App.tsx                 # Path toggle button
└── db/
    └── docker-compose.yml          # PostgreSQL + pgRouting container
```

## Database

| Setting | Value |
|---------|-------|
| Host | `localhost:5432` |
| Database | `simulation_db` |
| User | `postgres` |
| Password | `pass` |

Flyway migrations live in `backend/src/main/resources/db/migration/`:

- `V1.0.0__init_postgis.sql` — enables PostGIS
- `V2.0.0__pgrouting.sql` — enables pgRouting, creates `routing_edges`

Hibernate auto-updates entity tables (`Tile`, `Agent`, etc.) on startup.

## Configuration

Backend settings in `backend/src/main/resources/application.properties`:

- PostGIS dialect for Hibernate Spatial
- CORS enabled for `http://localhost:5173`
- Flyway migrations run at startup

## Roadmap

Planned next steps for the simulation:

1. **Economics** — resources on tiles, inventories and jobs on agents
2. **WebSockets** — push state updates instead of polling
3. **Behavior AI** — city vs. wilderness logic, goal-oriented decisions based on inventory
