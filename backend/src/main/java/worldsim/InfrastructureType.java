package worldsim;

/**
 * Built features on a tile. Terrain stays the ground cover; this is what sits on top.
 */
public enum InfrastructureType {
    NONE,
    ROAD,
    BRIDGE,
    VILLAGE,
    HARBOR;

    public String code() {
        return name().toLowerCase();
    }

    public static boolean is(String code, InfrastructureType type) {
        return type.code().equals(code);
    }
}
