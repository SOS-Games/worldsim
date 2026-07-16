# Project structure

```
worldsim/
├── backend/          # Quarkus simulation server
│   └── src/main/java/worldsim/
│       ├── Agent.java              # NPC entity (location, path, job, inventory)
│       ├── Tile.java               # Grid tile with PostGIS Point + resources
│       ├── ResourceType.java       # WOOD, GOLD, FOOD
│       ├── Job.java                # LUMBERJACK, MINER, TRADER
│       ├── MapService.java         # Grid generation, city/resource/agent seeding
│       ├── BehaviorService.java    # Resource vs city goal selection
│       ├── RoutingGraphService.java # Builds routing_edges from tiles
│       ├── PathfindingService.java  # Calls pgr_dijkstra()
│       ├── SimulationEngine.java   # 1s tick: move, harvest, deliver
│       ├── WorldResource.java      # REST API
│       └── dto/                    # JSON serialization
├── ui/               # React + PixiJS viewer
│   └── src/
│       ├── Viewport.tsx            # Map renderer, path overlay
│       └── App.tsx                 # Path toggle button
├── db/
│   └── docker-compose.yml          # PostgreSQL + pgRouting (Podman Compose)
└── docs/             # Detailed documentation
```
