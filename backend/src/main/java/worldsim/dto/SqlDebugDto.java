package worldsim.dto;

import java.util.List;

public record SqlDebugDto(
        List<SqlStepDto> physics,
        List<SqlStepDto> ai,
        List<SqlTableStatDto> tables,
        List<String> statements,
        List<String> hints) {}
