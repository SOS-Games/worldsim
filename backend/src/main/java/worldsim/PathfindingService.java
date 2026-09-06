package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class PathfindingService {

    public record PathPair(long startId, long endId) {}

    @Inject
    EntityManager entityManager;

    public List<PathPoint> findPath(Tile start, Tile end) {
        if (start == null || end == null || start.id.equals(end.id) || !end.isPassable()) {
            return List.of();
        }
        return findPaths(List.of(new PathPair(start.id, end.id)))
                .getOrDefault(new PathPair(start.id, end.id), List.of());
    }

    /**
     * One graph load, many independent routes. Duplicate start/end pairs share a result.
     */
    public Map<PathPair, List<PathPoint>> findPaths(Collection<PathPair> pairs) {
        Set<PathPair> unique = new LinkedHashSet<>();
        for (PathPair pair : pairs) {
            if (pair.startId() != pair.endId()) {
                unique.add(pair);
            }
        }
        if (unique.isEmpty()) {
            return Map.of();
        }

        String values = unique.stream()
                .map(pair -> "(" + pair.startId() + "," + pair.endId() + ")")
                .collect(Collectors.joining(","));

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT route.start_vid, route.end_vid, ST_X(t.location), ST_Y(t.location)
                        FROM pgr_dijkstra(
                            'SELECT id, source, target, cost, reverse_cost FROM routing_edges',
                            'SELECT * FROM (VALUES %s) AS c(source, target)',
                            false
                        ) AS route
                        JOIN tile t ON t.id = route.node
                        ORDER BY route.start_vid, route.end_vid, route.seq
                        """.formatted(values))
                .getResultList();

        Map<PathPair, List<PathPoint>> paths = new LinkedHashMap<>();
        for (Object[] row : rows) {
            PathPair pair = new PathPair(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            paths.computeIfAbsent(pair, ignored -> new ArrayList<>())
                    .add(new PathPoint(((Number) row[2]).doubleValue(), ((Number) row[3]).doubleValue()));
        }
        return paths;
    }
}
