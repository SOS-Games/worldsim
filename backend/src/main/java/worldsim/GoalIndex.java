package worldsim;

import jakarta.persistence.EntityManager;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-tick snapshot of patches, claims, cities, and markets so AI can pick goals
 * without a SQL round-trip per NPC.
 */
final class GoalIndex {

    private record Patch(int x, int y, int quantity, ResourceType type) {}

    private static volatile Map<Long, Long> TILE_IDS = Map.of();

    private final Map<ResourceType, List<Patch>> patches;
    private final Map<Long, Integer> claimed;
    private final List<Market> markets;
    private final List<City> cities;
    private final List<City> harbors;
    private final Set<Long> cityTiles;
    private final Set<Long> openWater;
    private final Map<Long, VehicleType> vehiclesByAgent;

    private GoalIndex(
            Map<ResourceType, List<Patch>> patches,
            Map<Long, Integer> claimed,
            List<Market> markets,
            List<City> cities,
            List<City> harbors,
            Set<Long> cityTiles,
            Set<Long> openWater,
            Map<Long, VehicleType> vehiclesByAgent) {
        this.patches = patches;
        this.claimed = claimed;
        this.markets = markets;
        this.cities = cities;
        this.harbors = harbors;
        this.cityTiles = cityTiles;
        this.openWater = openWater;
        this.vehiclesByAgent = vehiclesByAgent;
    }

    @SuppressWarnings("unchecked")
    static GoalIndex load(EntityManager entityManager) {
        Map<ResourceType, List<Patch>> patches = new EnumMap<>(ResourceType.class);
        List<Object[]> patchRows = entityManager.createNativeQuery("""
                        SELECT x, y, quantity, resourcetype
                        FROM tile
                        WHERE resourcetype IS NOT NULL AND quantity > 0
                        """)
                .getResultList();
        for (Object[] row : patchRows) {
            ResourceType type = ResourceType.valueOf(row[3].toString());
            patches.computeIfAbsent(type, key -> new ArrayList<>())
                    .add(new Patch(
                            ((Number) row[0]).intValue(),
                            ((Number) row[1]).intValue(),
                            ((Number) row[2]).intValue(),
                            type));
        }

        Map<Long, Integer> claimed = new HashMap<>();
        List<Object[]> claimRows = entityManager.createNativeQuery("""
                        SELECT FLOOR(ST_X(targetlocation))::int,
                               FLOOR(ST_Y(targetlocation))::int,
                               COUNT(*)::int
                        FROM agent
                        WHERE targetlocation IS NOT NULL
                        GROUP BY 1, 2
                        """)
                .getResultList();
        for (Object[] row : claimRows) {
            claimed.put(key(((Number) row[0]).intValue(), ((Number) row[1]).intValue()), ((Number) row[2]).intValue());
        }

        ensureTileIds(entityManager);

        Set<Long> cityTiles = new HashSet<>();
        List<Object[]> cityRows = entityManager.createNativeQuery("""
                        SELECT x, y FROM tile WHERE terraintype = 'city'
                        """)
                .getResultList();
        for (Object[] row : cityRows) {
            cityTiles.add(key(((Number) row[0]).intValue(), ((Number) row[1]).intValue()));
        }
        Set<Long> openWater = new HashSet<>();
        List<Object[]> waterRows = entityManager.createNativeQuery("""
                        SELECT x, y FROM tile
                        WHERE terraintype = 'water'
                          AND infrastructuretype IS DISTINCT FROM 'bridge'
                        """)
                .getResultList();
        for (Object[] row : waterRows) {
            openWater.add(key(((Number) row[0]).intValue(), ((Number) row[1]).intValue()));
        }

        List<City> cities = City.all();
        List<City> harbors = new ArrayList<>();
        for (City city : cities) {
            if (city.harbor) {
                harbors.add(city);
            }
        }

        Map<Long, VehicleType> vehiclesByAgent = new HashMap<>();
        for (Vehicle vehicle : Vehicle.all()) {
            if (vehicle.occupantId != null && vehicle.type != null) {
                vehiclesByAgent.put(vehicle.occupantId, vehicle.type);
            }
        }
        return new GoalIndex(
                patches, claimed, Market.all(), cities, harbors, cityTiles, openWater, vehiclesByAgent);
    }

    static void clearTileIds() {
        TILE_IDS = Map.of();
    }

    static Long tileId(Point point) {
        if (point == null) {
            return null;
        }
        return TILE_IDS.get(key((int) Math.floor(point.getX()), (int) Math.floor(point.getY())));
    }

    @SuppressWarnings("unchecked")
    private static void ensureTileIds(EntityManager entityManager) {
        if (TILE_IDS.size() == WorldConfig.EXPECTED_TILE_COUNT) {
            return;
        }
        Map<Long, Long> ids = new HashMap<>();
        List<Object[]> rows = entityManager.createNativeQuery("SELECT id, x, y FROM tile").getResultList();
        for (Object[] row : rows) {
            ids.put(
                    key(((Number) row[1]).intValue(), ((Number) row[2]).intValue()),
                    ((Number) row[0]).longValue());
        }
        TILE_IDS = Map.copyOf(ids);
    }

    boolean needsPath(Agent agent) {
        if (agent.job == null || agent.location == null) {
            return false;
        }
        if (!vehiclesByAgent.containsKey(agent.id) && atHarbor(agent)) {
            City here = harborAt(agent);
            if (here != null && here.boatStock > 0) {
                return false;
            }
            City stocked = nearestStockedHarbor(agent);
            if (stocked == null) {
                return false;
            }
            return agent.currentPath == null || agent.currentPath.isEmpty();
        }
        if (agent.currentPath == null || agent.currentPath.isEmpty()) {
            return true;
        }
        if (agent.targetLocation == null) {
            return true;
        }
        int tx = (int) Math.floor(agent.targetLocation.getX());
        int ty = (int) Math.floor(agent.targetLocation.getY());
        if (agent.job.isTrader()) {
            return !isCity(tx, ty);
        }
        boolean shouldDeliver = agent.inventoryCount() >= capacity(agent);
        if (shouldDeliver) {
            return !isCity(tx, ty);
        }
        ResourceType want = agent.job.harvests();
        int need = capacity(agent) - agent.inventoryCount();
        Patch patch = patchAt(want, tx, ty);
        return want == null || patch == null || patch.quantity < need;
    }

    private Patch patchAt(ResourceType want, int x, int y) {
        if (want == null) {
            return null;
        }
        for (Patch patch : patches.getOrDefault(want, List.of())) {
            if (patch.x == x && patch.y == y) {
                return patch;
            }
        }
        return null;
    }

    private boolean isCity(int x, int y) {
        return cityTiles.contains(key(x, y));
    }

    int size() {
        int n = 0;
        for (List<Patch> list : patches.values()) {
            n += list.size();
        }
        return n;
    }

    void assign(Agent agent) {
        if (agent.job == null) {
            return;
        }
        if (agent.job.isTrader()) {
            assignTrader(agent);
            maybeFetchBoat(agent);
            return;
        }
        if (agent.inventoryCount() >= capacity(agent)) {
            setNearestCity(agent);
        } else {
            setBestResource(agent);
        }
        maybeFetchBoat(agent);
    }

    private void maybeFetchBoat(Agent agent) {
        if (vehiclesByAgent.containsKey(agent.id) || harbors.isEmpty() || agent.targetLocation == null) {
            return;
        }
        if (atHarbor(agent)) {
            City here = harborAt(agent);
            if (here != null && here.boatStock > 0) {
                setCity(agent, here);
                return;
            }
            City stocked = nearestStockedHarbor(agent);
            if (stocked != null) {
                setCity(agent, stocked);
            }
            return;
        }
        if (!wantsBoat(agent)) {
            return;
        }
        if (nearestStockedHarbor(agent) == null) {
            return;
        }
        if (openWaterOnLine(agent.location, agent.targetLocation) < WorldConfig.WATER_CROSSING_TILES) {
            return;
        }
        setNearestHarbor(agent);
    }

    private boolean wantsBoat(Agent agent) {
        if (agent.job != null && agent.job.isTrader()) {
            return true;
        }
        ResourceType harvests = agent.job == null ? null : agent.job.harvests();
        return harvests == ResourceType.GOLD
                || harvests == ResourceType.IRON
                || harvests == ResourceType.STONE;
    }

    private City nearestStockedHarbor(Agent agent) {
        City best = null;
        double bestDist = Double.MAX_VALUE;
        for (City harbor : harbors) {
            if (harbor.boatStock <= 0) {
                continue;
            }
            double dist = dist2(agent.location, harbor.x, harbor.y);
            if (best == null || dist < bestDist) {
                best = harbor;
                bestDist = dist;
            }
        }
        return best;
    }

    private void setNearestHarbor(Agent agent) {
        City best = nearestStockedHarbor(agent);
        if (best == null) {
            return;
        }
        setCity(agent, best);
    }

    private boolean atHarbor(Agent agent) {
        return harborAt(agent) != null;
    }

    private City harborAt(Agent agent) {
        int x = (int) Math.floor(agent.location.getX());
        int y = (int) Math.floor(agent.location.getY());
        int radius = WorldConfig.VILLAGE_RADIUS;
        for (City harbor : harbors) {
            if (Math.abs(harbor.x - x) <= radius && Math.abs(harbor.y - y) <= radius) {
                return harbor;
            }
        }
        return null;
    }

    private int openWaterOnLine(Point from, Point to) {
        int x0 = (int) Math.floor(from.getX());
        int y0 = (int) Math.floor(from.getY());
        int x1 = (int) Math.floor(to.getX());
        int y1 = (int) Math.floor(to.getY());
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int water = 0;
        while (true) {
            if (openWater.contains(key(x0, y0))) {
                water++;
            }
            if (x0 == x1 && y0 == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
        return water;
    }

    private void setBestResource(Agent agent) {
        ResourceType want = agent.job.harvests();
        if (want == null) {
            return;
        }
        int x = (int) Math.floor(agent.location.getX());
        int y = (int) Math.floor(agent.location.getY());
        if (standingOn(want, x, y)) {
            agent.targetLocation = agent.location;
            return;
        }

        int need = Math.max(1, capacity(agent) - agent.inventoryCount());
        List<Patch> options = patches.getOrDefault(want, List.of());
        Patch best = null;
        int bestFill = -1;
        int bestClaimed = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (Patch patch : options) {
            int fill = Math.min(patch.quantity, need);
            int n = claimed.getOrDefault(key(patch.x, patch.y), 0);
            double dist = dist2(agent.location, patch.x, patch.y);
            if (best == null
                    || fill > bestFill
                    || (fill == bestFill && n < bestClaimed)
                    || (fill == bestFill && n == bestClaimed && dist < bestDist)) {
                best = patch;
                bestFill = fill;
                bestClaimed = n;
                bestDist = dist;
            }
        }
        if (best == null) {
            setNearestCity(agent);
            return;
        }
        claimed.merge(key(best.x, best.y), 1, Integer::sum);
        agent.targetLocation = GeometryFactoryHolder.createPoint(best.x + 0.5, best.y + 0.5);
        agent.currentPath = new ArrayList<>();
    }

    private boolean standingOn(ResourceType want, int x, int y) {
        return patchAt(want, x, y) != null;
    }

    private void assignTrader(Agent agent) {
        ResourceType carried = carriedType(agent);
        if (carried != null) {
            agent.tradeResource = carried;
            Market sellAt = highestPrice(carried);
            if (sellAt != null) {
                setCity(agent, sellAt.city);
            } else {
                setNearestCity(agent);
            }
            return;
        }

        Deal deal = bestDeal();
        if (deal == null) {
            agent.tradeResource = null;
            setNearestCity(agent);
            return;
        }
        agent.tradeResource = deal.resource;
        setCity(agent, deal.buyCity);
    }

    private static void setCity(Agent agent, City city) {
        agent.targetLocation = GeometryFactoryHolder.createPoint(city.x + 0.5, city.y + 0.5);
        agent.currentPath = new ArrayList<>();
    }

    private void setNearestCity(Agent agent) {
        Point from = agent.location;
        City best = cities.isEmpty() ? null : cities.get(0);
        double bestDist = best == null ? Double.MAX_VALUE : dist2(from, best.x, best.y);
        for (int i = 1; i < cities.size(); i++) {
            City city = cities.get(i);
            double dist = dist2(from, city.x, city.y);
            if (dist < bestDist) {
                best = city;
                bestDist = dist;
            }
        }
        if (best != null) {
            setCity(agent, best);
        }
    }

    private Market highestPrice(ResourceType type) {
        Market best = null;
        for (Market listing : markets) {
            if (listing.resourceType != type) {
                continue;
            }
            if (best == null || listing.currentPrice() > best.currentPrice()) {
                best = listing;
            }
        }
        return best;
    }

    private Deal bestDeal() {
        Map<ResourceType, List<Market>> byType = new EnumMap<>(ResourceType.class);
        for (Market listing : markets) {
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
                best = new Deal(type, buy.city, spread);
            }
        }
        return best;
    }

    private int capacity(Agent agent) {
        return vehiclesByAgent.get(agent.id) == VehicleType.WAGON
                ? BehaviorService.WAGON_CAPACITY
                : BehaviorService.INVENTORY_CAPACITY;
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

    private static double dist2(Point from, int x, int y) {
        double dx = from.getX() - (x + 0.5);
        double dy = from.getY() - (y + 0.5);
        return dx * dx + dy * dy;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private record Deal(ResourceType resource, City buyCity, double spread) {}
}
