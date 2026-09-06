package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import worldsim.dto.DebugDto;
import worldsim.dto.SqlStepDto;
import worldsim.dto.StuckAgentDto;
import worldsim.dto.TickStatsDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DebugService {

    private static final int SAMPLE_LIMIT = 12;

    @Inject
    EntityManager entityManager;

    @Inject
    TickMetrics tickMetrics;

    @Transactional
    public DebugDto snapshot() {
        TickStatsDto tick = tickMetrics.toDto();
        return new DebugDto(tick, jobCounts(), needRouteSample(), marketLines(), hints(tick));
    }

    public String formatText(DebugDto debug) {
        TickStatsDto t = debug.tick();
        StringBuilder out = new StringBuilder();
        out.append("worldsim debug\n");
        out.append(String.format(
                "timing   move %dms   replan %dms%n", t.moveMs(), t.replanMs()));
        out.append(String.format(
                "now      walk %d   harvest %d   buy %d   sell %d   need-route %d   (of %d)%n",
                t.walking(), t.harvesting(), t.buying(), t.depositing(), t.needRoute(), t.agents()));
        out.append(String.format(
                "tick     stepped %d   gathered %d   bought %d   sold %d   new-routes %d   regen-tiles %d%n",
                t.moved(), t.harvested(), t.bought(), t.delivered(), t.routed(), t.regenerated()));
        appendSqlLine(out, "physics", t.physicsSql());
        appendSqlLine(out, "ai", t.aiSql());
        out.append("jobs     ");
        debug.jobs().forEach((job, count) -> out.append(job).append('=').append(count).append(' '));
        out.append('\n');
        if (!debug.markets().isEmpty()) {
            out.append("markets\n");
            for (String line : debug.markets()) {
                out.append("  ").append(line).append('\n');
            }
        }
        if (!debug.hints().isEmpty()) {
            out.append("hints\n");
            for (String hint : debug.hints()) {
                out.append("  - ").append(hint).append('\n');
            }
        }
        if (!debug.needRouteSample().isEmpty()) {
            out.append("need-route sample (standing still, waiting for AI)\n");
            for (StuckAgentDto agent : debug.needRouteSample()) {
                out.append(String.format(
                        "  #%d %s %s at (%d,%d) %s carried=%d target=%s path=%d%n",
                        agent.id(),
                        agent.name(),
                        agent.job() == null ? "?" : agent.job(),
                        agent.x(),
                        agent.y(),
                        tileLabel(agent),
                        agent.carried(),
                        agent.targetX() == null ? "-" : ("(" + agent.targetX() + "," + agent.targetY() + ")"),
                        agent.pathLen()));
            }
        }
        return out.toString();
    }

    private static void appendSqlLine(StringBuilder out, String label, List<SqlStepDto> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        out.append("sql ").append(label);
        for (SqlStepDto step : steps) {
            out.append("   ").append(step.name()).append('=').append(step.ms()).append("ms");
        }
        out.append('\n');
    }

    private static String tileLabel(StuckAgentDto agent) {
        if (agent.tile() == null) {
            return "unknown";
        }
        if (agent.resource() != null) {
            return agent.tile() + "/" + agent.resource();
        }
        return agent.tile();
    }

    private List<String> hints(TickStatsDto tick) {
        List<String> hints = new ArrayList<>();
        if (tick.moveMs() > 900) {
            hints.add("Physics tick exceeded 900ms; overlapping ticks will be skipped.");
        }
        if (tick.replanMs() > 900) {
            hints.add("AI/pathfinding tick exceeded 900ms; overlapping AI ticks will be skipped.");
        }
        if (tick.needRoute() > WorldConfig.MAX_AI_PER_TICK) {
            hints.add("need-route is high; NPCs finished a job faster than AI can assign new routes.");
        }
        if (tick.harvesting() + tick.buying() + tick.depositing() == 0
                && tick.walking() == 0
                && tick.agents() > 0) {
            hints.add("Nobody is walking or working; check pathfinding, markets, and resource patches.");
        }
        return hints;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> jobCounts() {
        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT job, COUNT(*)::int FROM agent GROUP BY job ORDER BY job")
                .getResultList();
        Map<String, Integer> jobs = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String job = row[0] == null ? "NONE" : row[0].toString();
            jobs.put(job, ((Number) row[1]).intValue());
        }
        return jobs;
    }

    private List<String> marketLines() {
        List<String> lines = new ArrayList<>();
        for (City city : City.all()) {
            List<Market> listings = city.listings;
            if (listings == null || listings.isEmpty()) {
                listings = Market.list("city", city);
            }
            Market hottest = null;
            int total = 0;
            for (Market listing : listings) {
                total += listing.stock;
                if (hottest == null || listing.currentPrice() > hottest.currentPrice()) {
                    hottest = listing;
                }
            }
            if (hottest == null) {
                lines.add(city.name + "  empty");
            } else {
                lines.add(String.format(
                        "%s  stock=%d  dearest %s @ %.1f",
                        city.name,
                        total,
                        hottest.resourceType.name(),
                        hottest.currentPrice()));
            }
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private List<StuckAgentDto> needRouteSample() {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT a.id,
                               a.name,
                               a.job,
                               FLOOR(ST_X(a.location))::int,
                               FLOOR(ST_Y(a.location))::int,
                               t.terraintype,
                               t.resourcetype,
                               COALESCE(inv.carried, 0)::int,
                               CASE WHEN a.targetlocation IS NULL THEN NULL ELSE FLOOR(ST_X(a.targetlocation))::int END,
                               CASE WHEN a.targetlocation IS NULL THEN NULL ELSE FLOOR(ST_Y(a.targetlocation))::int END,
                               CASE
                                   WHEN jsonb_typeof(a.currentpath) = 'array' THEN jsonb_array_length(a.currentpath)
                                   ELSE 0
                               END
                        FROM agent a
                        LEFT JOIN tile t
                          ON t.x = FLOOR(ST_X(a.location))::int
                         AND t.y = FLOOR(ST_Y(a.location))::int
                        LEFT JOIN (
                            SELECT agent_id, SUM(quantity) AS carried
                            FROM agent_inventory
                            GROUP BY agent_id
                        ) inv ON inv.agent_id = a.id
                        WHERE NOT (
                                a.job IS NOT NULL
                            AND t.resourcetype IS NOT NULL
                            AND t.quantity > 0
                            AND COALESCE(inv.carried, 0) < :capacity
                            AND %s
                        )
                          AND NOT (t.terraintype = 'city' AND COALESCE(inv.carried, 0) > 0)
                          AND NOT (a.job = 'TRADER' AND t.terraintype = 'city')
                          AND (
                                a.currentpath IS NULL
                             OR jsonb_typeof(a.currentpath) <> 'array'
                             OR jsonb_array_length(a.currentpath) = 0
                          )
                        ORDER BY a.id
                        """.formatted(Job.sqlHarvestMatch("a.job", "t.resourcetype")))
                .setParameter("capacity", BehaviorService.INVENTORY_CAPACITY)
                .setMaxResults(SAMPLE_LIMIT)
                .getResultList();
        List<StuckAgentDto> sample = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            sample.add(new StuckAgentDto(
                    ((Number) row[0]).longValue(),
                    row[1] == null ? "" : row[1].toString(),
                    row[2] == null ? null : row[2].toString(),
                    ((Number) row[3]).intValue(),
                    ((Number) row[4]).intValue(),
                    row[5] == null ? null : row[5].toString(),
                    row[6] == null ? null : row[6].toString(),
                    ((Number) row[7]).intValue(),
                    row[8] == null ? null : ((Number) row[8]).intValue(),
                    row[9] == null ? null : ((Number) row[9]).intValue(),
                    ((Number) row[10]).intValue()));
        }
        return sample;
    }
}
