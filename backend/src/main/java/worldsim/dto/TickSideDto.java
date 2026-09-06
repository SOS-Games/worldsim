package worldsim.dto;

import java.util.List;

public record TickSideDto(
        long lastMs,
        long sqlMs,
        long javaMs,
        long ageMs,
        int ticks,
        int slow,
        int skipped,
        List<Long> recentMs,
        List<SqlStepDto> javaSteps) {}
