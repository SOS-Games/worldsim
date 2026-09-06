package worldsim.dto;

import java.util.List;

public record WorldStateDto(
        List<AgentDto> agents, TickStatsDto tick, List<CityDto> cities, List<VehicleDto> vehicles) {}
