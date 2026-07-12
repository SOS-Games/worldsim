package worldsim.dto;

import java.util.List;

public record WorldStateDto(List<TileDto> tiles, List<AgentDto> agents) {}
