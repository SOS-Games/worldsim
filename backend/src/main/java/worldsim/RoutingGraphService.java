package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class RoutingGraphService {

    @Inject
    EntityManager entityManager;

    @Transactional
    public void rebuild() {
        entityManager.createNativeQuery("TRUNCATE routing_edges RESTART IDENTITY").executeUpdate();
        entityManager.createNativeQuery("""
                INSERT INTO routing_edges (source, target, cost, reverse_cost, geom)
                SELECT
                    t1.id,
                    t2.id,
                    1,
                    1,
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
                """)
                .executeUpdate();
    }
}
