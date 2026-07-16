package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class MapService {

    @Inject
    RoutingGraphService routingGraphService;

    @Inject
    BehaviorService behaviorService;

    @Transactional
    public void generateGrid(int width, int height) {
        Tile[][] grid = new Tile[width][height];

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Tile tile = new Tile();
                tile.x = x;
                tile.y = y;
                tile.terrainType = "grass";
                tile.location = GeometryFactoryHolder.createPoint(x + 0.5, y + 0.5);
                tile.persist();
                grid[x][y] = tile;
            }
        }

        addMountainBarrier(grid, width, height);
        placeCity(grid, width, height);
        seedResources(grid, width, height);
        routingGraphService.rebuild();
        spawnDefaultAgents(grid, width, height);
    }

    @Transactional
    public void ensureMapFeatures() {
        ensureMountainBarrier();
        ensureCity();
        ensureResources();
        routingGraphService.rebuild();
        ensureMultipleAgents();
        ensureAgentJobs();
        ensureAgentGoals();
    }

    @Transactional
    public void ensureMultipleAgents() {
        Tile[][] grid = gridBounds();
        if (grid == null) {
            return;
        }
        int maxX = grid.length - 1;
        int maxY = grid[0].length - 1;
        int midX = maxX / 2;
        int midY = maxY / 2;

        spawnIfMissing(grid, "Walker", Job.LUMBERJACK, 0, 0);
        spawnIfMissing(grid, "Scout", Job.MINER, maxX, 0);
        spawnIfMissing(grid, "Trader", Job.TRADER, 0, maxY);
        spawnIfMissing(grid, "Guard", Job.MINER, midX, 0);
        spawnIfMissing(grid, "Ranger", Job.LUMBERJACK, 0, midY);
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
    public void ensureCity() {
        if (Tile.count("terrainType = ?1", "city") > 0) {
            return;
        }
        Tile[][] grid = gridBounds();
        if (grid == null) {
            return;
        }
        placeCity(grid, grid.length, grid[0].length);
    }

    @Transactional
    public void ensureAgentJobs() {
        for (Agent agent : Agent.all()) {
            if (agent.job != null) {
                continue;
            }
            agent.job = switch (agent.name) {
                case "Scout", "Guard" -> Job.MINER;
                case "Trader" -> Job.TRADER;
                default -> Job.LUMBERJACK;
            };
            agent.persist();
        }
    }

    @Transactional
    public void ensureAgentGoals() {
        for (Agent agent : Agent.all()) {
            behaviorService.assignGoal(agent);
            agent.persist();
        }
    }

    private void spawnDefaultAgents(Tile[][] grid, int width, int height) {
        if (Agent.count() > 0) {
            return;
        }
        int maxX = width - 1;
        int maxY = height - 1;
        int midX = maxX / 2;
        int midY = maxY / 2;

        createAgent(grid, "Walker", Job.LUMBERJACK, 0, 0);
        createAgent(grid, "Scout", Job.MINER, maxX, 0);
        createAgent(grid, "Trader", Job.TRADER, 0, maxY);
        createAgent(grid, "Guard", Job.MINER, midX, 0);
        createAgent(grid, "Ranger", Job.LUMBERJACK, 0, midY);
    }

    private void spawnIfMissing(Tile[][] grid, String name, Job job, int x, int y) {
        if (Agent.count("name = ?1", name) == 0) {
            createAgent(grid, name, job, x, y);
        }
    }

    private void createAgent(Tile[][] grid, String name, Job job, int x, int y) {
        Agent agent = new Agent();
        agent.name = name;
        agent.job = job;
        agent.speed = 1.0;
        agent.location = grid[x][y].location;
        agent.persist();
        behaviorService.assignGoal(agent);
        agent.persist();
    }

    private static void seedResources(Tile[][] grid, int width, int height) {
        placeResourcePatch(grid, ResourceType.WOOD, 2, 2, 4, 4, 40);
        placeResourcePatch(grid, ResourceType.GOLD, width - 6, 2, 4, 4, 40);
        placeResourcePatch(grid, ResourceType.FOOD, 2, height - 6, 4, 4, 40);
        placeResourcePatch(grid, ResourceType.WOOD, width - 6, height - 6, 3, 3, 40);
        placeResourcePatch(grid, ResourceType.GOLD, width - 5, height / 2 + 3, 3, 2, 30);
        placeResourcePatch(grid, ResourceType.FOOD, width / 2 + 3, height - 5, 3, 3, 30);
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
                tile.resourceType = type;
                tile.quantity = quantity;
                tile.persist();
            }
        }
    }

    @Transactional
    public void ensureMountainBarrier() {
        if (Tile.count("terrainType = ?1", "mountain") > 0) {
            return;
        }
        Tile[][] grid = gridBounds();
        if (grid == null) {
            return;
        }
        addMountainBarrier(grid, grid.length, grid[0].length);
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

    private static void addMountainBarrier(Tile[][] grid, int width, int height) {
        int barrierY = height / 2;
        for (int x = 2; x < width - 2; x++) {
            if (x == width / 2) {
                continue;
            }
            Tile tile = grid[x][barrierY];
            tile.terrainType = "mountain";
            tile.persist();
        }
    }

    /** City sits just south of the mountain barrier, centered on the pass. */
    private static void placeCity(Tile[][] grid, int width, int height) {
        int midX = width / 2;
        int cityY = height / 2 + 2;
        for (int x = midX - 1; x <= midX + 1; x++) {
            for (int y = cityY; y <= cityY + 2; y++) {
                if (x < 0 || y < 0 || x >= width || y >= height) {
                    continue;
                }
                Tile tile = grid[x][y];
                if (!tile.isPassable() && !"city".equals(tile.terrainType)) {
                    continue;
                }
                tile.terrainType = "city";
                tile.resourceType = null;
                tile.quantity = 0;
                tile.persist();
            }
        }
    }
}
