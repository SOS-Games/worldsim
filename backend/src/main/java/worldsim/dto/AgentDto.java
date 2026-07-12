package worldsim.dto;

import worldsim.Agent;
import worldsim.Tile;

import java.util.ArrayList;
import java.util.List;

public record AgentDto(
        long id,
        String name,
        CoordDto location,
        double speed,
        CoordDto targetLocation,
        List<CoordDto> path) {
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
        return new AgentDto(
                agent.id,
                agent.name,
                CoordDto.from(agent.location),
                agent.speed,
                target,
                path);
    }

    private static boolean coordsEqual(CoordDto a, CoordDto b) {
        return Math.abs(a.x() - b.x()) < 0.001 && Math.abs(a.y() - b.y()) < 0.001;
    }
}
