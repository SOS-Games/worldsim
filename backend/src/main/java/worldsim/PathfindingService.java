package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class PathfindingService {

    public record PathPair(long startId, long endId) {}

    @Inject
    EntityManager entityManager;

    public List<PathPoint> findPath(Tile start, Tile end) {
        return findPath(null, start, end);
    }

    public List<PathPoint> findPath(Agent agent, Tile start, Tile end) {
        MovementMode mode = modeOf(agent, null);
        if (start == null || end == null || start.id.equals(end.id)) {
            return List.of();
        }
        if (mode != MovementMode.BOAT && !end.isPassable()) {
            return List.of();
        }
        if (TerrainType.MOUNTAIN.code().equals(end.terrainType)) {
            return List.of();
        }
        PathPair pair = new PathPair(start.id, end.id);
        return findPaths(List.of(pair), mode).getOrDefault(pair, List.of());
    }

    public Map<PathPair, List<PathPoint>> findPaths(Collection<PathPair> pairs) {
        return findPaths(pairs, MovementMode.WALK);
    }

    /**
     * One graph load, many independent routes. Duplicate start/end pairs share a result.
     * Walker/wagon graphs omit open water, so those agents use bridges or go around lakes.
     * Boat graphs treat water as cost 0.5.
     */
    public Map<PathPair, List<PathPoint>> findPaths(Collection<PathPair> pairs, MovementMode mode) {
        Set<PathPair> unique = new LinkedHashSet<>();
        for (PathPair pair : pairs) {
            if (pair.startId() != pair.endId()) {
                unique.add(pair);
            }
        }
        if (unique.isEmpty()) {
            return Map.of();
        }

        MovementMode used = mode == null ? MovementMode.WALK : mode;
        String values = unique.stream()
                .map(pair -> "(" + pair.startId() + "," + pair.endId() + ")")
                .collect(Collectors.joining(","));

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT route.start_vid, route.end_vid, ST_X(t.location), ST_Y(t.location)
                        FROM pgr_dijkstra(
                            '%s',
                            'SELECT * FROM (VALUES %s) AS c(source, target)',
                            false
                        ) AS route
                        JOIN tile t ON t.id = route.node
                        ORDER BY route.start_vid, route.end_vid, route.seq
                        """.formatted(used.edgeSql(), values))
                .getResultList();

        Map<PathPair, List<PathPoint>> paths = new LinkedHashMap<>();
        for (Object[] row : rows) {
            PathPair pair = new PathPair(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            paths.computeIfAbsent(pair, ignored -> new ArrayList<>())
                    .add(new PathPoint(((Number) row[2]).doubleValue(), ((Number) row[3]).doubleValue()));
        }
        return paths;
    }

    public static MovementMode modeOf(Agent agent, Map<Long, Vehicle> vehicles) {
        if (agent == null) {
            return MovementMode.WALK;
        }
        if (vehicles != null) {
            if (agent.vehicleId != null) {
                Vehicle owned = vehicles.get(agent.vehicleId);
                if (owned != null) {
                    return MovementMode.of(owned.type);
                }
            }
            for (Vehicle vehicle : vehicles.values()) {
                if (agent.id.equals(vehicle.occupantId)) {
                    return MovementMode.of(vehicle.type);
                }
            }
        }
        if (agent.vehicleId != null) {
            Vehicle vehicle = Vehicle.findById(agent.vehicleId);
            if (vehicle != null) {
                return MovementMode.of(vehicle.type);
            }
        }
        return MovementMode.WALK;
    }

    /**
     * A* on the in-memory grid for laying roads. Walkers still use {@link #findPaths};
     * this path may step on water (those tiles become bridges).
     */
    public List<int[]> findRoadPath(Tile[][] grid, int startX, int startY, int endX, int endY) {
        if (grid == null || grid.length == 0) {
            return List.of();
        }
        int width = grid.length;
        int height = grid[0].length;
        if (!inBounds(startX, startY, width, height) || !inBounds(endX, endY, width, height)) {
            return List.of();
        }
        if (startX == endX && startY == endY) {
            return List.of(new int[] {startX, startY});
        }

        record Node(long key, double g, double f) {}

        long startKey = pack(startX, startY);
        long endKey = pack(endX, endY);
        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        gScore.put(startKey, 0.0);

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        open.add(new Node(startKey, 0.0, manhattan(startX, startY, endX, endY)));

        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            Node current = open.poll();
            long key = current.key();
            if (current.g() > gScore.getOrDefault(key, Double.POSITIVE_INFINITY) + 1e-9) {
                continue;
            }
            if (key == endKey) {
                return reconstruct(cameFrom, key);
            }
            int x = unpackX(key);
            int y = unpackY(key);
            for (int[] dir : dirs) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                if (!inBounds(nx, ny, width, height)) {
                    continue;
                }
                double step = roadBuildCost(grid[nx][ny]);
                if (step >= 1_000_000.0) {
                    continue;
                }
                long next = pack(nx, ny);
                double tentative = current.g() + step;
                if (tentative >= gScore.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    continue;
                }
                cameFrom.put(next, key);
                gScore.put(next, tentative);
                open.add(new Node(next, tentative, tentative + 0.4 * manhattan(nx, ny, endX, endY)));
            }
        }
        return List.of();
    }

    private static double roadBuildCost(Tile tile) {
        if (tile == null || TerrainType.MOUNTAIN.code().equals(tile.terrainType)) {
            return 1_000_000.0;
        }
        if (InfrastructureType.is(tile.infrastructureType, InfrastructureType.ROAD)
                || InfrastructureType.is(tile.infrastructureType, InfrastructureType.BRIDGE)) {
            return 0.4;
        }
        if (TerrainType.WATER.code().equals(tile.terrainType)) {
            return 1.3;
        }
        if (tile.isCity()) {
            return 0.7;
        }
        if (tile.resourceType != null) {
            return 1.2;
        }
        return 1.0;
    }

    private static List<int[]> reconstruct(Map<Long, Long> cameFrom, long end) {
        List<int[]> path = new ArrayList<>();
        long key = end;
        path.add(new int[] {unpackX(key), unpackY(key)});
        while (cameFrom.containsKey(key)) {
            key = cameFrom.get(key);
            path.add(new int[] {unpackX(key), unpackY(key)});
        }
        Collections.reverse(path);
        return path;
    }

    private static boolean inBounds(int x, int y, int width, int height) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    private static int manhattan(int ax, int ay, int bx, int by) {
        return Math.abs(ax - bx) + Math.abs(ay - by);
    }

    private static long pack(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackY(long key) {
        return (int) key;
    }
}
