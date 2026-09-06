package worldsim.dto;

import java.util.List;

/** Compact live frame: agent entries are id + coordinates only. */
public record LiveFrameDto(List<AgentPosDto> agents, TickStatsDto tick) {}
