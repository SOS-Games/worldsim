package worldsim;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Entity
public class Agent extends PanacheEntity {
    public String name;

    @Column(columnDefinition = "geometry(Point, 4326)")
    public Point location;

    public double speed;

    @Column(columnDefinition = "geometry(Point, 4326)")
    public Point targetLocation;

    @Enumerated(EnumType.STRING)
    public Job job;

    /** Resource a trader is currently buying or selling. */
    @Enumerated(EnumType.STRING)
    @Column(name = "trade_resource")
    public ResourceType tradeResource;

    @Column(name = "vehicle_id")
    public Long vehicleId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_inventory", joinColumns = @JoinColumn(name = "agent_id"))
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "resource_type")
    @Column(name = "quantity")
    public Map<ResourceType, Integer> inventory = new EnumMap<>(ResourceType.class);

    /** Remaining waypoints as JSON — avoids heavy join-table updates each tick. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public List<PathPoint> currentPath = new ArrayList<>();

    public void addToInventory(ResourceType type, int amount) {
        inventory.merge(type, amount, Integer::sum);
    }

    public int inventoryCount() {
        return inventory.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int carried(ResourceType type) {
        return inventory.getOrDefault(type, 0);
    }

    public int inventoryCapacity() {
        if (vehicleId == null) {
            return BehaviorService.INVENTORY_CAPACITY;
        }
        Vehicle vehicle = Vehicle.findById(vehicleId);
        if (vehicle != null && vehicle.type == VehicleType.WAGON) {
            return BehaviorService.WAGON_CAPACITY;
        }
        return BehaviorService.INVENTORY_CAPACITY;
    }

    public boolean isInventoryFull() {
        return inventoryCount() >= inventoryCapacity();
    }

    public boolean hasVehicle() {
        return vehicleId != null;
    }

    public MovementMode movementMode(Vehicle vehicle) {
        if (vehicle == null || vehicleId == null || !vehicle.id.equals(vehicleId)) {
            return MovementMode.WALK;
        }
        return MovementMode.of(vehicle.type);
    }

    public void clearInventory() {
        inventory.clear();
    }

    public static List<Agent> all() {
        return getEntityManager().createQuery("from Agent", Agent.class).getResultList();
    }
}
