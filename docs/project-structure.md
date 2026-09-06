# Project structure

```
worldsim/
├── backend/          # Quarkus simulation server
│   └── src/main/java/worldsim/
│       ├── Agent.java              # NPC entity (location, path, job, inventory)
│       ├── Tile.java               # Grid tile: terrain, infrastructure, PostGIS point, resources
│       ├── TerrainType.java        # water, grass, mountain, city, forest, farm, mine, quarry, vein, meadow
│       ├── InfrastructureType.java # none, road, bridge, village, harbor
│       ├── Vehicle.java            # Boat or wagon an agent can enter
│       ├── PerlinNoise.java        # 2D noise for biomes and resource veins
│       ├── ResourceType.java       # WOOD, GOLD, FOOD, STONE, IRON, HERBS
│       ├── City.java               # Named city with a map center
│       ├── Market.java             # Per-city stock + base price for each resource
│       ├── Job.java                # lumberjack, miner, farmer, stonecutter, prospector, herbalist, trader
│       ├── WorldConfig.java        # Map size / agent count
│       ├── MapService.java         # Grid generation, city/resource/agent seeding
│       ├── BehaviorService.java    # Resource vs city goal selection
│       ├── GoalIndex.java          # One-tick in-memory patches/claims/markets for AI
│       ├── RoutingGraphService.java # Builds routing_edges from tiles
│       ├── PathfindingService.java  # Calls pgr_dijkstra()
│       ├── PhysicsService.java     # Bulk SQL: move, harvest, deliver, regen
│       ├── SimulationEngine.java   # physicsTick (DB) + time-sliced aiTick
│       ├── TickMetrics.java        # Last tick durations and activity census
│       ├── DebugService.java       # /world/debug snapshot
│       ├── SqlDebugService.java    # /world/debug/sql timings + table stats
│       ├── BackendDebugService.java # /world/debug/backend JVM + tick skips
│       ├── WorldResource.java      # REST API (/map + /state + /debug)
│       ├── WorldWebSocket.java     # Live agent positions (/world/live)
│       └── dto/                    # JSON serialization
├── ui/               # React + PixiJS viewer
│   └── src/
│       └── Viewport.tsx            # Map renderer, WebSocket positions, path overlay
│       └── App.tsx                 # HUD: connection, tick timings, path toggle
├── scripts/
│   └── world-debug.ps1             # curl-style debug dump for CLI / agents
├── db/
│   └── docker-compose.yml          # PostgreSQL + pgRouting (Podman Compose)
└── docs/             # Detailed documentation
```
