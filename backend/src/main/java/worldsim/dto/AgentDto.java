package worldsim.dto;

import worldsim.Agent;
import worldsim.PathPoint;
import worldsim.ResourceType;

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
        String tradeResource,
        String vehicleType,
        int capacity,
        Map<String, Integer> inventory) {

    public static AgentDto from(Agent agent, boolean includePath) {
        return from(agent, includePath, null);
    }

    public static AgentDto from(Agent agent, boolean includePath, String vehicleType) {
        CoordDto target = agent.targetLocation != null ? CoordDto.from(agent.targetLocation) : null;

        List<CoordDto> path = List.of();
        if (includePath) {
            path = new ArrayList<>();
            if (agent.currentPath != null) {
                for (PathPoint point : agent.currentPath) {
                    path.add(new CoordDto(point.x(), point.y()));
                }
            }
        }

        Map<String, Integer> inventoryJson = new LinkedHashMap<>();
        if (agent.inventory != null) {
            for (Map.Entry<ResourceType, Integer> entry : agent.inventory.entrySet()) {
                inventoryJson.put(entry.getKey().name(), entry.getValue());
            }
        }

        String resolvedVehicle = vehicleType;
        if (resolvedVehicle == null && agent.vehicleId != null) {
            worldsim.Vehicle vehicle = worldsim.Vehicle.findById(agent.vehicleId);
            if (vehicle != null && vehicle.type != null) {
                resolvedVehicle = vehicle.type.name();
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
                agent.tradeResource != null ? agent.tradeResource.name() : null,
                resolvedVehicle,
                agent.inventoryCapacity(),
                inventoryJson);
    }
}
