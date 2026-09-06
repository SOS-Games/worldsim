package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class MapService {

    @Inject
    RoutingGraphService routingGraphService;

    @Inject
    BehaviorService behaviorService;

    @Inject
    EntityManager entityManager;

    @Transactional
    public void ensureWorldReady() {
        long tiles = Tile.count();
        boolean missingBiomes = Tile.count("terrainType = ?1", TerrainType.FOREST.code()) == 0;
        if (tiles != WorldConfig.EXPECTED_TILE_COUNT || missingBiomes) {
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
                        "TRUNCATE TABLE agent_inventory, agent, market, city, tile, routing_edges RESTART IDENTITY CASCADE")
                .executeUpdate();
        GoalIndex.clearTileIds();
    }

    @Transactional
    public void generateGrid(int width, int height) {
        Tile[][] grid = new Tile[width][height];

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Tile tile = new Tile();
                tile.x = x;
                tile.y = y;
                tile.terrainType = TerrainType.GRASS.code();
                tile.location = GeometryFactoryHolder.createPoint(x + 0.5, y + 0.5);
                tile.persist();
                grid[x][y] = tile;
            }
        }

        addMountainRidges(grid, width, height);
        placeCities(grid, width, height);
        seedResources(grid, width, height);
        routingGraphService.rebuild();
        spawnAgents(grid, width, height, WorldConfig.AGENT_COUNT);
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
        seedResources(grid, grid.length, grid[0].length);
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
        if (Tile.count("terrainType = ?1", TerrainType.MOUNTAIN.code()) > 0) {
            return;
        }
        Tile[][] grid = gridBounds();
        if (grid == null) {
            return;
        }
        addMountainRidges(grid, grid.length, grid[0].length);
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
        while (created < count && attempts < count * 20) {
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

    private static void seedResources(Tile[][] grid, int width, int height) {
        placePatches(grid, ResourceType.WOOD, 4, 4, List.of(
                new int[] {5, 5}, new int[] {25, 8}, new int[] {45, 12}, new int[] {70, 6},
                new int[] {90, 15}, new int[] {8, 40}, new int[] {55, 35}, new int[] {85, 42},
                new int[] {12, 70}, new int[] {40, 75}, new int[] {68, 80}, new int[] {88, 72},
                new int[] {20, 90}, new int[] {50, 88}, new int[] {78, 92}));
        placePatches(grid, ResourceType.GOLD, 3, 3, List.of(
                new int[] {15, 18}, new int[] {35, 5}, new int[] {60, 10}, new int[] {82, 22},
                new int[] {5, 55}, new int[] {30, 48}, new int[] {58, 55}, new int[] {92, 50},
                new int[] {18, 78}, new int[] {48, 65}, new int[] {75, 70}, new int[] {95, 85},
                new int[] {28, 92}, new int[] {62, 90}, new int[] {42, 30}));
        placePatches(grid, ResourceType.FOOD, 4, 3, List.of(
                new int[] {10, 25}, new int[] {40, 20}, new int[] {65, 25}, new int[] {88, 8},
                new int[] {22, 45}, new int[] {50, 42}, new int[] {78, 48}, new int[] {6, 68},
                new int[] {35, 72}, new int[] {60, 68}, new int[] {85, 78}, new int[] {15, 88},
                new int[] {45, 95}, new int[] {72, 88}, new int[] {92, 60}));
        placePatches(grid, ResourceType.STONE, 3, 4, List.of(
                new int[] {2, 12}, new int[] {38, 38}, new int[] {72, 28}, new int[] {22, 62},
                new int[] {96, 38}, new int[] {44, 8}, new int[] {64, 94}, new int[] {8, 82},
                new int[] {52, 64}, new int[] {80, 88}));
        placePatches(grid, ResourceType.IRON, 3, 2, List.of(
                new int[] {28, 28}, new int[] {76, 14}, new int[] {14, 76}, new int[] {84, 64},
                new int[] {36, 86}, new int[] {54, 22}, new int[] {96, 72}, new int[] {4, 48},
                new int[] {70, 52}, new int[] {32, 4}));
        placePatches(grid, ResourceType.HERBS, 4, 2, List.of(
                new int[] {32, 16}, new int[] {66, 44}, new int[] {24, 54}, new int[] {80, 34},
                new int[] {16, 32}, new int[] {48, 78}, new int[] {74, 56}, new int[] {92, 26},
                new int[] {60, 2}, new int[] {4, 94}));
    }

    private static void placePatches(
            Tile[][] grid, ResourceType type, int patchWidth, int patchHeight, List<int[]> sites) {
        for (int[] site : sites) {
            placeResourcePatch(grid, type, site[0], site[1], patchWidth, patchHeight, type.cap());
        }
    }

    private static void placeResourcePatch(
            Tile[][] grid,
            ResourceType type,
            int originX,
            int originY,
            int patchWidth,
            int patchHeight,
            int quantity) {
        int maxX = grid.length;
        int maxY = grid[0].length;
        for (int x = originX; x < originX + patchWidth && x < maxX; x++) {
            for (int y = originY; y < originY + patchHeight && y < maxY; y++) {
                if (x < 0 || y < 0) {
                    continue;
                }
                Tile tile = grid[x][y];
                if (!tile.isPassable() || tile.isCity()) {
                    continue;
                }
                tile.terrainType = type.patchTerrain().code();
                tile.resourceType = type;
                tile.quantity = quantity;
                tile.persist();
            }
        }
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

    private static void addMountainRidges(Tile[][] grid, int width, int height) {
        // Horizontal ridges with passes
        addHorizontalRidge(grid, width, height / 3, List.of(width / 4, width / 2, (3 * width) / 4));
        addHorizontalRidge(grid, width, (2 * height) / 3, List.of(width / 5, (2 * width) / 5, (3 * width) / 5, (4 * width) / 5));
        // Vertical ridges with passes
        addVerticalRidge(grid, height, width / 3, List.of(height / 4, height / 2, (3 * height) / 4));
        addVerticalRidge(grid, height, (2 * width) / 3, List.of(height / 5, (2 * height) / 5, (3 * height) / 5, (4 * height) / 5));
    }

    private static void addHorizontalRidge(Tile[][] grid, int width, int y, List<Integer> passes) {
        if (y <= 0 || y >= grid[0].length) {
            return;
        }
        for (int x = 1; x < width - 1; x++) {
            if (isNearPass(x, passes)) {
                continue;
            }
            grid[x][y].terrainType = TerrainType.MOUNTAIN.code();
            grid[x][y].resourceType = null;
            grid[x][y].quantity = 0;
            grid[x][y].persist();
        }
    }

    private static void addVerticalRidge(Tile[][] grid, int height, int x, List<Integer> passes) {
        if (x <= 0 || x >= grid.length) {
            return;
        }
        for (int y = 1; y < height - 1; y++) {
            if (isNearPass(y, passes)) {
                continue;
            }
            if (TerrainType.CITY.code().equals(grid[x][y].terrainType)) {
                continue;
            }
            grid[x][y].terrainType = TerrainType.MOUNTAIN.code();
            grid[x][y].resourceType = null;
            grid[x][y].quantity = 0;
            grid[x][y].persist();
        }
    }

    private static boolean isNearPass(int coord, List<Integer> passes) {
        for (int pass : passes) {
            if (Math.abs(coord - pass) <= 1) {
                return true;
            }
        }
        return false;
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
            placeCityAt(grid, width, height, city, WorldConfig.CITY_RADIUS);
        }
    }

    private static void placeCityAt(Tile[][] grid, int width, int height, City city, int radius) {
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
