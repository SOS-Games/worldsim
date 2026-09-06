package worldsim;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class BehaviorService {

    public static final int INVENTORY_CAPACITY = WorldConfig.INVENTORY_CAPACITY;
    public static final int WAGON_CAPACITY = WorldConfig.WAGON_CAPACITY;

    public static String capacitySql(String agentTable) {
        return "CASE WHEN EXISTS (SELECT 1 FROM vehicle _cap WHERE _cap.id = "
                + agentTable + ".vehicle_id AND _cap.type = 'WAGON') THEN "
                + WAGON_CAPACITY + " ELSE " + INVENTORY_CAPACITY + " END";
    }

    @Inject
    EntityManager entityManager;

    private GoalIndex tickGoals;

    /**
     * Load patches, claims, and markets once per AI tick so assignGoal avoids per-NPC SQL.
     */
    int beginTick() {
        tickGoals = GoalIndex.load(entityManager);
        return tickGoals.size();
    }

    void endTick() {
        tickGoals = null;
    }

    boolean needsRoute(Agent agent) {
        return tickGoals != null && tickGoals.needsPath(agent);
    }

    Long tileId(org.locationtech.jts.geom.Point point) {
        return GoalIndex.tileId(point);
    }

    /**
     * Gatherers: full inventory → nearest city, else a stocked job patch.
     * Traders: empty → cheapest city with a price gap, cargo → most expensive city.
     */
    public void assignGoal(Agent agent) {
        if (tickGoals != null) {
            tickGoals.assign(agent);
            return;
        }
        if (agent.job == null) {
            return;
        }
        if (agent.job.isTrader()) {
            assignTraderGoal(agent);
            return;
        }
        if (agent.inventoryCount() >= agent.inventoryCapacity()) {
            setTargetToNearestCity(agent);
        } else {
            setTargetToBestResource(agent);
        }
    }

    public void setTargetToNearestCity(Agent agent) {
        Tile city = Tile.findNearestCity(agent.location);
        if (city != null) {
            agent.targetLocation = city.location;
            agent.currentPath = new ArrayList<>();
        }
    }

    public void setTargetToBestResource(Agent agent) {
        ResourceType want = agent.job.harvests();
        if (want == null) {
            return;
        }
        Tile here = Tile.atPoint(agent.location);
        if (here != null && here.resourceType == want && here.quantity > 0) {
            agent.targetLocation = here.location;
            return;
        }

        int need = agent.inventoryCapacity() - agent.inventoryCount();
        Tile resource = Tile.findBestResource(want, agent.location, need, agent.id);
        if (resource != null) {
            agent.targetLocation = resource.location;
            agent.currentPath = new ArrayList<>();
        } else {
            setTargetToNearestCity(agent);
        }
    }

    void assignTraderGoal(Agent agent) {
        ResourceType carried = carriedType(agent);
        if (carried != null) {
            agent.tradeResource = carried;
            Market sellAt = highestPrice(carried);
            if (sellAt != null) {
                setTargetToCity(agent, sellAt.city);
            } else {
                setTargetToNearestCity(agent);
            }
            return;
        }

        Deal deal = bestDeal();
        if (deal == null) {
            agent.tradeResource = null;
            setTargetToNearestCity(agent);
            return;
        }
        agent.tradeResource = deal.resource;
        setTargetToCity(agent, deal.buyCity);
    }

    private static void setTargetToCity(Agent agent, City city) {
        Tile tile = Tile.findByGrid(city.x, city.y);
        if (tile == null) {
            tile = Tile.findNearestCity(agent.location);
        }
        if (tile != null) {
            agent.targetLocation = tile.location;
            agent.currentPath = new ArrayList<>();
        }
    }

    private static ResourceType carriedType(Agent agent) {
        if (agent.inventory == null) {
            return null;
        }
        for (Map.Entry<ResourceType, Integer> entry : agent.inventory.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static Deal bestDeal() {
        Map<ResourceType, List<Market>> byType = new EnumMap<>(ResourceType.class);
        for (Market listing : Market.all()) {
            byType.computeIfAbsent(listing.resourceType, key -> new ArrayList<>()).add(listing);
        }

        Deal best = null;
        for (ResourceType type : ResourceType.values()) {
            List<Market> listings = byType.get(type);
            if (listings == null || listings.size() < 2) {
                continue;
            }
            Market buy = listings.stream()
                    .filter(listing -> listing.stock > 0)
                    .min(Comparator.comparingDouble(Market::currentPrice))
                    .orElse(null);
            Market sell = listings.stream()
                    .max(Comparator.comparingDouble(Market::currentPrice))
                    .orElse(null);
            if (buy == null || sell == null || buy.city.id.equals(sell.city.id)) {
                continue;
            }
            double spread = sell.currentPrice() - buy.currentPrice();
            if (spread < 0.01) {
                continue;
            }
            if (best == null || spread > best.spread) {
                best = new Deal(type, buy.city, sell.city, spread);
            }
        }
        return best;
    }

    private static Market highestPrice(ResourceType type) {
        return Market.all().stream()
                .filter(listing -> listing.resourceType == type)
                .max(Comparator.comparingDouble(Market::currentPrice))
                .orElse(null);
    }

    private record Deal(ResourceType resource, City buyCity, City sellCity, double spread) {}
}
