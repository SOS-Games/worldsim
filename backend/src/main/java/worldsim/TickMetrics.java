package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import worldsim.dto.SqlStepDto;
import worldsim.dto.TickSideDto;
import worldsim.dto.TickStatsDto;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class TickMetrics {
    private static final int RECENT = 12;
    private static final long SKIP_GAP_MS = 1500;

    private volatile long moveMs;
    private volatile long replanMs;
    private volatile int agents;
    private volatile int walking;
    private volatile int harvesting;
    private volatile int buying;
    private volatile int depositing;
    private volatile int needRoute;
    private volatile int moved;
    private volatile int harvested;
    private volatile int delivered;
    private volatile int bought;
    private volatile int routed;
    private volatile int regenerated;
    private volatile List<SqlStepDto> physicsSql = List.of();
    private volatile List<SqlStepDto> aiSql = List.of();
    private volatile List<SqlStepDto> physicsJava = List.of();
    private volatile List<SqlStepDto> aiJava = List.of();

    private volatile long lastPhysicsWallMs;
    private volatile long lastAiWallMs;
    private volatile int physicsTicks;
    private volatile int aiTicks;
    private volatile int physicsSlow;
    private volatile int aiSlow;
    private volatile int physicsSkipped;
    private volatile int aiSkipped;
    private volatile String lastPhysicsError;
    private volatile String lastAiError;

    private final long[] physicsRecent = new long[RECENT];
    private final long[] aiRecent = new long[RECENT];
    private int physicsRecentCount;
    private int physicsRecentIdx;
    private int aiRecentCount;
    private int aiRecentIdx;

    public void recordPhysics(
            long durationMs,
            PhysicsService.Census census,
            int moved,
            int harvested,
            int delivered,
            int bought,
            int regenerated,
            List<SqlStepDto> sql,
            List<SqlStepDto> java) {
        this.moveMs = durationMs;
        this.agents = census.agents();
        this.walking = census.walking();
        this.harvesting = census.harvesting();
        this.buying = census.buying();
        this.depositing = census.depositing();
        this.needRoute = census.needRoute();
        this.moved = moved;
        this.harvested = harvested;
        this.delivered = delivered;
        this.bought = bought;
        this.regenerated = regenerated;
        this.physicsSql = sql == null ? List.of() : List.copyOf(sql);
        this.physicsJava = java == null ? List.of() : List.copyOf(java);
        notePhysics(durationMs);
    }

    public synchronized void addPhysicsJava(SqlStepDto step) {
        List<SqlStepDto> next = new ArrayList<>(physicsJava);
        next.add(step);
        physicsJava = List.copyOf(next);
    }

    public void recordReplan(long durationMs, int routed, List<SqlStepDto> sql, List<SqlStepDto> java) {
        this.replanMs = durationMs;
        this.routed = routed;
        this.aiSql = sql == null ? List.of() : List.copyOf(sql);
        this.aiJava = java == null ? List.of() : List.copyOf(java);
        noteAi(durationMs);
    }

    public void recordPhysicsError(Throwable error) {
        lastPhysicsError = compact(error);
    }

    public void recordAiError(Throwable error) {
        lastAiError = compact(error);
    }

    public List<SqlStepDto> physicsSql() {
        return physicsSql;
    }

    public List<SqlStepDto> aiSql() {
        return aiSql;
    }

    public String lastPhysicsError() {
        return lastPhysicsError;
    }

    public String lastAiError() {
        return lastAiError;
    }

    public synchronized TickSideDto physicsSide() {
        return side(moveMs, physicsSql, physicsJava, lastPhysicsWallMs, physicsTicks, physicsSlow, physicsSkipped, physicsRecent, physicsRecentCount, physicsRecentIdx);
    }

    public synchronized TickSideDto aiSide() {
        return side(replanMs, aiSql, aiJava, lastAiWallMs, aiTicks, aiSlow, aiSkipped, aiRecent, aiRecentCount, aiRecentIdx);
    }

    public TickStatsDto toDto() {
        return new TickStatsDto(
                moveMs,
                replanMs,
                agents,
                walking,
                harvesting,
                buying,
                depositing,
                needRoute,
                moved,
                harvested,
                delivered,
                bought,
                routed,
                regenerated,
                physicsSql,
                aiSql);
    }

    private synchronized void notePhysics(long durationMs) {
        physicsTicks++;
        if (durationMs > 900) {
            physicsSlow++;
        }
        physicsSkipped += skippedSince(lastPhysicsWallMs);
        lastPhysicsWallMs = System.currentTimeMillis();
        physicsRecent[physicsRecentIdx] = durationMs;
        physicsRecentIdx = (physicsRecentIdx + 1) % RECENT;
        if (physicsRecentCount < RECENT) {
            physicsRecentCount++;
        }
    }

    private synchronized void noteAi(long durationMs) {
        aiTicks++;
        if (durationMs > 900) {
            aiSlow++;
        }
        aiSkipped += skippedSince(lastAiWallMs);
        lastAiWallMs = System.currentTimeMillis();
        aiRecent[aiRecentIdx] = durationMs;
        aiRecentIdx = (aiRecentIdx + 1) % RECENT;
        if (aiRecentCount < RECENT) {
            aiRecentCount++;
        }
    }

    private static int skippedSince(long lastWallMs) {
        if (lastWallMs <= 0) {
            return 0;
        }
        long gap = System.currentTimeMillis() - lastWallMs;
        if (gap <= SKIP_GAP_MS) {
            return 0;
        }
        return Math.max(1, (int) (gap / 1000) - 1);
    }

    private TickSideDto side(
            long lastMs,
            List<SqlStepDto> sql,
            List<SqlStepDto> java,
            long lastWallMs,
            int ticks,
            int slow,
            int skipped,
            long[] recent,
            int recentCount,
            int recentIdx) {
        long sqlMs = sum(sql);
        long ageMs = lastWallMs <= 0 ? 0 : Math.max(0, System.currentTimeMillis() - lastWallMs);
        return new TickSideDto(
                lastMs,
                sqlMs,
                Math.max(0, lastMs - sqlMs),
                ageMs,
                ticks,
                slow,
                skipped,
                copyRecent(recent, recentCount, recentIdx),
                java);
    }

    private static long sum(List<SqlStepDto> steps) {
        long total = 0;
        for (SqlStepDto step : steps) {
            total += step.ms();
        }
        return total;
    }

    private static List<Long> copyRecent(long[] recent, int count, int idx) {
        if (count == 0) {
            return List.of();
        }
        List<Long> out = new ArrayList<>(count);
        int start = count < RECENT ? 0 : idx;
        for (int i = 0; i < count; i++) {
            out.add(recent[(start + i) % RECENT]);
        }
        return List.copyOf(out);
    }

    private static String compact(Throwable error) {
        if (error == null) {
            return null;
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String text = root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        text = text.replace('\n', ' ').trim();
        return text.length() > 220 ? text.substring(0, 217) + "..." : text;
    }
}
