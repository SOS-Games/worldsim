package worldsim.dto;

import java.util.List;

public record TickStatsDto(
        long moveMs,
        long replanMs,
        int agents,
        int walking,
        int harvesting,
        int buying,
        int depositing,
        int needRoute,
        int moved,
        int harvested,
        int delivered,
        int bought,
        int routed,
        int regenerated,
        List<SqlStepDto> physicsSql,
        List<SqlStepDto> aiSql) {}
