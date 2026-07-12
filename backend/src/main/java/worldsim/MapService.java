package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class MapService {

    @Inject
    RoutingGraphService routingGraphService;

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
        routingGraphService.rebuild();
        spawnDefaultAgents(grid, width, height);
    }

    @Transactional
    public void ensureMapFeatures() {
        ensureMountainBarrier();
        routingGraphService.rebuild();
        ensureMultipleAgents();
        ensureAgentTargets();
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

        spawnIfMissing(grid, "Walker", 0, 0, maxX, maxY);
        spawnIfMissing(grid, "Scout", maxX, 0, 0, maxY);
        spawnIfMissing(grid, "Trader", 0, maxY, maxX, 0);
        spawnIfMissing(grid, "Guard", midX, 0, midX, maxY);
        spawnIfMissing(grid, "Ranger", 0, midY, maxX, midY);
    }

    private static void spawnDefaultAgents(Tile[][] grid, int width, int height) {
        if (Agent.count() > 0) {
            return;
        }
        int maxX = width - 1;
        int maxY = height - 1;
        int midX = maxX / 2;
        int midY = maxY / 2;

        createAgent(grid, "Walker", 0, 0, maxX, maxY);
        createAgent(grid, "Scout", maxX, 0, 0, maxY);
        createAgent(grid, "Trader", 0, maxY, maxX, 0);
        createAgent(grid, "Guard", midX, 0, midX, maxY);
        createAgent(grid, "Ranger", 0, midY, maxX, midY);
    }

    private static void spawnIfMissing(Tile[][] grid, String name, int x, int y, int tx, int ty) {
        if (Agent.count("name = ?1", name) == 0) {
            createAgent(grid, name, x, y, tx, ty);
        }
    }

    private static void createAgent(Tile[][] grid, String name, int x, int y, int tx, int ty) {
        Agent agent = new Agent();
        agent.name = name;
        agent.speed = 1.0;
        agent.location = grid[x][y].location;
        agent.targetLocation = grid[tx][ty].location;
        agent.persist();
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

    @Transactional
    public void ensureAgentTargets() {
        Tile[][] bounds = gridBounds();
        if (bounds == null) {
            return;
        }

        int maxX = bounds.length - 1;
        int maxY = bounds[0].length - 1;
        for (Agent agent : Agent.all()) {
            if (agent.targetLocation == null) {
                agent.targetLocation = bounds[maxX][maxY].location;
                agent.currentPath.clear();
                agent.persist();
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
}
