package worldsim;

public final class WorldConfig {
    public static final int MAP_WIDTH = 100;
    public static final int MAP_HEIGHT = 100;
    public static final int AGENT_COUNT = 240;
    public static final int EXPECTED_TILE_COUNT = MAP_WIDTH * MAP_HEIGHT;

    public static final int REGEN_EVERY_TICKS = 10;
    public static final int MAX_AI_PER_TICK = 16;

    public static final int CITY_RADIUS = 2;
    public static final int MARKET_TARGET_STOCK = 50;

    public static final int[][] CITY_CENTERS = {
            {15, 15}, {50, 12}, {85, 18},
            {12, 50}, {50, 50}, {88, 50},
            {18, 85}, {50, 88}, {82, 85}
    };

    public static final String[] CITY_NAMES = {
            "Hillford", "Northport", "Easthaven",
            "Westmere", "Midkeep", "Saltgate",
            "Southwick", "Riverbend", "Ashfield"
    };

    private WorldConfig() {}
}
