# Worldsim

A tile-based world simulation where NPCs navigate a grid using PostgreSQL pathfinding, with a React + PixiJS frontend that renders the map in real time.

## Stack

| Layer | Tech |
|-------|------|
| **Database** | PostgreSQL 18, PostGIS, pgRouting |
| **Backend** | Quarkus 3.37, Hibernate Spatial, Panache, Flyway |
| **Frontend** | React 19, Vite 8, PixiJS 8, TypeScript |

## What's implemented

- 20×20 tile grid with a mountain barrier and a central **city**
- Five NPCs pathfinding with pgRouting between resources and the city
- Economic loop: gather job resources → fill inventory (10) → deliver to city → repeat
- PixiJS viewer with path overlay toggle, resource-tinted tiles, and city tiles

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

On first startup, Flyway runs migrations and the app seeds the map, routing graph, resources, and agents. Quarkus can take about a minute to become ready — the UI shows a connection indicator until `/world/state` responds.

### 3. Start the frontend

```bash
cd ui
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173).

## Docs

| Doc | Contents |
|-----|----------|
| [Architecture](docs/architecture.md) | System diagram, navigation & economics model |
| [Database](docs/database.md) | Connection settings, migrations, configuration |
| [API](docs/api.md) | REST endpoints and response shapes |
| [Project structure](docs/project-structure.md) | Repository layout and key files |

## Roadmap

1. **WebSockets** — push state updates instead of polling
2. **City stockpiles** — track deposited resources in the city instead of discarding them
