package worldsim;

/** Serializable path waypoint stored as JSON on the agent. */
public record PathPoint(double x, double y) {
    public static PathPoint from(Tile tile) {
        return new PathPoint(tile.location.getX(), tile.location.getY());
    }
}
