package worldsim;

public final class WorldConfig {
    public static final int MAP_WIDTH = 200;
    public static final int MAP_HEIGHT = 200;
    public static final int AGENT_COUNT = 480;
    public static final int EXPECTED_TILE_COUNT = MAP_WIDTH * MAP_HEIGHT;

    public static final int REGEN_EVERY_TICKS = 10;
    public static final int MAX_AI_PER_TICK = 24;

    public static final long WORLD_SEED = 20260906L;
    public static final double WATER_LEVEL = 0.36;
    public static final double MOUNTAIN_LEVEL = 0.63;
    /** Half the original 0.038 frequency, so ranges and lakes are about 2× wider. */
    public static final double LANDFORM_FREQUENCY = 0.019;
    public static final double BIOME_FREQUENCY = 0.04;
    public static final int MOUNTAIN_MASSIFS = 8;
    public static final int MASSIF_SEPARATION = Math.max(22, mapSpan() * 22 / 100);
    public static final int MASSIF_RADIUS = Math.max(7, 7 * mapSpan() / 100);
    public static final int RIVERS_PER_RANGE = 4;
    /** One river may run ~60% of the shorter map edge. */
    public static final int RIVER_MAX_STEPS = Math.max(24, mapSpan() * 3 / 5);
    public static final int RIVER_STRAIGHT_LIMIT = 2;
    public static final int MIN_MOUNTAIN_RANGE = Math.max(25, 25 * mapSpan() / 100);
    public static final int MIN_MOUNTAIN_TILES = EXPECTED_TILE_COUNT * 4 / 100;
    public static final int MAX_MOUNTAIN_TILES = EXPECTED_TILE_COUNT * 14 / 100;
    public static final int LARGE_MOUNTAIN_SIZE = MIN_MOUNTAIN_RANGE;
    public static final int MIN_LARGE_MOUNTAIN_RANGES = 6;
    public static final int MAX_MOUNTAIN_BLOB = Math.max(400, EXPECTED_TILE_COUNT * 5 / 100);
    public static final int MAX_LAKE_LINKS = Math.max(8, mapSpan() / 20);
    public static final int MAX_LAKE_LINK_DIST = Math.max(16, mapSpan() * 18 / 100);
    public static final int MAX_SEPARATE_LAKES = Math.max(12, mapSpan() / 4);
    public static final int CANAL_RUN_LENGTH = 8;
    public static final int MAX_CANAL_RUNS = 2;
    public static final int MIN_WATER_TILES = EXPECTED_TILE_COUNT * 12 / 100;
    public static final int MAX_WATER_TILES = EXPECTED_TILE_COUNT * 40 / 100;
    public static final int MAX_FARM_TILES = EXPECTED_TILE_COUNT * 20 / 100;
    public static final int CITY_RADIUS = 2;
    public static final int VILLAGE_RADIUS = 1;
    public static final int VILLAGE_CLUSTER_RADIUS = 2;
    public static final int VILLAGE_MIN_CLUSTER = 5;
    public static final int VILLAGE_MIN_SEPARATION = Math.max(12, mapSpan() * 12 / 100);
    public static final int MAX_VILLAGES = 12;
    public static final int ROAD_LINKS_PER_VILLAGE = 2;
    public static final int ROAD_LINKS_PER_CITY = 2;
    public static final int MIN_RESOURCE_SIDE_PERCENT = 25;
    public static final int MAX_HARBORS = 3;
    public static final int HARBOR_BOAT_STOCK = 4;
    public static final int HARBOR_BOAT_CAP = 6;
    public static final int WATER_CROSSING_TILES = 4;
    public static final double BOAT_SPEED = 1.5;
    public static final double WAGON_SPEED = 1.2;
    public static final int ROAD_MOVE_STEPS = 2;
    public static final int INVENTORY_CAPACITY = 10;
    public static final int WAGON_CAPACITY = 20;
    public static final int MARKET_TARGET_STOCK = 50;

    public static final int[][] CITY_CENTERS = scaledCityCenters();

    public static final String[] CITY_NAMES = {
            "Hillford", "Northport", "Easthaven",
            "Westmere", "Midkeep", "Saltgate",
            "Southwick", "Riverbend", "Ashfield"
    };

    public static final String[] HARBOR_NAMES = {"Tidewatch", "Lakesend", "Saltquay"};

    public static int mapSpan() {
        return Math.min(MAP_WIDTH, MAP_HEIGHT);
    }

    private static int[][] scaledCityCenters() {
        int[][] on100 = {
                {15, 15}, {50, 12}, {85, 18},
                {12, 50}, {50, 50}, {88, 50},
                {18, 85}, {50, 88}, {82, 85}
        };
        int[][] out = new int[on100.length][2];
        for (int i = 0; i < on100.length; i++) {
            out[i][0] = on100[i][0] * MAP_WIDTH / 100;
            out[i][1] = on100[i][1] * MAP_HEIGHT / 100;
        }
        return out;
    }

    private WorldConfig() {}
}
