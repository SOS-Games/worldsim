# API

## `GET /world/map`

Load once when the viewer starts. Returns map size plus **non-grass** tiles (water, mountains, cities, biome patches, roads) so a 200×200 world stays compact.

```json
{
  "width": 200,
  "height": 200,
  "tiles": [
    {
      "id": 1,
      "x": 15,
      "y": 15,
      "terrainType": "city",
      "infrastructureType": "none",
      "location": { "x": 15.5, "y": 15.5 },
      "resourceType": null,
      "quantity": 0,
      "cityId": 1
    }
  ]
}
```

## `GET /world/state`

Richer agent snapshot for tooltips and the path overlay. The viewer loads this less often than live positions.

| Query | Default | Description |
|-------|---------|-------------|
| `paths` | `false` | When `true`, includes each agent’s planned path (heavier; used by the path overlay) |

```json
{
  "tick": {
    "moveMs": 42,
    "replanMs": 380,
    "agents": 120,
    "walking": 70,
    "harvesting": 38,
    "buying": 4,
    "depositing": 0,
    "needRoute": 12,
    "moved": 70,
    "harvested": 38,
    "delivered": 5,
    "bought": 4,
    "routed": 16,
    "regenerated": 0
  },
  "cities": [
    {
      "id": 1,
      "name": "Hillford",
      "x": 15,
      "y": 15,
      "listings": [{ "resource": "WOOD", "stock": 12, "price": 16.7 }]
    }
  ],
  "agents": [
    {
      "id": 1,
      "name": "Worker-1",
      "job": "LUMBERJACK",
      "tradeResource": null,
      "vehicleType": null,
      "inventory": { "WOOD": 3 },
      "location": { "x": 10.5, "y": 12.5 },
      "speed": 1.0,
      "targetLocation": { "x": 15.5, "y": 15.5 },
      "path": []
    }
  ]
}
```

### Agent fields

| Field | Description |
|-------|-------------|
| `job` | `LUMBERJACK`, `MINER`, `FARMER`, `STONECUTTER`, `PROSPECTOR`, `HERBALIST`, or `TRADER` |
| `tradeResource` | Resource a trader is buying or selling |
| `inventory` | Map of resource type → count |
| `targetLocation` | Current destination |
| `path` | Planned route (only when `?paths=true`) |

`GET /world/state` also includes `cities` with market stock and current prices (`basePrice * targetStock / (stock + 1)`). Hover a city in the viewer to see them.

## `WS /world/live`

Pushed after every physics tick. Agent entries are **id + x/y only**. A small `tick` object is included for the HUD.

`walking` / `harvesting` / `buying` / `depositing` / `needRoute` are **what NPCs are doing right now**. `moved` / `harvested` / `delivered` / `bought` / `routed` are **how many did that this tick**. `routed` is the AI budget used this second (new routes computed), not “how many NPCs have a path”.

## `GET /world/debug/backend`

JVM and tick health for the Quarkus process (not Postgres). JSON by default; send `Accept: text/plain` for a readable dump.

```powershell
./scripts/world-debug.ps1 -Backend
./scripts/world-debug.ps1 -Backend -Json
```

```json
{
  "runtime": "Java 21.0.8",
  "pid": 12345,
  "uptimeSec": 420,
  "heapUsedMb": 180,
  "heapMaxMb": 512,
  "threads": 42,
  "gcCount": 12,
  "gcMs": 80,
  "worldActive": true,
  "liveClients": 1,
  "physics": {
    "lastMs": 18,
    "sqlMs": 14,
    "javaMs": 4,
    "ageMs": 220,
    "ticks": 400,
    "slow": 0,
    "skipped": 0,
    "recentMs": [16, 18, 15],
    "javaSteps": [{ "name": "broadcast", "ms": 1, "rows": 1 }]
  },
  "ai": {
    "lastMs": 40,
    "sqlMs": 36,
    "javaMs": 4,
    "ageMs": 180,
    "ticks": 400,
    "slow": 0,
    "skipped": 0,
    "recentMs": [38, 41, 36],
    "javaSteps": [{ "name": "persist", "ms": 2, "rows": 10 }]
  },
  "lastPhysicsError": null,
  "lastAiError": null,
  "hints": []
}
```

`skipped` counts scheduled ticks that never ran because the previous one was still going (`SKIP`). A rising skip count with `ageMs` over ~1500 usually means a second backend is bound to `:8080`, or a tick took more than one second.

## `GET /world/debug/sql`

Per-statement timings from the last physics and AI ticks, plus Postgres table scan counts. JSON by default; send `Accept: text/plain` for a readable dump. `pg_stat_statements` is included when that extension is installed.

```powershell
./scripts/world-debug.ps1 -Sql
./scripts/world-debug.ps1 -Sql -Json
```

```json
{
  "physics": [
    { "name": "sell", "ms": 2, "rows": 4 },
    { "name": "buy", "ms": 1, "rows": 1 },
    { "name": "harvest", "ms": 3, "rows": 18 },
    { "name": "walk", "ms": 8, "rows": 70 },
    { "name": "census", "ms": 4, "rows": 240 }
  ],
  "ai": [
    { "name": "candidates", "ms": 6, "rows": 64 },
    { "name": "goal-index", "ms": 8, "rows": 42 },
    { "name": "goals", "ms": 1, "rows": 16 },
    { "name": "routes", "ms": 22, "rows": 16 }
  ],
  "tables": [
    { "table": "agent_inventory", "seqScan": 1200, "idxScan": 800, "updates": 400, "inserts": 50, "liveRows": 180 }
  ],
  "statements": [],
  "hints": []
}
```

## `GET /world/debug`

Snapshot for a CLI or another agent. JSON by default; send `Accept: text/plain` for a readable dump.

From the repo root:

```powershell
./scripts/world-debug.ps1
./scripts/world-debug.ps1 -Json
./scripts/world-debug.ps1 -Sql
./scripts/world-debug.ps1 -Backend
```

```json
{
  "tick": {
    "moveMs": 42,
    "replanMs": 380,
    "agents": 120,
    "walking": 70,
    "harvesting": 38,
    "buying": 4,
    "depositing": 0,
    "needRoute": 12,
    "moved": 70,
    "harvested": 38,
    "delivered": 5,
    "bought": 4,
    "routed": 16,
    "regenerated": 0
  },
  "jobs": { "FARMER": 17, "HERBALIST": 17, "LUMBERJACK": 18, "MINER": 17, "PROSPECTOR": 17, "STONECUTTER": 17, "TRADER": 17 },
  "needRouteSample": [
    {
      "id": 51,
      "name": "Worker-51",
      "job": "LUMBERJACK",
      "x": 84,
      "y": 83,
      "tile": "city",
      "resource": null,
      "carried": 0,
      "targetX": 88,
      "targetY": 75,
      "pathLen": 0
    }
  ],
  "hints": ["need-route is high; NPCs finished a job faster than AI can assign new routes."]
}
```
