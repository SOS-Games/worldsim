package worldsim;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

import java.util.List;

@Entity
@Table(name = "vehicle")
public class Vehicle extends PanacheEntity {
    @Enumerated(EnumType.STRING)
    public VehicleType type;

    @Column(columnDefinition = "geometry(Point, 4326)")
    public Point location;

    public double speed;

    @Column(name = "occupant_id")
    public Long occupantId;

    @Column(name = "home_city_id")
    public Long homeCityId;

    public boolean isBoat() {
        return type == VehicleType.BOAT;
    }

    public static List<Vehicle> all() {
        return listAll();
    }

    public static Vehicle forAgent(Long agentId) {
        if (agentId == null) {
            return null;
        }
        return find("occupantId", agentId).firstResult();
    }
}
