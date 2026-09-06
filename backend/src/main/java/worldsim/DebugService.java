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
        Map<String, Integer> terrain = terrainCounts();
        Landform landform = landform(terrain);
        return new DebugDto(
                tick,
                jobCounts(),
                terrain,
                landform.mountainRanges(),
                landform.mountainSizes(),
                landform.waterBodies(),
                landform.waterSizes(),
                landform.lakeBasins(),
                landform.canalRuns(),
                needRouteSample(),
                marketLines(),
                hints(tick, landform, terrain));
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
        out.append("terrain  ");
        debug.terrain().forEach((name, count) -> out.append(name).append('=').append(count).append(' '));
        out.append('\n');
        out.append(String.format(
                "landform mountain-ranges=%d sizes=%s%n",
                debug.mountainRanges(),
                joinSizes(debug.mountainSizes())));
        out.append(String.format(
                "         water-networks=%d sizes=%s   lakes=%d   canal-runs=%d%n",
                debug.waterBodies(),
                joinSizes(debug.waterSizes()),
                debug.lakeBasins(),
                debug.canalRuns()));
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

    private List<String> hints(TickStatsDto tick, Landform landform, Map<String, Integer> terrain) {
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
        int mountains = terrain.getOrDefault("mountain", 0);
        if (mountains < WorldConfig.MIN_MOUNTAIN_TILES) {
            hints.add("Few mountain tiles (" + mountains + "); regen should grow more ranges.");
        }
        long largeRanges = landform.mountainSizes().stream()
                .filter(size -> size >= WorldConfig.LARGE_MOUNTAIN_SIZE)
                .count();
        if (largeRanges < WorldConfig.MIN_LARGE_MOUNTAIN_RANGES) {
            hints.add("Only " + largeRanges + " sizable mountain ranges; map may look too flat.");
        }
        if (landform.lakeBasins() > WorldConfig.MAX_SEPARATE_LAKES) {
            hints.add("Many separate lakes (" + landform.lakeBasins()
                    + "); rivers may not be linking water patches.");
        }
        if (landform.canalRuns() > WorldConfig.MAX_CANAL_RUNS) {
            hints.add("Long straight water canals (" + landform.canalRuns()
                    + "); rivers should meander instead of cutting the map.");
        }
        return hints;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> terrainCounts() {
        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT terraintype, COUNT(*)::int FROM tile GROUP BY terraintype ORDER BY terraintype")
                .getResultList();
        Map<String, Integer> terrain = new LinkedHashMap<>();
        for (Object[] row : rows) {
            terrain.put(row[0].toString(), ((Number) row[1]).intValue());
        }
        return terrain;
    }

    @SuppressWarnings("unchecked")
    private Landform landform(Map<String, Integer> terrain) {
        if (terrain.getOrDefault("mountain", 0) == 0 && terrain.getOrDefault("water", 0) == 0) {
            return new Landform(0, List.of(), 0, List.of(), 0, 0);
        }
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT x, y, terraintype
                        FROM tile
                        WHERE terraintype IN ('mountain', 'water')
                        """)
                .getResultList();
        List<Integer> mountains = blobSizes(rows, "mountain");
        List<Integer> waters = blobSizes(rows, "water");
        return new Landform(
                (int) mountains.stream().filter(size -> size >= 5).count(),
                mountains.stream().filter(size -> size >= 5).limit(8).toList(),
                (int) waters.stream().filter(size -> size >= 5).count(),
                waters.stream().filter(size -> size >= 5).limit(8).toList(),
                fatBlobs(rows, "water", 3, 8),
                canalRuns(rows));
    }

    private static List<Integer> blobSizes(List<Object[]> rows, String type) {
        java.util.Set<Long> cells = new java.util.HashSet<>();
        for (Object[] row : rows) {
            if (type.equals(row[2].toString())) {
                cells.add((((long) ((Number) row[0]).intValue()) << 32)
                        ^ (((Number) row[1]).intValue() & 0xffffffffL));
            }
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<Integer> sizes = new ArrayList<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (long cell : cells) {
            if (!seen.add(cell)) {
                continue;
            }
            int size = 1;
            java.util.ArrayDeque<Long> q = new java.util.ArrayDeque<>();
            q.add(cell);
            while (!q.isEmpty()) {
                long cur = q.removeFirst();
                int x = (int) (cur >> 32);
                int y = (int) cur;
                for (int[] dir : dirs) {
                    long next = (((long) (x + dir[0])) << 32) ^ ((y + dir[1]) & 0xffffffffL);
                    if (cells.contains(next) && seen.add(next)) {
                        q.add(next);
                        size++;
                    }
                }
            }
            sizes.add(size);
        }
        sizes.sort(java.util.Comparator.reverseOrder());
        return sizes;
    }

    private static int fatBlobs(List<Object[]> rows, String type, int minNeighbors, int minSize) {
        java.util.Set<Long> cells = new java.util.HashSet<>();
        for (Object[] row : rows) {
            if (type.equals(row[2].toString())) {
                cells.add((((long) ((Number) row[0]).intValue()) << 32)
                        ^ (((Number) row[1]).intValue() & 0xffffffffL));
            }
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        java.util.Set<Long> fat = new java.util.HashSet<>();
        for (long cell : cells) {
            int x = (int) (cell >> 32);
            int y = (int) cell;
            int neighbors = 0;
            for (int[] dir : dirs) {
                long next = (((long) (x + dir[0])) << 32) ^ ((y + dir[1]) & 0xffffffffL);
                if (cells.contains(next)) {
                    neighbors++;
                }
            }
            if (neighbors >= minNeighbors) {
                fat.add(cell);
            }
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        int blobs = 0;
        for (long cell : fat) {
            if (!seen.add(cell)) {
                continue;
            }
            int size = 1;
            java.util.ArrayDeque<Long> q = new java.util.ArrayDeque<>();
            q.add(cell);
            while (!q.isEmpty()) {
                long cur = q.removeFirst();
                int x = (int) (cur >> 32);
                int y = (int) cur;
                for (int[] dir : dirs) {
                    long next = (((long) (x + dir[0])) << 32) ^ ((y + dir[1]) & 0xffffffffL);
                    if (fat.contains(next) && seen.add(next)) {
                        q.add(next);
                        size++;
                    }
                }
            }
            if (size >= minSize) {
                blobs++;
            }
        }
        return blobs;
    }

    private static int canalRuns(List<Object[]> rows) {
        boolean[][] water = new boolean[WorldConfig.MAP_WIDTH][WorldConfig.MAP_HEIGHT];
        for (Object[] row : rows) {
            if (!"water".equals(row[2].toString())) {
                continue;
            }
            int x = ((Number) row[0]).intValue();
            int y = ((Number) row[1]).intValue();
            if (x >= 0 && y >= 0 && x < water.length && y < water[0].length) {
                water[x][y] = true;
            }
        }
        return MapService.thinCanalRuns(water, WorldConfig.CANAL_RUN_LENGTH);
    }

    private static String joinSizes(List<Integer> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sizes.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(sizes.get(i));
        }
        return out.toString();
    }

    private record Landform(
            int mountainRanges,
            List<Integer> mountainSizes,
            int waterBodies,
            List<Integer> waterSizes,
            int lakeBasins,
            int canalRuns) {}

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
                            AND COALESCE(inv.carried, 0) < %s
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
                        """.formatted(
                                BehaviorService.capacitySql("a"),
                                Job.sqlHarvestMatch("a.job", "t.resourcetype")))
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
