package worldsim.dto;

import java.util.List;

public record BackendDebugDto(
        String runtime,
        long pid,
        long uptimeSec,
        long heapUsedMb,
        long heapMaxMb,
        int threads,
        long gcCount,
        long gcMs,
        boolean worldActive,
        int liveClients,
        TickSideDto physics,
        TickSideDto ai,
        String lastPhysicsError,
        String lastAiError,
        List<String> hints) {}
