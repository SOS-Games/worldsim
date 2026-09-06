package worldsim;

/**
 * Ground cover. Resource patches use a biome ({@code forest}, {@code farm}, …) plus a resource type.
 */
public enum TerrainType {
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
        return this != MOUNTAIN;
    }

    public static boolean isPassable(String code) {
        return !MOUNTAIN.code().equals(code);
    }
}
