package worldsim.dto;

import java.util.List;
import java.util.Map;

public record DebugDto(
        TickStatsDto tick,
        Map<String, Integer> jobs,
        List<StuckAgentDto> needRouteSample,
        List<String> markets,
        List<String> hints) {}
