package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import worldsim.dto.AgentPosDto;
import worldsim.dto.SqlStepDto;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class PhysicsService {

    private static final String JOB_HARVESTS = Job.sqlHarvestMatch("a.job", "t.resourcetype");
    private static final String TILE_BELOW_CAP = ResourceType.sqlBelowCap("resourcetype", "quantity");
    private static final String PRICE_M = Market.priceSql("m");
    private static final String PRICE_O = Market.priceSql("o");

    /** Standing in a city whose listing is not cheaper than any other city. */
    private static final String AT_HIGH_PRICE = """
            NOT EXISTS (
                SELECT 1 FROM market o
                WHERE o.resource_type = m.resource_type
                  AND o.city_id <> m.city_id
                  AND %s > %s + 0.01
            )
            """.formatted(PRICE_O, PRICE_M);

    /** Standing in the cheapest stocked city, with a more expensive city elsewhere. */
    private static final String AT_LOW_PRICE = """
            m.stock > 0
            AND NOT EXISTS (
                SELECT 1 FROM market o
                WHERE o.resource_type = m.resource_type
                  AND o.stock > 0
                  AND o.city_id <> m.city_id
                  AND %s < %s - 0.01
            )
            AND EXISTS (
                SELECT 1 FROM market o
                WHERE o.resource_type = m.resource_type
                  AND o.city_id <> m.city_id
                  AND %s > %s + 0.01
            )
            """.formatted(PRICE_O, PRICE_M, PRICE_O, PRICE_M);

    @Inject
    EntityManager entityManager;

    public record Census(int agents, int walking, int harvesting, int buying, int depositing, int needRoute) {}

    public record StepStats(
            Census census,
            int moved,
            int harvested,
            int delivered,
            int bought,
            int regenerated,
            List<SqlStepDto> sql) {}

    /**
     * One physics step in the database: sell, buy, harvest, walk. Regeneration is opt-in.
     */
    public StepStats step(boolean regenerate) {
        List<SqlStepDto> sql = new ArrayList<>();
        int delivered = timed(sql, "sell", this::sellToCities);
        int bought = timed(sql, "buy", this::buyFromCities);
        int harvested = timed(sql, "harvest", this::harvest);
        int moved = timed(sql, "walk", this::moveAlongPaths);
        int regenerated = regenerate ? timed(sql, "regen", this::regenerateResources) : 0;
        long censusStarted = System.nanoTime();
        Census census = census();
        sql.add(new SqlStepDto(
                "census", (System.nanoTime() - censusStarted) / 1_000_000, census.agents()));
        return new StepStats(census, moved, harvested, delivered, bought, regenerated, sql);
    }

    private int timed(List<SqlStepDto> sql, String name, java.util.function.IntSupplier work) {
        long started = System.nanoTime();
        int rows = work.getAsInt();
        sql.add(new SqlStepDto(name, (System.nanoTime() - started) / 1_000_000, rows));
        return rows;
    }

    /**
     * Gatherers dump cargo into the local city market. Traders only sell at a high-price city.
     */
    int sellToCities() {
        Number count = (Number) entityManager.createNativeQuery("""
                        WITH sellers AS (
                            SELECT a.id, t.city_id
                            FROM agent a
                            JOIN tile t
                              ON t.x = FLOOR(ST_X(a.location))::int
                             AND t.y = FLOOR(ST_Y(a.location))::int
                            WHERE t.terraintype = 'city'
                              AND t.city_id IS NOT NULL
                              AND EXISTS (
                                    SELECT 1 FROM agent_inventory i
                                    WHERE i.agent_id = a.id AND i.quantity > 0
                              )
                              AND (
                                    a.job IS DISTINCT FROM 'TRADER'
                                 OR EXISTS (
                                        SELECT 1
                                        FROM agent_inventory i
                                        JOIN market m
                                          ON m.city_id = t.city_id
                                         AND m.resource_type = i.resource_type
                                        WHERE i.agent_id = a.id
                                          AND i.quantity > 0
                                          AND %s
                                    )
                              )
                        ),
                        moved AS (
                            SELECT s.city_id, i.resource_type, SUM(i.quantity)::int AS qty
                            FROM sellers s
                            JOIN agent_inventory i ON i.agent_id = s.id
                            GROUP BY s.city_id, i.resource_type
                        ),
                        stocked AS (
                            UPDATE market m
                            SET stock = m.stock + d.qty
                            FROM moved d
                            WHERE m.city_id = d.city_id
                              AND m.resource_type = d.resource_type
                            RETURNING m.id
                        ),
                        dropped AS (
                            DELETE FROM agent_inventory
                            WHERE agent_id IN (SELECT id FROM sellers)
                            RETURNING agent_id
                        ),
                        cleared AS (
                            UPDATE agent
                            SET currentpath = '[]'::jsonb,
                                trade_resource = CASE WHEN job = 'TRADER' THEN NULL ELSE trade_resource END
                            WHERE id IN (SELECT id FROM sellers)
                            RETURNING id
                        )
                        SELECT COUNT(*) FROM sellers
                        """.formatted(AT_HIGH_PRICE))
                .getSingleResult();
        return count.intValue();
    }

    /**
     * Traders take one unit from a low-price city market into inventory.
     */
    int buyFromCities() {
        Number count = (Number) entityManager.createNativeQuery("""
                        WITH buyers AS (
                            SELECT a.id AS agent_id,
                                   m.id AS market_id,
                                   m.resource_type AS resource_type,
                                   COALESCE(inv.carried, 0) AS carried,
                                   ROW_NUMBER() OVER (PARTITION BY m.id ORDER BY a.id) AS take_order
                            FROM agent a
                            JOIN tile t
                              ON t.x = FLOOR(ST_X(a.location))::int
                             AND t.y = FLOOR(ST_Y(a.location))::int
                            JOIN market m
                              ON m.city_id = t.city_id
                             AND m.resource_type = a.trade_resource
                            LEFT JOIN (
                                SELECT agent_id, SUM(quantity) AS carried
                                FROM agent_inventory
                                GROUP BY agent_id
                            ) inv ON inv.agent_id = a.id
                            WHERE a.job = 'TRADER'
                              AND a.trade_resource IS NOT NULL
                              AND t.terraintype = 'city'
                              AND t.city_id IS NOT NULL
                              AND COALESCE(inv.carried, 0) < :capacity
                              AND %s
                        ),
                        taken AS (
                            SELECT b.*
                            FROM buyers b
                            JOIN market m ON m.id = b.market_id
                            WHERE b.take_order <= m.stock
                        ),
                        upd_market AS (
                            UPDATE market m
                            SET stock = m.stock - s.n
                            FROM (
                                SELECT market_id, COUNT(*)::int AS n
                                FROM taken
                                GROUP BY market_id
                            ) s
                            WHERE m.id = s.market_id
                            RETURNING m.id
                        ),
                        upd_inv AS (
                            INSERT INTO agent_inventory (agent_id, resource_type, quantity)
                            SELECT agent_id, resource_type, 1 FROM taken
                            ON CONFLICT (agent_id, resource_type)
                            DO UPDATE SET quantity = agent_inventory.quantity + 1
                            RETURNING agent_id
                        ),
                        clr AS (
                            UPDATE agent
                            SET currentpath = '[]'::jsonb
                            WHERE id IN (
                                SELECT agent_id FROM taken WHERE carried + 1 >= :capacity
                            )
                            RETURNING id
                        )
                        SELECT COUNT(*) FROM taken
                        """.formatted(AT_LOW_PRICE))
                .setParameter("capacity", BehaviorService.INVENTORY_CAPACITY)
                .getSingleResult();
        return count.intValue();
    }

    /**
     * One unit per standing harvester, without overdrawing a tile. Full inventories drop their path.
     * Resource type is kept at quantity 0 so patches can regenerate.
     */
    int harvest() {
        Number count = (Number) entityManager.createNativeQuery("""
                        WITH harvesters AS (
                            SELECT a.id AS agent_id,
                                   t.id AS tile_id,
                                   t.resourcetype AS resource_type,
                                   COALESCE(inv.carried, 0) AS carried,
                                   ROW_NUMBER() OVER (PARTITION BY t.id ORDER BY a.id) AS take_order
                            FROM agent a
                            JOIN tile t
                              ON t.x = FLOOR(ST_X(a.location))::int
                             AND t.y = FLOOR(ST_Y(a.location))::int
                            LEFT JOIN (
                                SELECT agent_id, SUM(quantity) AS carried
                                FROM agent_inventory
                                GROUP BY agent_id
                            ) inv ON inv.agent_id = a.id
                            WHERE a.job IS NOT NULL
                              AND a.job IS DISTINCT FROM 'TRADER'
                              AND t.resourcetype IS NOT NULL
                              AND t.quantity > 0
                              AND COALESCE(inv.carried, 0) < :capacity
                              AND %s
                        ),
                        taken AS (
                            SELECT h.*
                            FROM harvesters h
                            JOIN tile t ON t.id = h.tile_id
                            WHERE h.take_order <= t.quantity
                        ),
                        upd_tiles AS (
                            UPDATE tile t
                            SET quantity = t.quantity - s.n
                            FROM (
                                SELECT tile_id, COUNT(*)::int AS n
                                FROM taken
                                GROUP BY tile_id
                            ) s
                            WHERE t.id = s.tile_id
                            RETURNING t.id
                        ),
                        upd_inv AS (
                            INSERT INTO agent_inventory (agent_id, resource_type, quantity)
                            SELECT agent_id, resource_type, 1 FROM taken
                            ON CONFLICT (agent_id, resource_type)
                            DO UPDATE SET quantity = agent_inventory.quantity + 1
                            RETURNING agent_id
                        ),
                        clr AS (
                            UPDATE agent
                            SET currentpath = '[]'::jsonb
                            WHERE id IN (
                                SELECT agent_id FROM taken WHERE carried + 1 >= :capacity
                            )
                            RETURNING id
                        )
                        SELECT COUNT(*) FROM taken
                        """.formatted(JOB_HARVESTS))
                .setParameter("capacity", BehaviorService.INVENTORY_CAPACITY)
                .getSingleResult();
        return count.intValue();
    }

    /**
     * Advance every agent with a remaining waypoint, except those currently trading or harvesting.
     * Tile lookup is in a subquery so Postgres can use tile(x,y) and LATERAL can see the agent.
     */
    int moveAlongPaths() {
        return entityManager.createNativeQuery("""
                        UPDATE agent a
                        SET location = ST_SetSRID(
                                ST_MakePoint(
                                    (a.currentpath->0->>'x')::double precision,
                                    (a.currentpath->0->>'y')::double precision
                                ),
                                4326
                            ),
                            currentpath = a.currentpath - 0
                        FROM (
                            SELECT a2.id,
                                   t.terraintype,
                                   t.city_id,
                                   t.resourcetype,
                                   t.quantity,
                                   COALESCE(inv.carried, 0) AS carried
                            FROM agent a2
                            JOIN tile t
                              ON t.x = FLOOR(ST_X(a2.location))::int
                             AND t.y = FLOOR(ST_Y(a2.location))::int
                            LEFT JOIN (
                                SELECT agent_id, SUM(quantity) AS carried
                                FROM agent_inventory
                                GROUP BY agent_id
                            ) inv ON inv.agent_id = a2.id
                            WHERE jsonb_typeof(a2.currentpath) = 'array'
                              AND jsonb_array_length(a2.currentpath) > 0
                        ) src
                        WHERE a.id = src.id
                          AND NOT (
                                (
                                    src.terraintype = 'city'
                                AND src.city_id IS NOT NULL
                                AND src.carried > 0
                                AND a.job IS DISTINCT FROM 'TRADER'
                                )
                             OR (
                                    a.job = 'TRADER'
                                AND src.terraintype = 'city'
                                AND src.city_id IS NOT NULL
                                AND src.carried > 0
                                AND EXISTS (
                                        SELECT 1
                                        FROM agent_inventory i
                                        JOIN market m
                                          ON m.city_id = src.city_id
                                         AND m.resource_type = i.resource_type
                                        WHERE i.agent_id = a.id
                                          AND i.quantity > 0
                                          AND %s
                                    )
                                )
                             OR (
                                    a.job = 'TRADER'
                                AND src.terraintype = 'city'
                                AND src.city_id IS NOT NULL
                                AND src.carried < :capacity
                                AND a.trade_resource IS NOT NULL
                                AND EXISTS (
                                        SELECT 1 FROM market m
                                        WHERE m.city_id = src.city_id
                                          AND m.resource_type = a.trade_resource
                                          AND %s
                                    )
                                )
                             OR (
                                    src.resourcetype IS NOT NULL
                                AND src.quantity > 0
                                AND src.carried < :capacity
                                AND %s
                                )
                          )
                        """.formatted(
                                AT_HIGH_PRICE,
                                AT_LOW_PRICE,
                                Job.sqlHarvestMatch("a.job", "src.resourcetype")))
                .setParameter("capacity", BehaviorService.INVENTORY_CAPACITY)
                .executeUpdate();
    }

    int regenerateResources() {
        return entityManager.createNativeQuery("""
                        UPDATE tile
                        SET quantity = quantity + 1
                        WHERE resourcetype IS NOT NULL
                          AND %s
                        """.formatted(TILE_BELOW_CAP))
                .executeUpdate();
    }

    /**
     * Snapshot of what NPCs are doing right now (not this-tick event counts).
     */
    Census census() {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                        WITH classified AS (
                            SELECT CASE
                                WHEN a.job IS NOT NULL
                                 AND a.job IS DISTINCT FROM 'TRADER'
                                 AND t.resourcetype IS NOT NULL
                                 AND t.quantity > 0
                                 AND COALESCE(inv.carried, 0) < :capacity
                                 AND %s THEN 'harvesting'
                                WHEN a.job = 'TRADER'
                                 AND t.terraintype = 'city'
                                 AND t.city_id IS NOT NULL
                                 AND COALESCE(inv.carried, 0) < :capacity
                                 AND a.trade_resource IS NOT NULL THEN 'buying'
                                WHEN t.terraintype = 'city'
                                 AND COALESCE(inv.carried, 0) > 0 THEN 'depositing'
                                WHEN jsonb_typeof(a.currentpath) = 'array'
                                 AND jsonb_array_length(a.currentpath) > 0 THEN 'walking'
                                ELSE 'needRoute'
                            END AS activity
                            FROM agent a
                            LEFT JOIN tile t
                              ON t.x = FLOOR(ST_X(a.location))::int
                             AND t.y = FLOOR(ST_Y(a.location))::int
                            LEFT JOIN (
                                SELECT agent_id, SUM(quantity) AS carried
                                FROM agent_inventory
                                GROUP BY agent_id
                            ) inv ON inv.agent_id = a.id
                        )
                        SELECT
                            COUNT(*)::int,
                            COUNT(*) FILTER (WHERE activity = 'walking')::int,
                            COUNT(*) FILTER (WHERE activity = 'harvesting')::int,
                            COUNT(*) FILTER (WHERE activity = 'buying')::int,
                            COUNT(*) FILTER (WHERE activity = 'depositing')::int,
                            COUNT(*) FILTER (WHERE activity = 'needRoute')::int
                        FROM classified
                        """.formatted(JOB_HARVESTS))
                .setParameter("capacity", BehaviorService.INVENTORY_CAPACITY)
                .getSingleResult();
        return new Census(
                ((Number) row[0]).intValue(),
                ((Number) row[1]).intValue(),
                ((Number) row[2]).intValue(),
                ((Number) row[3]).intValue(),
                ((Number) row[4]).intValue(),
                ((Number) row[5]).intValue());
    }

    /** Compact id/x/y list for the live WebSocket. */
    @Transactional
    @SuppressWarnings("unchecked")
    public List<AgentPosDto> agentPositions() {
        List<Object[]> rows = entityManager
                .createNativeQuery("SELECT id, ST_X(location), ST_Y(location) FROM agent")
                .getResultList();
        List<AgentPosDto> agents = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            agents.add(new AgentPosDto(
                    ((Number) row[0]).longValue(),
                    ((Number) row[1]).doubleValue(),
                    ((Number) row[2]).doubleValue()));
        }
        return agents;
    }
}
