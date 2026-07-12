package worldsim;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OrderColumn;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.List;

@Entity
public class Agent extends PanacheEntity {
    public String name;

    @Column(columnDefinition = "geometry(Point, 4326)")
    public Point location;

    public double speed;

    @Column(columnDefinition = "geometry(Point, 4326)")
    public Point targetLocation;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "agent_path",
            joinColumns = @JoinColumn(name = "agent_id"),
            inverseJoinColumns = @JoinColumn(name = "tile_id"))
    @OrderColumn(name = "step_order")
    public List<Tile> currentPath = new ArrayList<>();

    public static List<Agent> all() {
        return getEntityManager().createQuery("from Agent", Agent.class).getResultList();
    }
}
