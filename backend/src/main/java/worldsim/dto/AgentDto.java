package worldsim.dto;

import worldsim.Agent;
import worldsim.ResourceType;
import worldsim.Tile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgentDto(
        long id,
        String name,
        CoordDto location,
        double speed,
        CoordDto targetLocation,
        List<CoordDto> path,
        String job,
        Map<String, Integer> inventory) {
    public static AgentDto from(Agent agent) {
        List<CoordDto> path = new ArrayList<>();
        if (agent.location != null) {
            path.add(CoordDto.from(agent.location));
        }
        for (Tile tile : agent.currentPath) {
            path.add(CoordDto.from(tile.location));
        }
        CoordDto target = agent.targetLocation != null ? CoordDto.from(agent.targetLocation) : null;
        if (target != null && (path.isEmpty() || !coordsEqual(path.get(path.size() - 1), target))) {
            path.add(target);
        }

        Map<String, Integer> inventoryJson = new LinkedHashMap<>();
        if (agent.inventory != null) {
            for (Map.Entry<ResourceType, Integer> entry : agent.inventory.entrySet()) {
                inventoryJson.put(entry.getKey().name(), entry.getValue());
            }
        }

        return new AgentDto(
                agent.id,
                agent.name,
                CoordDto.from(agent.location),
                agent.speed,
                target,
                path,
                agent.job != null ? agent.job.name() : null,
                inventoryJson);
    }

    private static boolean coordsEqual(CoordDto a, CoordDto b) {
        return Math.abs(a.x() - b.x()) < 0.001 && Math.abs(a.y() - b.y()) < 0.001;
    }
}
