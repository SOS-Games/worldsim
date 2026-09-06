package worldsim.dto;

public record SqlTableStatDto(
        String table,
        long seqScan,
        long idxScan,
        long updates,
        long inserts,
        long liveRows) {}
