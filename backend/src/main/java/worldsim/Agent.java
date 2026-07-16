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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.OrderColumn;
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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_inventory", joinColumns = @JoinColumn(name = "agent_id"))
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "resource_type")
    @Column(name = "quantity")
    public Map<ResourceType, Integer> inventory = new EnumMap<>(ResourceType.class);

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "agent_path",
            joinColumns = @JoinColumn(name = "agent_id"),
            inverseJoinColumns = @JoinColumn(name = "tile_id"))
    @OrderColumn(name = "step_order")
    public List<Tile> currentPath = new ArrayList<>();

    public void addToInventory(ResourceType type, int amount) {
        inventory.merge(type, amount, Integer::sum);
    }

    public int inventoryCount() {
        return inventory.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int carried(ResourceType type) {
        return inventory.getOrDefault(type, 0);
    }

    public boolean isInventoryFull() {
        return inventoryCount() >= BehaviorService.INVENTORY_CAPACITY;
    }

    public void clearInventory() {
        inventory.clear();
    }

    public static List<Agent> all() {
        return getEntityManager().createQuery("from Agent", Agent.class).getResultList();
    }
}
