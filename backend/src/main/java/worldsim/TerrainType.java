package worldsim;

/**
 * Ground cover. Resource patches use a biome ({@code forest}, {@code farm}, …) plus a resource type.
 */
public enum TerrainType {
    WATER,
    GRASS,
    MOUNTAIN,
    CITY,
    FOREST,
    FARM,
    MINE,
    QUARRY,
    VEIN,
    MEADOW;

    public String code() {
        return name().toLowerCase();
    }

    public boolean passable() {
        return this != MOUNTAIN && this != WATER;
    }

    public static boolean isPassable(String code) {
        return !MOUNTAIN.code().equals(code) && !WATER.code().equals(code);
    }
}
