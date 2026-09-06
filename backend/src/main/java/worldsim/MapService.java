package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class MapService {

    private static final Logger LOG = Logger.getLogger(MapService.class);

    @Inject
    RoutingGraphService routingGraphService;

    @Inject
    BehaviorService behaviorService;

    @Inject
    PathfindingService pathfindingService;

    @Inject
    EntityManager entityManager;

    @Transactional
    public void ensureWorldReady() {
        long tiles = Tile.count();
        boolean missingBiomes = Tile.count("terrainType = ?1", TerrainType.FOREST.code()) == 0;
        boolean missingWater = Tile.count("terrainType = ?1", TerrainType.WATER.code()) == 0
                || Tile.count("terrainType = ?1", TerrainType.WATER.code()) < WorldConfig.MIN_WATER_TILES;
        boolean floodedWater = Tile.count("terrainType = ?1", TerrainType.WATER.code()) > WorldConfig.MAX_WATER_TILES;
        boolean bulkyFarms = Tile.count("terrainType = ?1", TerrainType.FARM.code()) > WorldConfig.MAX_FARM_TILES;
        boolean missingVillages = Tile.count("infrastructureType = ?1", InfrastructureType.VILLAGE.code()) == 0;
        boolean missingHarbors = Tile.count("infrastructureType = ?1", InfrastructureType.HARBOR.code()) == 0;
        boolean skinnyMountains = Tile.count("terrainType = ?1", TerrainType.MOUNTAIN.code()) < WorldConfig.MIN_MOUNTAIN_TILES;
        boolean bulkyMountains = Tile.count("terrainType = ?1", TerrainType.MOUNTAIN.code()) > WorldConfig.MAX_MOUNTAIN_TILES;
        boolean fewMountainRanges = countLargeBlobs(TerrainType.MOUNTAIN, WorldConfig.LARGE_MOUNTAIN_SIZE)
                < WorldConfig.MIN_LARGE_MOUNTAIN_RANGES;
        boolean canalRivers = countCanalRuns(WorldConfig.CANAL_RUN_LENGTH) > WorldConfig.MAX_CANAL_RUNS;
        boolean lopsidedResources = resourceSideSkewed();
        boolean oversizedLandform = largestBlob(TerrainType.MOUNTAIN) > WorldConfig.MAX_MOUNTAIN_BLOB;
        if (tiles != WorldConfig.EXPECTED_TILE_COUNT
                || missingBiomes
                || missingWater
                || floodedWater
                || bulkyFarms
                || missingVillages
                || missingHarbors
                || skinnyMountains
                || bulkyMountains
                || fewMountainRanges
                || canalRivers
                || lopsidedResources
                || oversizedLandform) {
            LOG.infof(
                    "Regenerating %dx%d world (tiles=%d oversized-landform=%s).",
                    WorldConfig.MAP_WIDTH,
                    WorldConfig.MAP_HEIGHT,
                    tiles,
                    oversizedLandform);
            clearWorld();
            generateGrid(WorldConfig.MAP_WIDTH, WorldConfig.MAP_HEIGHT);
            return;
        }
        ensureMarkets();
        if (Agent.count() < WorldConfig.AGENT_COUNT) {
            ensureAgentCount();
        }
        ensureAgentJobs();
    }

    @Transactional
    public void clearWorld() {
        entityManager.createNativeQuery("DROP TABLE IF EXISTS agent_path CASCADE").executeUpdate();
        entityManager.createNativeQuery(
                        "TRUNCATE TABLE agent_inventory, vehicle, agent, market, city, tile, routing_edges RESTART IDENTITY CASCADE")
                .executeUpdate();
        GoalIndex.clearTileIds();
    }

    @Transactional
    public void generateGrid(int width, int height) {
        Tile[][] grid = new Tile[width][height];
        PerlinNoise noise = new PerlinNoise(WorldConfig.WORLD_SEED);
        double[][] elevation = new double[width][height];
        double[][] moisture = new double[width][height];

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                elevation[x][y] = elevationAt(noise, x, y);
                moisture[x][y] = noise.fbm01(
                        x * WorldConfig.BIOME_FREQUENCY + 80,
                        y * WorldConfig.BIOME_FREQUENCY + 80,
                        4);
            }
        }
        raiseMassifs(elevation, width, height);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Tile tile = new Tile();
                tile.x = x;
                tile.y = y;
                tile.terrainType = biomeAt(elevation[x][y], moisture[x][y]).code();
                tile.infrastructureType = InfrastructureType.NONE.code();
                tile.location = GeometryFactoryHolder.createPoint(x + 0.5, y + 0.5);
                tile.refreshTraversal();
                tile.persist();
                grid[x][y] = tile;
            }
        }

        carveRivers(grid, elevation, noise, width, height);
        clearLandForCities(grid, width, height);
        placeCities(grid, width, height);
        seedResourceVeins(grid, noise, elevation);
        List<int[]> villages = placeVillages(grid, width, height);
        List<int[]> harbors = placeHarbors(grid, width, height, villages);
        List<int[]> settlements = new ArrayList<>(villages);
        settlements.addAll(harbors);
        spawnWagons(villages);
        buildRoads(grid, settlements);
        routingGraphService.rebuild();
        spawnAgents(grid, width, height, WorldConfig.AGENT_COUNT);
    }

    private static TerrainType biomeAt(double elevation, double moisture) {
        if (elevation < WorldConfig.WATER_LEVEL) {
            return TerrainType.WATER;
        }
        if (elevation > WorldConfig.MOUNTAIN_LEVEL) {
            return TerrainType.MOUNTAIN;
        }
        if (moisture > 0.55) {
            return TerrainType.FOREST;
        }
        if (moisture < 0.32) {
            return TerrainType.MEADOW;
        }
        if (moisture > 0.40 && moisture < 0.48 && elevation < 0.50) {
            return TerrainType.FARM;
        }
        return TerrainType.GRASS;
    }

    private static final int[][] OUTFLOW_DIRS = {
            {0, -1}, {1, 0}, {0, 1}, {-1, 0},
            {1, -1}, {1, 1}, {-1, 1}, {-1, -1}
    };

    /**
     * Lakes are low-elevation WATER blobs. Each mountain range sends winding rivers
     * outward; leftover lake puddles get linked so the drainage network connects.
     */
    private static void carveRivers(
            Tile[][] grid, double[][] elevation, PerlinNoise noise, int width, int height) {
        List<int[]> lakes = new ArrayList<>();
        boolean[][] lake = new boolean[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (isTerrain(grid[x][y], TerrainType.WATER)) {
                    lakes.add(new int[] {x, y});
                    lake[x][y] = true;
                }
            }
        }
        if (lakes.isEmpty()) {
            return;
        }

        Random rng = new Random(WorldConfig.WORLD_SEED ^ 0x9E3779B97F4A7C15L);
        for (List<int[]> range : clusters(grid, TerrainType.MOUNTAIN)) {
            if (range.size() < WorldConfig.MIN_MOUNTAIN_RANGE) {
                continue;
            }
            int[] center = centroid(range);
            int want = Math.min(WorldConfig.RIVERS_PER_RANGE, OUTFLOW_DIRS.length);
            for (int i = 0; i < want; i++) {
                int[] dir = OUTFLOW_DIRS[i];
                int[] start = stepOffMountain(grid, farthestInDir(range, center, dir), dir);
                int[] mouth = nearbyLake(start, dir, lakes);
                int reach = WorldConfig.RIVER_MAX_STEPS;
                if (mouth == null) {
                    mouth = new int[] {
                            clamp(start[0] + dir[0] * reach, 0, width - 1),
                            clamp(start[1] + dir[1] * reach, 0, height - 1)
                    };
                }
                carveRiver(grid, elevation, lake, start, mouth, dir, noise, rng, reach);
            }
        }
        linkLakeBasins(grid, elevation, noise, rng);
    }

    private static int[] centroid(List<int[]> tiles) {
        int cx = 0;
        int cy = 0;
        for (int[] tile : tiles) {
            cx += tile[0];
            cy += tile[1];
        }
        return new int[] {cx / tiles.size(), cy / tiles.size()};
    }

    private static int[] farthestInDir(List<int[]> range, int[] center, int[] dir) {
        int[] best = range.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (int[] tile : range) {
            int score = (tile[0] - center[0]) * dir[0] + (tile[1] - center[1]) * dir[1];
            if (score > bestScore) {
                bestScore = score;
                best = tile;
            }
        }
        return best;
    }

    private static int[] stepOffMountain(Tile[][] grid, int[] start, int[] dir) {
        int x = start[0] + dir[0];
        int y = start[1] + dir[1];
        if (x < 0 || y < 0 || x >= grid.length || y >= grid[0].length || nearCitySite(x, y)) {
            return start;
        }
        return new int[] {x, y};
    }

    private static int[] nearbyLake(int[] start, int[] dir, List<int[]> lakes) {
        int[] best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int[] lakeTile : lakes) {
            int dx = lakeTile[0] - start[0];
            int dy = lakeTile[1] - start[1];
            if (dir[0] * dx + dir[1] * dy <= 0) {
                continue;
            }
            if (Math.abs(dx) <= 2 || Math.abs(dy) <= 2) {
                continue;
            }
            int dist = manhattan(start[0], start[1], lakeTile[0], lakeTile[1]);
            if (dist > WorldConfig.RIVER_MAX_STEPS || dist >= bestDist) {
                continue;
            }
            best = lakeTile;
            bestDist = dist;
        }
        return best;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void carveRiver(
            Tile[][] grid,
            double[][] elevation,
            boolean[][] lake,
            int[] peak,
            int[] mouth,
            int[] dir,
            PerlinNoise noise,
            Random rng,
            int maxSteps) {
        int x = peak[0];
        int y = peak[1];
        boolean[][] visited = new boolean[grid.length][grid[0].length];
        int[] target = mouth;
        int lakeHits = 0;
        int[] lastDir = null;
        int straight = 0;
        for (int step = 0; step < maxSteps; step++) {
            if (nearCitySite(x, y)) {
                int[] detour = downhillStep(
                        grid, elevation, visited, x, y, target, dir, lastDir, straight, noise, rng, true);
                if (detour == null) {
                    return;
                }
                int[] moved = new int[] {detour[0] - x, detour[1] - y};
                straight = sameDir(lastDir, moved) ? straight + 1 : 1;
                lastDir = moved;
                x = detour[0];
                y = detour[1];
                continue;
            }
            boolean alreadyWater = lake[x][y] || isTerrain(grid[x][y], TerrainType.WATER);
            boolean inLakeBasin = alreadyWater && elevation[x][y] < WorldConfig.WATER_LEVEL + 0.08;
            paintWater(grid[x][y]);
            lake[x][y] = true;
            visited[x][y] = true;
            if (inLakeBasin && step > 2) {
                if (lakeHits >= 1) {
                    return;
                }
                int[] nextLake = nearestOtherWater(grid, x, y);
                int dist = nextLake == null
                        ? Integer.MAX_VALUE
                        : manhattan(x, y, nextLake[0], nextLake[1]);
                if (dist > 2 && dist <= WorldConfig.MAX_LAKE_LINK_DIST) {
                    target = nextLake;
                    lakeHits++;
                } else {
                    return;
                }
            }
            int[] next = downhillStep(
                    grid, elevation, visited, x, y, target, dir, lastDir, straight, noise, rng, false);
            if (next == null) {
                return;
            }
            int[] moved = new int[] {next[0] - x, next[1] - y};
            straight = sameDir(lastDir, moved) ? straight + 1 : 1;
            lastDir = moved;
            x = next[0];
            y = next[1];
        }
    }

    private static boolean sameDir(int[] a, int[] b) {
        return a != null && b != null && a[0] == b[0] && a[1] == b[1];
    }

    private static void linkLakeBasins(Tile[][] grid, double[][] elevation, PerlinNoise noise, Random rng) {
        for (int n = 0; n < WorldConfig.MAX_LAKE_LINKS; n++) {
            List<List<int[]>> basins = clustersMasked(naturalLakeMask(grid, elevation));
            basins.removeIf(body -> body.size() < 8);
            if (basins.size() < 2) {
                return;
            }
            int[][] pair = closestBodyPairBeyond(basins, 1, WorldConfig.MAX_LAKE_LINK_DIST);
            if (pair == null) {
                return;
            }
            boolean[][] lake = waterMask(grid);
            int[] dir = {
                    Integer.signum(pair[1][0] - pair[0][0]),
                    Integer.signum(pair[1][1] - pair[0][1])
            };
            if (dir[0] == 0 && dir[1] == 0) {
                dir = new int[] {1, 0};
            }
            carveRiver(grid, elevation, lake, pair[0], pair[1], dir, noise, rng, WorldConfig.MAX_LAKE_LINK_DIST);
        }
    }

    private static boolean[][] naturalLakeMask(Tile[][] grid, double[][] elevation) {
        boolean[][] mask = new boolean[grid.length][grid[0].length];
        for (int x = 0; x < grid.length; x++) {
            for (int y = 0; y < grid[0].length; y++) {
                mask[x][y] = isTerrain(grid[x][y], TerrainType.WATER)
                        && elevation[x][y] < WorldConfig.WATER_LEVEL + 0.04;
            }
        }
        return mask;
    }

    private static List<List<int[]>> clustersMasked(boolean[][] mask) {
        int width = mask.length;
        int height = mask[0].length;
        boolean[][] seen = new boolean[width][height];
        List<List<int[]>> blobs = new ArrayList<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (seen[x][y] || !mask[x][y]) {
                    continue;
                }
                List<int[]> blob = new ArrayList<>();
                ArrayDeque<int[]> q = new ArrayDeque<>();
                q.add(new int[] {x, y});
                seen[x][y] = true;
                while (!q.isEmpty()) {
                    int[] cur = q.removeFirst();
                    blob.add(cur);
                    for (int[] dir : dirs) {
                        int nx = cur[0] + dir[0];
                        int ny = cur[1] + dir[1];
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height || seen[nx][ny]) {
                            continue;
                        }
                        if (!mask[nx][ny]) {
                            continue;
                        }
                        seen[nx][ny] = true;
                        q.add(new int[] {nx, ny});
                    }
                }
                blobs.add(blob);
            }
        }
        return blobs;
    }

    private static int[][] closestBodyPairBeyond(List<List<int[]>> bodies, int minDist, int maxDist) {
        int[][] best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < bodies.size(); i++) {
            for (int j = i + 1; j < bodies.size(); j++) {
                int[][] pair = nearestPair(bodies.get(i), bodies.get(j));
                if (pair == null) {
                    continue;
                }
                int dist = manhattan(pair[0][0], pair[0][1], pair[1][0], pair[1][1]);
                if (dist <= minDist || dist > maxDist || dist >= bestDist) {
                    continue;
                }
                bestDist = dist;
                best = pair;
            }
        }
        return best;
    }

    private static int[][] nearestPair(List<int[]> from, List<int[]> to) {
        int[] bestFrom = null;
        int[] bestTo = null;
        int best = Integer.MAX_VALUE;
        for (int[] a : from) {
            for (int[] b : to) {
                int dist = manhattan(a[0], a[1], b[0], b[1]);
                if (dist < best) {
                    best = dist;
                    bestFrom = a;
                    bestTo = b;
                }
            }
        }
        if (bestFrom == null) {
            return null;
        }
        return new int[][] {bestFrom, bestTo};
    }

    private static boolean[][] waterMask(Tile[][] grid) {
        boolean[][] lake = new boolean[grid.length][grid[0].length];
        for (int x = 0; x < grid.length; x++) {
            for (int y = 0; y < grid[0].length; y++) {
                lake[x][y] = isTerrain(grid[x][y], TerrainType.WATER);
            }
        }
        return lake;
    }

    private static int[] nearestOtherWater(Tile[][] grid, int x, int y) {
        int width = grid.length;
        int height = grid[0].length;
        boolean[][] same = new boolean[width][height];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[] {x, y});
        same[x][y] = true;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] cur = q.removeFirst();
            for (int[] dir : dirs) {
                int nx = cur[0] + dir[0];
                int ny = cur[1] + dir[1];
                if (nx < 0 || ny < 0 || nx >= width || ny >= height || same[nx][ny]) {
                    continue;
                }
                if (!isTerrain(grid[nx][ny], TerrainType.WATER)) {
                    continue;
                }
                same[nx][ny] = true;
                q.add(new int[] {nx, ny});
            }
        }
        int[] best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int wx = 0; wx < width; wx++) {
            for (int wy = 0; wy < height; wy++) {
                if (same[wx][wy] || !isTerrain(grid[wx][wy], TerrainType.WATER)) {
                    continue;
                }
                int dist = manhattan(x, y, wx, wy);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = new int[] {wx, wy};
                }
            }
        }
        return best;
    }

    private static List<List<int[]>> clusters(Tile[][] grid, TerrainType type) {
        int width = grid.length;
        int height = grid[0].length;
        boolean[][] seen = new boolean[width][height];
        List<List<int[]>> blobs = new ArrayList<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (seen[x][y] || !isTerrain(grid[x][y], type)) {
                    continue;
                }
                List<int[]> blob = new ArrayList<>();
                ArrayDeque<int[]> q = new ArrayDeque<>();
                q.add(new int[] {x, y});
                seen[x][y] = true;
                while (!q.isEmpty()) {
                    int[] cur = q.removeFirst();
                    blob.add(cur);
                    for (int[] dir : dirs) {
                        int nx = cur[0] + dir[0];
                        int ny = cur[1] + dir[1];
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height || seen[nx][ny]) {
                            continue;
                        }
                        if (!isTerrain(grid[nx][ny], type)) {
                            continue;
                        }
                        seen[nx][ny] = true;
                        q.add(new int[] {nx, ny});
                    }
                }
                blobs.add(blob);
            }
        }
        return blobs;
    }

    private static int[] downhillStep(
            Tile[][] grid,
            double[][] elevation,
            boolean[][] visited,
            int x,
            int y,
            int[] mouth,
            int[] flow,
            int[] lastDir,
            int straight,
            PerlinNoise noise,
            Random rng,
            boolean avoidCities) {
        int[] pick = riverStep(
                grid, elevation, visited, x, y, mouth, flow, lastDir, straight, noise, rng, avoidCities, true);
        if (pick != null) {
            return pick;
        }
        return riverStep(
                grid, elevation, visited, x, y, mouth, flow, lastDir, straight, noise, rng, avoidCities, false);
    }

    private static int[] riverStep(
            Tile[][] grid,
            double[][] elevation,
            boolean[][] visited,
            int x,
            int y,
            int[] mouth,
            int[] flow,
            int[] lastDir,
            int straight,
            PerlinNoise noise,
            Random rng,
            boolean avoidCities,
            boolean forbidStraight) {
        double angle = noise.fbm01(x * 0.13 + 3, y * 0.13 + 11, 3) * Math.PI * 6;
        double fx = Math.cos(angle);
        double fy = Math.sin(angle);
        List<int[]> choices = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] step : dirs) {
            int nx = x + step[0];
            int ny = y + step[1];
            if (nx < 0 || ny < 0 || nx >= grid.length || ny >= grid[0].length) {
                continue;
            }
            if (visited[nx][ny]) {
                continue;
            }
            if (avoidCities && nearCitySite(nx, ny)) {
                continue;
            }
            boolean continuing = sameDir(lastDir, step);
            if (forbidStraight && continuing && straight >= WorldConfig.RIVER_STRAIGHT_LIMIT) {
                continue;
            }
            double score = elevation[nx][ny]
                    + rng.nextDouble() * 0.10
                    + 0.006 * manhattan(nx, ny, mouth[0], mouth[1])
                    - 0.04 * (step[0] * flow[0] + step[1] * flow[1])
                    - 0.16 * (step[0] * fx + step[1] * fy)
                    + (continuing ? 0.22 * straight : 0);
            choices.add(new int[] {nx, ny});
            scores.add(score);
        }
        if (choices.isEmpty()) {
            return null;
        }
        int best = 0;
        int second = 0;
        for (int i = 1; i < scores.size(); i++) {
            if (scores.get(i) < scores.get(best)) {
                second = best;
                best = i;
            } else if (scores.get(i) < scores.get(second)) {
                second = i;
            }
        }
        if (second != best && rng.nextDouble() < 0.45) {
            return choices.get(second);
        }
        return choices.get(best);
    }

    private static int manhattan(int ax, int ay, int bx, int by) {
        return Math.abs(ax - bx) + Math.abs(ay - by);
    }

    private static boolean nearCitySite(int x, int y) {
        int pad = WorldConfig.CITY_RADIUS + 2;
        for (int[] center : WorldConfig.CITY_CENTERS) {
            if (Math.abs(center[0] - x) <= pad && Math.abs(center[1] - y) <= pad) {
                return true;
            }
        }
        return false;
    }

    private static void paintWater(Tile tile) {
        if (tile.isCity()) {
            return;
        }
        tile.terrainType = TerrainType.WATER.code();
        tile.resourceType = null;
        tile.quantity = 0;
        tile.infrastructureType = InfrastructureType.NONE.code();
        tile.refreshTraversal();
        tile.persist();
    }

    /** Keep the fixed city sites on land so pathing and GoalIndex stay aligned. */
    private static void clearLandForCities(Tile[][] grid, int width, int height) {
        int pad = WorldConfig.CITY_RADIUS + 2;
        for (int[] center : WorldConfig.CITY_CENTERS) {
            for (int x = center[0] - pad; x <= center[0] + pad; x++) {
                for (int y = center[1] - pad; y <= center[1] + pad; y++) {
                    if (x < 0 || y < 0 || x >= width || y >= height) {
                        continue;
                    }
                    Tile tile = grid[x][y];
                    if (tile.isCity()) {
                        continue;
                    }
                    tile.terrainType = TerrainType.GRASS.code();
                    tile.resourceType = null;
                    tile.quantity = 0;
                    tile.infrastructureType = InfrastructureType.NONE.code();
                    tile.refreshTraversal();
                    tile.persist();
                }
            }
        }
    }

    private static void seedResourceVeins(Tile[][] grid, PerlinNoise noise, double[][] elevation) {
        deposit(grid, noise, ResourceType.WOOD, 0.09, 10, 4, 0.58, areaScaled(80), areaScaled(220),
                tile -> isTerrain(tile, TerrainType.FOREST));
        deposit(grid, noise, ResourceType.FOOD, 0.1, 40, 9, 0.6, areaScaled(70), areaScaled(180),
                tile -> isTerrain(tile, TerrainType.FARM));
        deposit(grid, noise, ResourceType.HERBS, 0.11, 70, 14, 0.6, areaScaled(50), areaScaled(140),
                tile -> isTerrain(tile, TerrainType.MEADOW));
        deposit(grid, noise, ResourceType.STONE, 0.12, 110, 6, 0.62, areaScaled(50), areaScaled(140), tile ->
                foothill(tile, elevation));
        deposit(grid, noise, ResourceType.GOLD, 0.14, 150, 2, 0.64, areaScaled(40), areaScaled(120), tile ->
                foothill(tile, elevation));
        deposit(grid, noise, ResourceType.IRON, 0.13, 190, 11, 0.62, areaScaled(40), areaScaled(120), tile ->
                foothill(tile, elevation) || isTerrain(tile, TerrainType.FOREST));
    }

    private static int areaScaled(int tilesOnHundred) {
        return Math.max(tilesOnHundred, tilesOnHundred * WorldConfig.EXPECTED_TILE_COUNT / 10_000);
    }

    private static boolean foothill(Tile tile, double[][] elevation) {
        if (!tile.isPassable() || tile.isCity()) {
            return false;
        }
        double e = elevation[tile.x][tile.y];
        return e >= WorldConfig.MOUNTAIN_LEVEL - 0.12 && e <= WorldConfig.MOUNTAIN_LEVEL;
    }

    private static boolean isTerrain(Tile tile, TerrainType type) {
        return type.code().equals(tile.terrainType);
    }

    private static void deposit(
            Tile[][] grid,
            PerlinNoise noise,
            ResourceType type,
            double scale,
            double originX,
            double originY,
            double threshold,
            int minTiles,
            int maxTiles,
            java.util.function.Predicate<Tile> allow) {
        int placed = paintDeposits(grid, noise, type, scale, originX, originY, threshold, maxTiles, allow);
        double relaxed = threshold;
        while (placed < minTiles && relaxed > 0.42) {
            relaxed -= 0.06;
            placed += paintDeposits(grid, noise, type, scale, originX, originY, relaxed, maxTiles - placed, allow);
        }
    }

    private static int paintDeposits(
            Tile[][] grid,
            PerlinNoise noise,
            ResourceType type,
            double scale,
            double originX,
            double originY,
            double threshold,
            int maxTiles,
            java.util.function.Predicate<Tile> allow) {
        if (maxTiles <= 0) {
            return 0;
        }
        int width = grid.length;
        int height = grid[0].length;
        double[][] vein = new double[width][height];
        boolean[][] ok = new boolean[width][height];
        List<int[]> ranked = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Tile tile = grid[x][y];
                if (tile.resourceType != null || tile.isCity() || !tile.isPassable() || !allow.test(tile)) {
                    continue;
                }
                double value = noise.fbm01(tile.x * scale + originX, tile.y * scale + originY, 3);
                if (value < threshold) {
                    continue;
                }
                vein[x][y] = value;
                ok[x][y] = true;
                ranked.add(new int[] {x, y});
            }
        }
        if (ranked.isEmpty()) {
            return 0;
        }
        ranked.sort((a, b) -> Double.compare(vein[b[0]][b[1]], vein[a[0]][a[1]]));
        int cellW = Math.max(25, width / 4);
        int cellH = Math.max(25, height / 4);
        List<int[]> seeds = new ArrayList<>();
        for (int cx = 0; cx < width; cx += cellW) {
            for (int cy = 0; cy < height; cy += cellH) {
                int[] best = null;
                double bestVein = Double.NEGATIVE_INFINITY;
                int x1 = Math.min(width, cx + cellW);
                int y1 = Math.min(height, cy + cellH);
                for (int x = cx; x < x1; x++) {
                    for (int y = cy; y < y1; y++) {
                        if (ok[x][y] && vein[x][y] > bestVein) {
                            bestVein = vein[x][y];
                            best = new int[] {x, y};
                        }
                    }
                }
                if (best != null) {
                    seeds.add(best);
                }
            }
        }
        if (seeds.isEmpty()) {
            seeds.add(ranked.get(0));
        }
        boolean[][] used = new boolean[width][height];
        int placed = 0;
        for (int i = 0; i < seeds.size(); i++) {
            int want = Math.max(8, (maxTiles - placed) / (seeds.size() - i));
            placed += growPatch(grid, type, ok, used, seeds.get(i)[0], seeds.get(i)[1], want);
            if (placed >= maxTiles) {
                return placed;
            }
        }
        return placed;
    }

    private static int growPatch(
            Tile[][] grid,
            ResourceType type,
            boolean[][] ok,
            boolean[][] used,
            int startX,
            int startY,
            int want) {
        int width = grid.length;
        int height = grid[0].length;
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[] {startX, startY});
        int grown = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty() && grown < want) {
            int[] cur = q.removeFirst();
            int x = cur[0];
            int y = cur[1];
            if (x < 0 || y < 0 || x >= width || y >= height || used[x][y] || !ok[x][y]) {
                continue;
            }
            used[x][y] = true;
            Tile tile = grid[x][y];
            if (tile.resourceType != null) {
                continue;
            }
            tile.terrainType = type.patchTerrain().code();
            tile.resourceType = type;
            tile.quantity = type.cap();
            tile.refreshTraversal();
            tile.persist();
            grown++;
            for (int[] dir : dirs) {
                q.add(new int[] {x + dir[0], y + dir[1]});
            }
        }
        return grown;
    }

    @Transactional
    public void ensureMapFeatures() {
        ensureMountainRidges();
        ensureCities();
        ensureMarkets();
        ensureResources();
        routingGraphService.rebuild();
        ensureAgentCount();
        ensureAgentJobs();
        ensureAgentGoals();
    }

    @Transactional
    public void ensureAgentCount() {
        Tile[][] grid = gridBounds();
        if (grid == null) {
            return;
        }
        long existing = Agent.count();
        if (existing >= WorldConfig.AGENT_COUNT) {
            return;
        }
        spawnAgents(grid, grid.length, grid[0].length, (int) (WorldConfig.AGENT_COUNT - existing));
    }

    @Transactional
    public void ensureResources() {
        if (Tile.count("resourceType is not null") > 0) {
            return;
        }
        Tile[][] grid = gridBounds();
        if (grid == null) {
            return;
        }
        PerlinNoise noise = new PerlinNoise(WorldConfig.WORLD_SEED);
        double[][] elevation = elevations(grid, noise);
        seedResourceVeins(grid, noise, elevation);
    }

    @Transactional
    public void ensureCities() {
        if (Tile.count("terrainType = ?1", TerrainType.CITY.code()) >= 5) {
            return;
        }
        Tile[][] grid = gridBounds();
        if (grid == null) {
            return;
        }
        placeCities(grid, grid.length, grid[0].length);
    }

    @Transactional
    public void ensureMountainRidges() {
        // Mountain blobs come from elevation noise during generateGrid.
    }

    @Transactional
    public void ensureMarkets() {
        if (City.count() == 0) {
            for (int i = 0; i < WorldConfig.CITY_CENTERS.length; i++) {
                int[] center = WorldConfig.CITY_CENTERS[i];
                City city = new City();
                city.name = WorldConfig.CITY_NAMES[i];
                city.x = center[0];
                city.y = center[1];
                city.persist();
                Market.seedFor(city);
            }
        } else if (Market.count() == 0) {
            for (City city : City.all()) {
                Market.seedFor(city);
            }
        }
        for (City city : City.all()) {
            bindCityTiles(city, WorldConfig.CITY_RADIUS);
        }
    }

    @Transactional
    public void ensureAgentJobs() {
        Job[] jobs = Job.values();
        boolean missingTraders = Agent.count("job", Job.TRADER) == 0;
        int i = 0;
        for (Agent agent : Agent.all()) {
            if (agent.job == null || missingTraders) {
                agent.job = jobs[i % jobs.length];
                agent.persist();
            }
            i++;
        }
    }

    @Transactional
    public void ensureAgentGoals() {
        for (Agent agent : Agent.all()) {
            behaviorService.assignGoal(agent);
            agent.persist();
        }
    }

    private void spawnAgents(Tile[][] grid, int width, int height, int count) {
        Job[] jobs = Job.values();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int created = 0;
        int attempts = 0;
        while (created < count && attempts < count * 80) {
            attempts++;
            int x = rng.nextInt(width);
            int y = rng.nextInt(height);
            Tile tile = grid[x][y];
            if (!tile.isPassable()) {
                continue;
            }
            createAgent(tile, "Worker-" + (Agent.count() + 1), jobs[created % jobs.length]);
            created++;
        }
    }

    private void createAgent(Tile tile, String name, Job job) {
        Agent agent = new Agent();
        agent.name = name;
        agent.job = job;
        agent.speed = 1.0;
        agent.location = GeometryFactoryHolder.createPoint(tile.location.getX(), tile.location.getY());
        agent.persist();
        behaviorService.assignGoal(agent);
        agent.persist();
    }

    private static double elevationAt(PerlinNoise noise, int x, int y) {
        double f = WorldConfig.LANDFORM_FREQUENCY;
        return noise.fbm01(x * f, y * f, 4);
    }

    private static void raiseMassifs(double[][] elevation, int width, int height) {
        Random rng = new Random(WorldConfig.WORLD_SEED ^ 0x51EDL);
        List<int[]> sites = new ArrayList<>();
        int margin = Math.max(8, Math.min(width, height) / 12);
        int innerW = Math.max(1, width - margin * 2);
        int innerH = Math.max(1, height - margin * 2);
        for (int attempt = 0; attempt < 2000 && sites.size() < WorldConfig.MOUNTAIN_MASSIFS; attempt++) {
            int x = margin + rng.nextInt(innerW);
            int y = margin + rng.nextInt(innerH);
            if (elevation[x][y] < WorldConfig.WATER_LEVEL + 0.08 || nearCitySite(x, y)) {
                continue;
            }
            boolean far = true;
            for (int[] site : sites) {
                if (manhattan(site[0], site[1], x, y) < WorldConfig.MASSIF_SEPARATION) {
                    far = false;
                    break;
                }
            }
            if (!far) {
                continue;
            }
            sites.add(new int[] {x, y});
        }
        int baseRadius = WorldConfig.MASSIF_RADIUS;
        for (int[] site : sites) {
            int radius = baseRadius + rng.nextInt(Math.max(3, baseRadius / 5));
            double boost = 0.24 + rng.nextDouble() * 0.06;
            int x0 = Math.max(0, site[0] - radius);
            int x1 = Math.min(width - 1, site[0] + radius);
            int y0 = Math.max(0, site[1] - radius);
            int y1 = Math.min(height - 1, site[1] + radius);
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    double d = Math.hypot(x - site[0], y - site[1]) / radius;
                    if (d < 1) {
                        elevation[x][y] += (1 - d * d) * boost;
                    }
                }
            }
        }
    }

    private int largestBlob(TerrainType type) {
        int largest = 0;
        for (int size : blobSizes(type)) {
            if (size > largest) {
                largest = size;
            }
        }
        return largest;
    }

    private int countLargeBlobs(TerrainType type, int minSize) {
        int blobs = 0;
        for (int size : blobSizes(type)) {
            if (size >= minSize) {
                blobs++;
            }
        }
        return blobs;
    }

    @SuppressWarnings("unchecked")
    private List<Integer> blobSizes(TerrainType type) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT x, y
                        FROM tile
                        WHERE terraintype = ?1
                        """)
                .setParameter(1, type.code())
                .getResultList();
        Set<Long> cells = new HashSet<>();
        for (Object[] row : rows) {
            cells.add((((long) ((Number) row[0]).intValue()) << 32)
                    ^ (((Number) row[1]).intValue() & 0xffffffffL));
        }
        Set<Long> seen = new HashSet<>();
        List<Integer> sizes = new ArrayList<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (long cell : cells) {
            if (!seen.add(cell)) {
                continue;
            }
            int size = 1;
            ArrayDeque<Long> q = new ArrayDeque<>();
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
        return sizes;
    }

    private boolean resourceSideSkewed() {
        Number left = (Number) entityManager.createNativeQuery("""
                        SELECT COUNT(*) FROM tile
                        WHERE resourcetype IS NOT NULL AND x < :mid
                        """)
                .setParameter("mid", WorldConfig.MAP_WIDTH / 2)
                .getSingleResult();
        Number right = (Number) entityManager.createNativeQuery("""
                        SELECT COUNT(*) FROM tile
                        WHERE resourcetype IS NOT NULL AND x >= :mid
                        """)
                .setParameter("mid", WorldConfig.MAP_WIDTH / 2)
                .getSingleResult();
        int west = left.intValue();
        int east = right.intValue();
        int total = west + east;
        if (total == 0) {
            return true;
        }
        int minority = Math.min(west, east);
        return minority * 100 < total * WorldConfig.MIN_RESOURCE_SIDE_PERCENT;
    }

    @SuppressWarnings("unchecked")
    private int countCanalRuns(int minLength) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT x, y
                        FROM tile
                        WHERE terraintype = 'water'
                        """)
                .getResultList();
        boolean[][] water = new boolean[WorldConfig.MAP_WIDTH][WorldConfig.MAP_HEIGHT];
        for (Object[] row : rows) {
            int x = ((Number) row[0]).intValue();
            int y = ((Number) row[1]).intValue();
            if (x >= 0 && y >= 0 && x < water.length && y < water[0].length) {
                water[x][y] = true;
            }
        }
        return thinCanalRuns(water, minLength);
    }

    static int thinCanalRuns(boolean[][] water, int minLength) {
        int width = water.length;
        int height = water[0].length;
        int runs = 0;
        for (int y = 0; y < height; y++) {
            int len = 0;
            for (int x = 0; x < width; x++) {
                if (axisCanal(water, x, y, true)) {
                    len++;
                } else {
                    if (len >= minLength) {
                        runs++;
                    }
                    len = 0;
                }
            }
            if (len >= minLength) {
                runs++;
            }
        }
        for (int x = 0; x < width; x++) {
            int len = 0;
            for (int y = 0; y < height; y++) {
                if (axisCanal(water, x, y, false)) {
                    len++;
                } else {
                    if (len >= minLength) {
                        runs++;
                    }
                    len = 0;
                }
            }
            if (len >= minLength) {
                runs++;
            }
        }
        return runs;
    }

    private static boolean axisCanal(boolean[][] water, int x, int y, boolean horizontal) {
        if (!water[x][y]) {
            return false;
        }
        boolean east = x + 1 < water.length && water[x + 1][y];
        boolean west = x - 1 >= 0 && water[x - 1][y];
        boolean north = y - 1 >= 0 && water[x][y - 1];
        boolean south = y + 1 < water[0].length && water[x][y + 1];
        if (horizontal) {
            return east && west && !north && !south;
        }
        return north && south && !east && !west;
    }

    private static double[][] elevations(Tile[][] grid, PerlinNoise noise) {
        int width = grid.length;
        int height = grid[0].length;
        double[][] elevation = new double[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                elevation[x][y] = elevationAt(noise, x, y);
            }
        }
        raiseMassifs(elevation, width, height);
        return elevation;
    }

    private static Tile[][] gridBounds() {
        int width = 0;
        int height = 0;
        for (Tile tile : Tile.all()) {
            width = Math.max(width, tile.x + 1);
            height = Math.max(height, tile.y + 1);
        }
        if (width == 0 || height == 0) {
            return null;
        }

        Tile[][] grid = new Tile[width][height];
        for (Tile tile : Tile.all()) {
            grid[tile.x][tile.y] = tile;
        }
        return grid;
    }

    private static void placeCities(Tile[][] grid, int width, int height) {
        for (int i = 0; i < WorldConfig.CITY_CENTERS.length; i++) {
            int[] center = WorldConfig.CITY_CENTERS[i];
            City city = new City();
            city.name = WorldConfig.CITY_NAMES[i];
            city.x = center[0];
            city.y = center[1];
            city.persist();
            Market.seedFor(city);
            placeCityAt(grid, width, height, city, WorldConfig.CITY_RADIUS, InfrastructureType.NONE);
        }
    }

    private List<int[]> placeVillages(Tile[][] grid, int width, int height) {
        List<int[]> placed = new ArrayList<>();
        Map<ResourceType, Integer> namesUsed = new EnumMap<>(ResourceType.class);
        List<ClusterSite> sites = resourceClusters(grid, width, height, WorldConfig.VILLAGE_MIN_CLUSTER);
        if (sites.isEmpty()) {
            sites = resourceClusters(grid, width, height, 3);
        }
        for (ResourceType type : ResourceType.values()) {
            tryPlaceFarthestVillage(grid, width, height, sites, placed, namesUsed, type);
        }
        while (placed.size() < WorldConfig.MAX_VILLAGES) {
            if (!tryPlaceFarthestVillage(grid, width, height, sites, placed, namesUsed, null)) {
                break;
            }
        }
        return placed;
    }

    private static boolean tryPlaceFarthestVillage(
            Tile[][] grid,
            int width,
            int height,
            List<ClusterSite> sites,
            List<int[]> placed,
            Map<ResourceType, Integer> namesUsed,
            ResourceType type) {
        if (placed.size() >= WorldConfig.MAX_VILLAGES) {
            return false;
        }
        ClusterSite best = null;
        int[] bestAt = null;
        int bestFar = -1;
        int bestDensity = -1;
        for (ClusterSite site : sites) {
            if (type != null && site.resource != type) {
                continue;
            }
            int[] at = standableNear(grid, site.x, site.y);
            if (at == null || tooCloseToSettlement(at[0], at[1], placed)) {
                continue;
            }
            int far = minSettlementDist(at[0], at[1], placed);
            if (far > bestFar || (far == bestFar && site.density > bestDensity)) {
                best = site;
                bestAt = at;
                bestFar = far;
                bestDensity = site.density;
            }
        }
        if (best == null) {
            return false;
        }
        int nameIndex = namesUsed.merge(best.resource, 1, Integer::sum) - 1;
        City village = new City();
        village.name = villageName(best.resource, nameIndex);
        village.x = bestAt[0];
        village.y = bestAt[1];
        village.persist();
        Market.seedFor(village);
        placeCityAt(grid, width, height, village, WorldConfig.VILLAGE_RADIUS, InfrastructureType.VILLAGE);
        placed.add(bestAt);
        return true;
    }

    private List<int[]> placeHarbors(Tile[][] grid, int width, int height, List<int[]> villages) {
        List<int[]> placed = new ArrayList<>();
        List<int[]> coasts = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (!canSettle(grid, x, y) || !adjacentWater(grid, x, y)) {
                    continue;
                }
                List<int[]> skip = new ArrayList<>(villages);
                skip.addAll(placed);
                if (tooCloseToSettlement(x, y, skip)) {
                    continue;
                }
                coasts.add(new int[] {x, y, countNearbyResources(grid, x, y, 4)});
            }
        }
        coasts.sort(Comparator.comparingInt((int[] c) -> c[2]).reversed());
        int band = Math.max(1, width / WorldConfig.MAX_HARBORS);
        for (int b = 0; b < WorldConfig.MAX_HARBORS; b++) {
            int x0 = b * band;
            int x1 = b == WorldConfig.MAX_HARBORS - 1 ? width : (b + 1) * band;
            int[] pick = null;
            for (int[] coast : coasts) {
                if (coast[0] < x0 || coast[0] >= x1) {
                    continue;
                }
                if (tooCloseToSettlement(coast[0], coast[1], placed)) {
                    continue;
                }
                pick = coast;
                break;
            }
            if (pick == null) {
                continue;
            }
            City harbor = new City();
            harbor.name = WorldConfig.HARBOR_NAMES[placed.size()];
            harbor.x = pick[0];
            harbor.y = pick[1];
            harbor.harbor = true;
            harbor.boatStock = WorldConfig.HARBOR_BOAT_STOCK;
            harbor.persist();
            Market.seedFor(harbor);
            placeCityAt(grid, width, height, harbor, WorldConfig.VILLAGE_RADIUS, InfrastructureType.HARBOR);
            placed.add(new int[] {pick[0], pick[1]});
        }
        return placed;
    }

    private static void spawnWagons(List<int[]> villages) {
        for (int[] at : villages) {
            City city = City.atGrid(at[0], at[1]);
            if (city == null || city.harbor) {
                continue;
            }
            city.wagonStock = 1;
            city.persist();
            Vehicle wagon = new Vehicle();
            wagon.type = VehicleType.WAGON;
            wagon.location = GeometryFactoryHolder.createPoint(at[0] + 0.5, at[1] + 0.5);
            wagon.speed = WorldConfig.WAGON_SPEED;
            wagon.homeCityId = city.id;
            wagon.persist();
        }
    }

    private static boolean adjacentWater(Tile[][] grid, int x, int y) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : dirs) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            if (nx < 0 || ny < 0 || nx >= grid.length || ny >= grid[0].length) {
                continue;
            }
            if (isTerrain(grid[nx][ny], TerrainType.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static int countNearbyResources(Tile[][] grid, int x, int y, int radius) {
        int count = 0;
        int width = grid.length;
        int height = grid[0].length;
        for (int nx = x - radius; nx <= x + radius; nx++) {
            for (int ny = y - radius; ny <= y + radius; ny++) {
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                    continue;
                }
                if (grid[nx][ny].resourceType != null) {
                    count++;
                }
            }
        }
        return count;
    }

    private void buildRoads(Tile[][] grid, List<int[]> villages) {
        Set<Long> linked = new HashSet<>();
        for (int[] from : WorldConfig.CITY_CENTERS) {
            List<int[]> others = new ArrayList<>();
            for (int[] city : WorldConfig.CITY_CENTERS) {
                if (city[0] == from[0] && city[1] == from[1]) {
                    continue;
                }
                others.add(city);
            }
            others.sort(Comparator.comparingInt(other -> manhattan(from[0], from[1], other[0], other[1])));
            int links = Math.min(WorldConfig.ROAD_LINKS_PER_CITY, others.size());
            for (int i = 0; i < links; i++) {
                connectRoad(grid, from, others.get(i), linked);
            }
        }
        for (int[] from : villages) {
            List<int[]> others = new ArrayList<>(villages);
            others.removeIf(other -> other[0] == from[0] && other[1] == from[1]);
            others.sort(Comparator.comparingInt(other -> manhattan(from[0], from[1], other[0], other[1])));
            int links = Math.min(WorldConfig.ROAD_LINKS_PER_VILLAGE, others.size());
            for (int i = 0; i < links; i++) {
                connectRoad(grid, from, others.get(i), linked);
            }
            int[] city = nearestCityCenter(from);
            if (city != null) {
                connectRoad(grid, from, city, linked);
            }
        }
    }

    private void connectRoad(Tile[][] grid, int[] from, int[] to, Set<Long> linked) {
        long key = pairKey(from, to);
        if (!linked.add(key)) {
            return;
        }
        List<int[]> path = pathfindingService.findRoadPath(grid, from[0], from[1], to[0], to[1]);
        for (int[] step : path) {
            paintRoadTile(grid[step[0]][step[1]]);
        }
    }

    private static void paintRoadTile(Tile tile) {
        if (tile == null || tile.isCity() || isTerrain(tile, TerrainType.MOUNTAIN)) {
            return;
        }
        if (isTerrain(tile, TerrainType.WATER)) {
            tile.infrastructureType = InfrastructureType.BRIDGE.code();
        } else {
            tile.infrastructureType = InfrastructureType.ROAD.code();
        }
        tile.refreshTraversal();
        tile.persist();
    }

    private static List<ClusterSite> resourceClusters(Tile[][] grid, int width, int height, int minDensity) {
        List<ClusterSite> sites = new ArrayList<>();
        int r = WorldConfig.VILLAGE_CLUSTER_RADIUS;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Tile tile = grid[x][y];
                if (tile.resourceType == null || !tile.isPassable() || tile.isCity()) {
                    continue;
                }
                int density = countResource(grid, x, y, r, tile.resourceType);
                if (density < minDensity) {
                    continue;
                }
                sites.add(new ClusterSite(x, y, density, tile.resourceType));
            }
        }
        return sites;
    }

    private static int countResource(Tile[][] grid, int x, int y, int radius, ResourceType type) {
        int count = 0;
        int width = grid.length;
        int height = grid[0].length;
        for (int nx = x - radius; nx <= x + radius; nx++) {
            for (int ny = y - radius; ny <= y + radius; ny++) {
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                    continue;
                }
                if (grid[nx][ny].resourceType == type) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int[] standableNear(Tile[][] grid, int x, int y) {
        int width = grid.length;
        int height = grid[0].length;
        if (canSettle(grid, x, y)) {
            return new int[] {x, y};
        }
        for (int radius = 1; radius <= 6; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.abs(dx) != radius && Math.abs(dy) != radius) {
                        continue;
                    }
                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                        continue;
                    }
                    if (canSettle(grid, nx, ny)) {
                        return new int[] {nx, ny};
                    }
                }
            }
        }
        return null;
    }

    private static boolean canSettle(Tile[][] grid, int x, int y) {
        Tile tile = grid[x][y];
        return tile.isPassable() && !tile.isCity() && !nearCitySite(x, y);
    }

    private static boolean tooCloseToSettlement(int x, int y, List<int[]> villages) {
        return minSettlementDist(x, y, villages) < WorldConfig.VILLAGE_MIN_SEPARATION;
    }

    private static int minSettlementDist(int x, int y, List<int[]> villages) {
        int best = Integer.MAX_VALUE;
        for (int[] village : villages) {
            best = Math.min(best, manhattan(x, y, village[0], village[1]));
        }
        for (int[] center : WorldConfig.CITY_CENTERS) {
            best = Math.min(best, manhattan(x, y, center[0], center[1]));
        }
        return best;
    }

    private static int[] nearestCityCenter(int[] from) {
        int[] best = WorldConfig.CITY_CENTERS[0];
        int bestDist = manhattan(from[0], from[1], best[0], best[1]);
        for (int i = 1; i < WorldConfig.CITY_CENTERS.length; i++) {
            int[] center = WorldConfig.CITY_CENTERS[i];
            int dist = manhattan(from[0], from[1], center[0], center[1]);
            if (dist < bestDist) {
                best = center;
                bestDist = dist;
            }
        }
        return best;
    }

    private static long pairKey(int[] a, int[] b) {
        int ax = a[0];
        int ay = a[1];
        int bx = b[0];
        int by = b[1];
        if (ax > bx || (ax == bx && ay > by)) {
            int tx = ax;
            ax = bx;
            bx = tx;
            int ty = ay;
            ay = by;
            by = ty;
        }
        return (((long) ax) << 48) | (((long) ay) << 32) | (((long) bx) << 16) | (by & 0xffff);
    }

    private static String villageName(ResourceType resource, int index) {
        String[] names = switch (resource) {
            case WOOD -> new String[] {"Timberfall", "Oakstead", "Pineholt"};
            case FOOD -> new String[] {"Millfield", "Harveston", "Grainwell"};
            case GOLD -> new String[] {"Goldrun", "Aurum"};
            case STONE -> new String[] {"Flintford", "Cragwatch"};
            case IRON -> new String[] {"Ironwell", "Forgelea"};
            case HERBS -> new String[] {"Fernlea", "Bramblewick"};
        };
        if (index < names.length) {
            return names[index];
        }
        return names[0] + " " + (index + 1);
    }

    private record ClusterSite(int x, int y, int density, ResourceType resource) {}

    private static void placeCityAt(
            Tile[][] grid, int width, int height, City city, int radius, InfrastructureType infrastructure) {
        for (int x = city.x - radius; x <= city.x + radius; x++) {
            for (int y = city.y - radius; y <= city.y + radius; y++) {
                if (x < 0 || y < 0 || x >= width || y >= height) {
                    continue;
                }
                Tile tile = grid[x][y];
                tile.terrainType = TerrainType.CITY.code();
                tile.resourceType = null;
                tile.quantity = 0;
                tile.cityId = city.id;
                tile.infrastructureType = infrastructure.code();
                tile.refreshTraversal();
                tile.persist();
            }
        }
    }

    private static void bindCityTiles(City city, int radius) {
        for (int x = city.x - radius; x <= city.x + radius; x++) {
            for (int y = city.y - radius; y <= city.y + radius; y++) {
                Tile tile = Tile.findByGrid(x, y);
                if (tile == null || !tile.isCity()) {
                    continue;
                }
                tile.cityId = city.id;
                tile.persist();
            }
        }
    }
}
