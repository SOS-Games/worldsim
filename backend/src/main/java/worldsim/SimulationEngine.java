package worldsim;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.transaction.Transactional;

import worldsim.dto.SqlStepDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class SimulationEngine {

    private static final int AI_CANDIDATE_LOOKAHEAD = WorldConfig.MAX_AI_PER_TICK * 4;
    private static final String DEST_JOB_HARVESTS = Job.sqlHarvestMatch("a.job", "dest.resourcetype");

    @Inject
    PathfindingService pathfindingService;

    @Inject
    BehaviorService behaviorService;

    @Inject
    WorldRuntime worldRuntime;

    @Inject
    TickMetrics tickMetrics;

    @Inject
    PhysicsService physicsService;

    @Inject
    WorldWebSocket worldWebSocket;

    @Inject
    EntityManager entityManager;

    private int physicsTickCount;
    private long aiCursorId;

    /**
     * Bulk physics in Postgres: deliver, harvest, step every path by one waypoint.
     */
    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP, identity = "sim-physics")
    @RunOnVirtualThread
    @Transactional
    public void physicsTick() {
        if (!worldRuntime.isActive()) {
            return;
        }

        long started = System.nanoTime();
        physicsTickCount++;
        boolean regenerate = physicsTickCount % WorldConfig.REGEN_EVERY_TICKS == 0;
        try {
            PhysicsService.StepStats stats = physicsService.step(regenerate);
            long posStarted = System.nanoTime();
            var positions = physicsService.agentPositions();
            long positionsMs = (System.nanoTime() - posStarted) / 1_000_000;
            tickMetrics.recordPhysics(
                    (System.nanoTime() - started) / 1_000_000,
                    stats.census(),
                    stats.moved(),
                    stats.harvested(),
                    stats.delivered(),
                    stats.bought(),
                    stats.regenerated(),
                    stats.sql(),
                    List.of(new SqlStepDto("positions", positionsMs, positions.size())));
            long sendStarted = System.nanoTime();
            worldWebSocket.broadcastPositions(positions);
            tickMetrics.addPhysicsJava(new SqlStepDto(
                    "broadcast",
                    (System.nanoTime() - sendStarted) / 1_000_000,
                    worldWebSocket.clientCount()));
        } catch (RuntimeException e) {
            tickMetrics.recordPhysicsError(e);
            throw e;
        }
    }

    /**
     * Time-sliced AI: goal selection + pgRouting for a rotating subset of agents.
     */
    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP, identity = "sim-ai")
    @RunOnVirtualThread
    @Transactional
    public void aiTick() {
        if (!worldRuntime.isActive()) {
            return;
        }

        long started = System.nanoTime();
        entityManager.setFlushMode(FlushModeType.COMMIT);
        List<SqlStepDto> sql = new ArrayList<>();
        List<SqlStepDto> java = new ArrayList<>();
        int replanned = 0;
        long lastSeen = aiCursorId;

        try {
            long stepStarted = System.nanoTime();
            List<Agent> candidates = nextAiCandidates();
        sql.add(new SqlStepDto("candidates", (System.nanoTime() - stepStarted) / 1_000_000, candidates.size()));

        List<Agent> routing = new ArrayList<>();
        List<PathfindingService.PathPair> pairs = new ArrayList<>();
        List<MovementMode> modes = new ArrayList<>();
        Map<Long, Vehicle> vehicles = Vehicle.all().stream()
                .filter(vehicle -> vehicle.id != null)
                .collect(Collectors.toMap(vehicle -> vehicle.id, vehicle -> vehicle, (a, b) -> a));

        stepStarted = System.nanoTime();
        int patches = behaviorService.beginTick();
        sql.add(new SqlStepDto("goal-index", (System.nanoTime() - stepStarted) / 1_000_000, patches));
        try {
            stepStarted = System.nanoTime();
            for (Agent agent : candidates) {
                lastSeen = agent.id;
                if (!worldRuntime.isActive() || routing.size() >= WorldConfig.MAX_AI_PER_TICK) {
                    break;
                }
                if (!behaviorService.needsRoute(agent)) {
                    continue;
                }
                behaviorService.assignGoal(agent);
                Long startId = behaviorService.tileId(agent.location);
                Long goalId = behaviorService.tileId(agent.targetLocation);
                if (startId == null || goalId == null || startId.equals(goalId)) {
                    agent.persist();
                    continue;
                }
                routing.add(agent);
                pairs.add(new PathfindingService.PathPair(startId, goalId));
                modes.add(PathfindingService.modeOf(agent, vehicles));
            }
            sql.add(new SqlStepDto("goals", (System.nanoTime() - stepStarted) / 1_000_000, routing.size()));

            try {
                stepStarted = System.nanoTime();
                int routed = 0;
                for (MovementMode mode : MovementMode.values()) {
                    List<PathfindingService.PathPair> subset = new ArrayList<>();
                    for (int i = 0; i < routing.size(); i++) {
                        if (modes.get(i) == mode) {
                            subset.add(pairs.get(i));
                        }
                    }
                    if (subset.isEmpty()) {
                        continue;
                    }
                    Map<PathfindingService.PathPair, List<PathPoint>> paths =
                            pathfindingService.findPaths(subset, mode);
                    routed += paths.size();
                    for (int i = 0; i < routing.size(); i++) {
                        if (modes.get(i) != mode) {
                            continue;
                        }
                        applyPath(routing.get(i), paths.getOrDefault(pairs.get(i), List.of()));
                        routing.get(i).persist();
                        replanned++;
                    }
                }
                sql.add(new SqlStepDto("routes", (System.nanoTime() - stepStarted) / 1_000_000, routed));
            } catch (IllegalStateException e) {
                tickMetrics.recordAiError(e);
            }
        } finally {
            behaviorService.endTick();
        }

        aiCursorId = lastSeen;
        tickMetrics.recordReplan((System.nanoTime() - started) / 1_000_000, replanned, sql, java);
        } catch (RuntimeException e) {
            tickMetrics.recordAiError(e);
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Agent> nextAiCandidates() {
        List<Number> ids = entityManager.createNativeQuery("""
                        SELECT a.id
                        FROM agent a
                        LEFT JOIN tile dest
                          ON dest.x = FLOOR(ST_X(a.targetlocation))::int
                         AND dest.y = FLOOR(ST_Y(a.targetlocation))::int
                        LEFT JOIN (
                            SELECT agent_id, SUM(quantity) AS carried
                            FROM agent_inventory
                            GROUP BY agent_id
                        ) inv ON inv.agent_id = a.id
                        WHERE a.job IS NOT NULL
                          AND (
                                a.currentpath IS NULL
                             OR jsonb_typeof(a.currentpath) <> 'array'
                             OR jsonb_array_length(a.currentpath) = 0
                             OR (
                                    a.job IS DISTINCT FROM 'TRADER'
                                AND inv.carried >= %s
                                AND (dest.id IS NULL OR dest.terraintype <> 'city')
                                )
                             OR (
                                    a.job IS DISTINCT FROM 'TRADER'
                                AND inv.carried < %s
                                AND (
                                        dest.id IS NULL
                                     OR dest.terraintype = 'city'
                                     OR dest.resourcetype IS NULL
                                     OR dest.quantity < (%s - inv.carried)
                                     OR NOT %s
                                    )
                                )
                             OR (
                                    a.job = 'TRADER'
                                AND (dest.id IS NULL OR dest.terraintype <> 'city')
                                )
                          )
                        ORDER BY (a.id <= :cursor), a.id
                        LIMIT :limit
                        """.formatted(
                                BehaviorService.capacitySql("a"),
                                BehaviorService.capacitySql("a"),
                                BehaviorService.capacitySql("a"),
                                DEST_JOB_HARVESTS))
                .setParameter("cursor", aiCursorId)
                .setParameter("limit", AI_CANDIDATE_LOOKAHEAD)
                .getResultList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Long> ordered = ids.stream().map(Number::longValue).toList();
        Map<Long, Agent> loaded = entityManager
                .createQuery("from Agent a where a.id in :ids", Agent.class)
                .setParameter("ids", ordered)
                .getResultList()
                .stream()
                .collect(Collectors.toMap(agent -> agent.id, agent -> agent));
        List<Agent> agents = new ArrayList<>(ordered.size());
        for (Long id : ordered) {
            Agent agent = loaded.get(id);
            if (agent != null) {
                agents.add(agent);
            }
        }
        return agents;
    }

    private static void applyPath(Agent agent, List<PathPoint> routed) {
        List<PathPoint> path = new ArrayList<>(routed);
        if (!path.isEmpty() && agent.location != null) {
            PathPoint first = path.get(0);
            if (Math.abs(first.x() - agent.location.getX()) < 0.001
                    && Math.abs(first.y() - agent.location.getY()) < 0.001) {
                path.remove(0);
            }
        }
        agent.currentPath = path;
    }
}
