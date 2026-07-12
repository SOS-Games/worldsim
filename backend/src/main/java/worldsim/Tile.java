package worldsim;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import org.locationtech.jts.geom.Point;

import java.util.List;

@Entity
public class Tile extends PanacheEntity {
    public int x;
    public int y;
    public String terrainType;

    @Column(columnDefinition = "geometry(Point, 4326)")
    public Point location;

    public boolean isPassable() {
        return !"mountain".equals(terrainType);
    }

    public static Tile findNearest(Point point) {
        return find("ORDER BY distance(location, ?1) ASC", point).firstResult();
    }

    public static Tile findByGrid(int x, int y) {
        return find("x = ?1 and y = ?2", x, y).firstResult();
    }

    public static List<Tile> all() {
        return getEntityManager().createQuery("from Tile", Tile.class).getResultList();
    }
}
