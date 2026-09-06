package worldsim;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.locationtech.jts.geom.Point;

import java.util.List;

@Entity
public class Tile extends PanacheEntity {
    public int x;
    public int y;
    public String terrainType;

    @Column(columnDefinition = "geometry(Point, 4326)")
    public Point location;

    @Enumerated(EnumType.STRING)
    public ResourceType resourceType;

    public int quantity;

    @Column(name = "city_id")
    public Long cityId;

    public boolean isPassable() {
        return TerrainType.isPassable(terrainType);
    }

    public boolean hasResource() {
        return resourceType != null && quantity > 0;
    }

    public boolean isCity() {
        return TerrainType.CITY.code().equals(terrainType);
    }

    public static Tile findNearest(Point point) {
        Tile exact = atPoint(point);
        if (exact != null) {
            return exact;
        }
        return find("ORDER BY distance(location, ?1) ASC", point).firstResult();
    }

    public static Tile atPoint(Point point) {
        if (point == null) {
            return null;
        }
        return findByGrid((int) Math.floor(point.getX()), (int) Math.floor(point.getY()));
    }

    public static Tile findNearestCity(Point point) {
        return find("terrainType = ?1 ORDER BY distance(location, ?2) ASC", TerrainType.CITY.code(), point)
                .firstResult();
    }

    /**
     * Prefer a tile that can fill {@code need} units; among those, spread across tiles
     * that fewer agents are already heading to. Distance is the final tie-breaker.
     */
    @SuppressWarnings("unchecked")
    public static Tile findBestResource(ResourceType type, Point point, int need, Long excludeAgentId) {
        if (type == null || point == null) {
            return null;
        }
        int want = Math.max(need, 1);
        var entityManager = getEntityManager();
        entityManager.flush();
        List<Number> ids = entityManager
                .createNativeQuery("""
                        SELECT t.id
                        FROM tile t
                        LEFT JOIN (
                            SELECT FLOOR(ST_X(a.targetlocation))::int AS tx,
                                   FLOOR(ST_Y(a.targetlocation))::int AS ty,
                                   COUNT(*)::int AS n
                            FROM agent a
                            WHERE a.targetlocation IS NOT NULL
                              AND (:excludeId IS NULL OR a.id <> :excludeId)
                            GROUP BY 1, 2
                        ) claimed ON claimed.tx = t.x AND claimed.ty = t.y
                        WHERE t.resourcetype = :type
                          AND t.quantity > 0
                        ORDER BY LEAST(t.quantity, :need) DESC,
                                 COALESCE(claimed.n, 0) ASC,
                                 ST_Distance(
                                    t.location,
                                    ST_SetSRID(ST_MakePoint(:x, :y), 4326)
                                 ) ASC
                        LIMIT 1
                        """)
                .setParameter("excludeId", excludeAgentId)
                .setParameter("type", type.name())
                .setParameter("need", want)
                .setParameter("x", point.getX())
                .setParameter("y", point.getY())
                .getResultList();
        if (ids.isEmpty()) {
            return null;
        }
        return findById(ids.get(0).longValue());
    }

    public static Tile findByGrid(int x, int y) {
        return find("x = ?1 and y = ?2", x, y).firstResult();
    }

    public static List<Tile> all() {
        return getEntityManager().createQuery("from Tile", Tile.class).getResultList();
    }
}
