package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class PathfindingService {

    @Inject
    EntityManager entityManager;

    public List<Tile> findPath(Tile start, Tile end) {
        if (start == null || end == null) {
            return List.of();
        }
        if (start.id.equals(end.id)) {
            return List.of();
        }
        if (!end.isPassable()) {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<Number> nodeIds = entityManager.createNativeQuery("""
                SELECT node
                FROM pgr_dijkstra(
                    'SELECT id, source, target, cost, reverse_cost FROM routing_edges',
                    :startId,
                    :endId,
                    directed => false
                )
                ORDER BY seq
                """)
                .setParameter("startId", start.id)
                .setParameter("endId", end.id)
                .getResultList();

        List<Tile> path = new ArrayList<>();
        for (Number nodeId : nodeIds) {
            Tile tile = Tile.findById(nodeId.longValue());
            if (tile != null) {
                path.add(tile);
            }
        }
        return path;
    }
}
