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

    public boolean isPassable() {
        return !"mountain".equals(terrainType);
    }

    public boolean hasResource() {
        return resourceType != null && quantity > 0;
    }

    public boolean isCity() {
        return "city".equals(terrainType);
    }

    public static Tile findNearest(Point point) {
        return find("ORDER BY distance(location, ?1) ASC", point).firstResult();
    }

    public static Tile findNearestCity(Point point) {
        return find("terrainType = ?1 ORDER BY distance(location, ?2) ASC", "city", point)
                .firstResult();
    }

    public static Tile findNearestResource(ResourceType type, Point point) {
        return find(
                        "resourceType = ?1 and quantity > 0 ORDER BY distance(location, ?2) ASC",
                        type,
                        point)
                .firstResult();
    }

    public static Tile findByGrid(int x, int y) {
        return find("x = ?1 and y = ?2", x, y).firstResult();
    }

    public static List<Tile> all() {
        return getEntityManager().createQuery("from Tile", Tile.class).getResultList();
    }
}
