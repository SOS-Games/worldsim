package worldsim;

import jakarta.persistence.EntityManager;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private GoalIndex(
            Map<ResourceType, List<Patch>> patches, Map<Long, Integer> claimed, List<Market> markets) {
        this.patches = patches;
        this.claimed = claimed;
        this.markets = markets;
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
        return new GoalIndex(patches, claimed, Market.all());
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
        boolean shouldDeliver = agent.inventoryCount() >= BehaviorService.INVENTORY_CAPACITY;
        if (shouldDeliver) {
            return !isCity(tx, ty);
        }
        ResourceType want = agent.job.harvests();
        int need = BehaviorService.INVENTORY_CAPACITY - agent.inventoryCount();
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

    private static boolean isCity(int x, int y) {
        int radius = WorldConfig.CITY_RADIUS;
        for (int[] center : WorldConfig.CITY_CENTERS) {
            if (Math.abs(center[0] - x) <= radius && Math.abs(center[1] - y) <= radius) {
                return true;
            }
        }
        return false;
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
            return;
        }
        if (agent.inventoryCount() >= BehaviorService.INVENTORY_CAPACITY) {
            setNearestCity(agent);
        } else {
            setBestResource(agent);
        }
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

        int need = Math.max(1, BehaviorService.INVENTORY_CAPACITY - agent.inventoryCount());
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

    static void setNearestCity(Agent agent) {
        Point from = agent.location;
        int[] best = WorldConfig.CITY_CENTERS[0];
        double bestDist = dist2(from, best[0], best[1]);
        for (int i = 1; i < WorldConfig.CITY_CENTERS.length; i++) {
            int[] center = WorldConfig.CITY_CENTERS[i];
            double dist = dist2(from, center[0], center[1]);
            if (dist < bestDist) {
                best = center;
                bestDist = dist;
            }
        }
        agent.targetLocation = GeometryFactoryHolder.createPoint(best[0] + 0.5, best[1] + 0.5);
        agent.currentPath = new ArrayList<>();
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
