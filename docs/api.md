# API

The frontend polls the world snapshot every second.

## `GET /world/state`

Returns the full world snapshot:

```json
{
  "tiles": [
    {
      "id": 1,
      "x": 2,
      "y": 2,
      "terrainType": "grass",
      "location": { "x": 2.5, "y": 2.5 },
      "resourceType": "WOOD",
      "quantity": 20
    }
  ],
  "agents": [
    {
      "id": 1,
      "name": "Walker",
      "job": "LUMBERJACK",
      "inventory": { "WOOD": 3 },
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

### Tile fields

| Field | Description |
|-------|-------------|
| `terrainType` | e.g. `grass`, `mountain` |
| `location` | Tile center as `{ x, y }` |
| `resourceType` | `WOOD`, `GOLD`, `FOOD`, or `null` |
| `quantity` | Remaining resource units |

### Agent fields

| Field | Description |
|-------|-------------|
| `job` | `LUMBERJACK`, `MINER`, or `TRADER` |
| `inventory` | Map of resource type → count |
| `targetLocation` | Current destination |
| `path` | Planned route including current position and destination |
