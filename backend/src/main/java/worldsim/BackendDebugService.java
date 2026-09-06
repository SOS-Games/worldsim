package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import worldsim.dto.BackendDebugDto;
import worldsim.dto.SqlStepDto;
import worldsim.dto.TickSideDto;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class BackendDebugService {

    @Inject
    TickMetrics tickMetrics;

    @Inject
    WorldRuntime worldRuntime;

    @Inject
    WorldWebSocket worldWebSocket;

    public BackendDebugDto snapshot() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long gcCount = 0;
        long gcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) {
                gcCount += gc.getCollectionCount();
            }
            if (gc.getCollectionTime() > 0) {
                gcMs += gc.getCollectionTime();
            }
        }
        TickSideDto physics = tickMetrics.physicsSide();
        TickSideDto ai = tickMetrics.aiSide();
        String physicsError = tickMetrics.lastPhysicsError();
        String aiError = tickMetrics.lastAiError();
        BackendDebugDto debug = new BackendDebugDto(
                "Java " + System.getProperty("java.version"),
                ProcessHandle.current().pid(),
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                heap.getUsed() / (1024 * 1024),
                heap.getMax() / (1024 * 1024),
                ManagementFactory.getThreadMXBean().getThreadCount(),
                gcCount,
                gcMs,
                worldRuntime.isActive(),
                worldWebSocket.clientCount(),
                physics,
                ai,
                physicsError,
                aiError,
                hints(worldRuntime.isActive(), worldWebSocket.clientCount(), heap, physics, ai, physicsError, aiError));
        return debug;
    }

    public String formatText(BackendDebugDto debug) {
        StringBuilder out = new StringBuilder();
        out.append("worldsim backend\n");
        out.append(String.format(
                "runtime  %s  pid=%d  up=%ds%n", debug.runtime(), debug.pid(), debug.uptimeSec()));
        out.append(String.format(
                "heap     %d / %d MB   gc=%d (%dms)   threads=%d%n",
                debug.heapUsedMb(),
                debug.heapMaxMb(),
                debug.gcCount(),
                debug.gcMs(),
                debug.threads()));
        out.append(String.format(
                "world    %s   live-ws=%d%n",
                debug.worldActive() ? "active" : "idle",
                debug.liveClients()));
        appendSide(out, "physics", debug.physics());
        appendSide(out, "ai", debug.ai());
        if (debug.lastPhysicsError() != null) {
            out.append("error physics  ").append(debug.lastPhysicsError()).append('\n');
        }
        if (debug.lastAiError() != null) {
            out.append("error ai       ").append(debug.lastAiError()).append('\n');
        }
        if (!debug.hints().isEmpty()) {
            out.append("hints\n");
            for (String hint : debug.hints()) {
                out.append("  - ").append(hint).append('\n');
            }
        }
        return out.toString();
    }

    private static void appendSide(StringBuilder out, String name, TickSideDto side) {
        out.append(String.format(
                "%-8s last=%dms  sql=%dms  java=%dms  age=%dms  ticks=%d  slow=%d  skipped=%d%n",
                name,
                side.lastMs(),
                side.sqlMs(),
                side.javaMs(),
                side.ageMs(),
                side.ticks(),
                side.slow(),
                side.skipped()));
        if (!side.recentMs().isEmpty()) {
            out.append("         recent ");
            for (int i = 0; i < side.recentMs().size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(side.recentMs().get(i));
            }
            out.append(" ms\n");
        }
        for (SqlStepDto step : side.javaSteps()) {
            out.append(String.format("         %-12s %4dms  n=%d%n", step.name(), step.ms(), step.rows()));
        }
    }

    private static List<String> hints(
            boolean active,
            int liveClients,
            MemoryUsage heap,
            TickSideDto physics,
            TickSideDto ai,
            String physicsError,
            String aiError) {
        List<String> hints = new ArrayList<>();
        if (!active) {
            hints.add("World is not active; physics and AI ticks are paused.");
        }
        if (liveClients == 0) {
            hints.add("No live WebSocket clients; the viewer is not connected.");
        }
        if (physics.ageMs() > 1500) {
            hints.add("Physics has not finished a tick in "
                    + physics.ageMs()
                    + "ms. Overlapping ticks are skipped — often a second backend on :8080, or a tick over 1s.");
        }
        if (ai.ageMs() > 1500) {
            hints.add("AI has not finished a tick in "
                    + ai.ageMs()
                    + "ms. Overlapping AI ticks are skipped.");
        }
        if (physics.skipped() > 0) {
            hints.add("Physics skipped " + physics.skipped() + " scheduled tick(s) since startup.");
        }
        if (ai.skipped() > 0) {
            hints.add("AI skipped " + ai.skipped() + " scheduled tick(s) since startup.");
        }
        if (physics.lastMs() > 900) {
            hints.add("Last physics tick was " + physics.lastMs() + "ms; the next one will be skipped.");
        }
        if (ai.lastMs() > 900) {
            hints.add("Last AI tick was " + ai.lastMs() + "ms; the next one will be skipped.");
        }
        if (physics.javaMs() > physics.sqlMs() && physics.javaMs() >= 20) {
            hints.add("Physics spent more time in Java ("
                    + physics.javaMs()
                    + "ms) than SQL ("
                    + physics.sqlMs()
                    + "ms).");
        }
        if (ai.javaMs() > ai.sqlMs() && ai.javaMs() >= 20) {
            hints.add("AI spent more time in Java (" + ai.javaMs() + "ms) than SQL (" + ai.sqlMs() + "ms).");
        }
        if (heap.getMax() > 0 && heap.getUsed() > heap.getMax() * 0.85) {
            hints.add("Heap is over 85% full; GC may hitch ticks.");
        }
        if (physicsError != null) {
            hints.add("Last physics error: " + physicsError);
        }
        if (aiError != null) {
            hints.add("Last AI error: " + aiError);
        }
        return hints;
    }
}
