package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class RoutingGraphService {

    private static final String WALKER = """
            CASE
                WHEN %1$s.infrastructuretype = 'bridge' THEN 1.0
                WHEN %1$s.infrastructuretype = 'road' THEN 0.5
                WHEN %1$s.terraintype = 'water' THEN 1000000.0
                ELSE 1.0
            END
            """;

    private static final String BOAT = """
            CASE
                WHEN %1$s.terraintype = 'water' THEN 0.5
                WHEN %1$s.infrastructuretype = 'road' THEN 0.5
                ELSE 1.0
            END
            """;

    private static final String WAGON = """
            CASE
                WHEN %1$s.infrastructuretype = 'bridge' THEN 1.0
                WHEN %1$s.infrastructuretype = 'road' THEN 0.35
                WHEN %1$s.terraintype = 'water' THEN 1000000.0
                ELSE 1.0
            END
            """;

    @Inject
    EntityManager entityManager;

    @Transactional
    public void rebuild() {
        entityManager.createNativeQuery("TRUNCATE routing_edges RESTART IDENTITY").executeUpdate();
        entityManager.createNativeQuery("""
                        INSERT INTO routing_edges (
                            source, target, cost, reverse_cost,
                            boat_cost, boat_reverse_cost, wagon_cost, wagon_reverse_cost, geom)
                        SELECT
                            t1.id,
                            t2.id,
                            %s,
                            %s,
                            %s,
                            %s,
                            %s,
                            %s,
                            ST_MakeLine(t1.location, t2.location)
                        FROM tile t1
                        JOIN tile t2 ON (
                            (t1.x + 1 = t2.x AND t1.y = t2.y) OR
                            (t1.x - 1 = t2.x AND t1.y = t2.y) OR
                            (t1.x = t2.x AND t1.y + 1 = t2.y) OR
                            (t1.x = t2.x AND t1.y - 1 = t2.y)
                        )
                        WHERE t1.terraintype <> 'mountain'
                          AND t2.terraintype <> 'mountain'
                        """
                        .formatted(
                                WALKER.formatted("t2"),
                                WALKER.formatted("t1"),
                                BOAT.formatted("t2"),
                                BOAT.formatted("t1"),
                                WAGON.formatted("t2"),
                                WAGON.formatted("t1")))
                .executeUpdate();
    }
}
