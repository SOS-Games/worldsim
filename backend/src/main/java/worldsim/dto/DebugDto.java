package worldsim.dto;

import java.util.List;
import java.util.Map;

public record DebugDto(
        TickStatsDto tick,
        Map<String, Integer> jobs,
        Map<String, Integer> terrain,
        int mountainRanges,
        List<Integer> mountainSizes,
        int waterBodies,
        List<Integer> waterSizes,
        int lakeBasins,
        int canalRuns,
        List<StuckAgentDto> needRouteSample,
        List<String> markets,
        List<String> hints) {}
