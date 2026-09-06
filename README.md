# Worldsim

A tile-based world simulation where NPCs navigate a grid using PostgreSQL pathfinding, with a React + PixiJS frontend that renders the map in real time.

## Stack

| Layer | Tech |
|-------|------|
| **Database** | PostgreSQL 18, PostGIS, pgRouting |
| **Backend** | Quarkus 3.37, Hibernate Spatial, Panache, Flyway |
| **Frontend** | React 19, Vite 8, PixiJS 8, TypeScript |

## What's implemented

- **200×200** tile world from Perlin noise: lakes, rivers from mountains to water, fields/forests, mountains, **9 cities**, resource-side **villages**, and **roads/bridges** between them
- **480 agents** across seven jobs, plus **harbor boats** and **village wagons** for travel
- Economic loop: gatherers sell into city markets; prices fall as stock rises; traders buy cheap and sell dear
- Compact APIs: map loaded once, live positions over WebSocket, richer state for tooltips/paths
- PixiJS viewer with pan/zoom, hover tooltips, and optional path overlay

## Prerequisites

- **Java 21**
- **Node.js**
- **Podman** (with Compose support)
- **Quarkus CLI** or the Maven wrapper in `backend/`

## Quick start

### 1. Start the database

```bash
cd db
podman compose up -d
```

### 2. Start the backend

```bash
cd backend
quarkus dev
# or: ./mvnw quarkus:dev
```

On first startup (or when the map isn’t 200×200 / is missing biome tiles), the world is regenerated: 40,000 tiles, routing graph, resources, cities, and 480 agents. That can take a few minutes — the UI connection indicator waits until the live WebSocket connects.

### 3. Start the frontend

```bash
cd ui
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173).

**Controls:** drag to pan, scroll to zoom, hover for tile/agent info. Path overlay is on by default. Hover HUD labels for what each number means. For a text dump: `./scripts/world-debug.ps1`. For SQL timings: `./scripts/world-debug.ps1 -Sql`. For the Java process: `./scripts/world-debug.ps1 -Backend`.

## Docs

| Doc | Contents |
|-----|----------|
| [Architecture](docs/architecture.md) | System diagram, navigation & economics model |
| [Database](docs/database.md) | Connection settings, migrations, configuration |
| [API](docs/api.md) | REST endpoints, WebSocket live feed, and response shapes |
| [Project structure](docs/project-structure.md) | Repository layout and key files |
