package worldsim;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SimulationEngine {

    @Inject
    PathfindingService pathfindingService;

    @Scheduled(every = "1s")
    @RunOnVirtualThread
    @Transactional
    public void tick() {
        for (Agent agent : Agent.all()) {
            moveAgent(agent);
            agent.persist();
        }
    }

    private void moveAgent(Agent agent) {
        if (agent.targetLocation == null) {
            return;
        }

        if (agent.currentPath.isEmpty()) {
            replanPath(agent);
        }

        if (agent.currentPath.isEmpty()) {
            return;
        }

        Tile nextTile = agent.currentPath.remove(0);
        agent.location = GeometryFactoryHolder.createPoint(
                nextTile.location.getX(),
                nextTile.location.getY());

        if (agent.currentPath.isEmpty() && hasReachedTarget(agent)) {
            flipTarget(agent);
            replanPath(agent);
        }
    }

    private void replanPath(Agent agent) {
        Tile start = Tile.findNearest(agent.location);
        Tile goal = Tile.findNearest(agent.targetLocation);
        if (start == null || goal == null) {
            return;
        }

        List<Tile> path = new ArrayList<>(pathfindingService.findPath(start, goal));
        if (!path.isEmpty() && path.get(0).id.equals(start.id)) {
            path.remove(0);
        }

        agent.currentPath.clear();
        agent.currentPath.addAll(path);
    }

    private static boolean hasReachedTarget(Agent agent) {
        Tile currentTile = Tile.findNearest(agent.location);
        Tile targetTile = Tile.findNearest(agent.targetLocation);
        return currentTile != null && targetTile != null && currentTile.id.equals(targetTile.id);
    }

    private static void flipTarget(Agent agent) {
        Tile[][] grid = gridBounds();
        if (grid == null) {
            return;
        }

        int maxX = grid.length - 1;
        int maxY = grid[0].length - 1;
        int midX = maxX / 2;
        int midY = maxY / 2;

        Tile[] waypoints = {
            grid[0][0],
            grid[maxX][0],
            grid[maxX][maxY],
            grid[0][maxY],
            grid[midX][midY],
        };

        Tile currentTarget = Tile.findNearest(agent.targetLocation);
        int currentIdx = 0;
        for (int i = 0; i < waypoints.length; i++) {
            if (currentTarget != null && waypoints[i].id.equals(currentTarget.id)) {
                currentIdx = i;
                break;
            }
        }

        int stride = 1 + (int) (agent.id % 2);
        Tile next = waypoints[(currentIdx + stride) % waypoints.length];
        agent.targetLocation = next.location;
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
}
