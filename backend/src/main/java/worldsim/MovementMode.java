package worldsim;

/**
 * How an agent pays for each tile in {@code pgr_dijkstra}.
 * Walkers and wagons treat open water as blocked (bridges still work). Boats treat water as fast.
 */
public enum MovementMode {
    WALK,
    BOAT,
    WAGON;

    public static MovementMode of(VehicleType type) {
        if (type == VehicleType.BOAT) {
            return BOAT;
        }
        if (type == VehicleType.WAGON) {
            return WAGON;
        }
        return WALK;
    }

    /**
     * pgRouting edge SQL. Open water is omitted for walkers/wagons so they use bridges or go around.
     */
    public String edgeSql() {
        return switch (this) {
            case WALK ->
                "SELECT id, source, target, cost, reverse_cost FROM routing_edges WHERE cost < 100000";
            case BOAT ->
                "SELECT id, source, target, boat_cost AS cost, boat_reverse_cost AS reverse_cost FROM routing_edges WHERE boat_cost < 100000";
            case WAGON ->
                "SELECT id, source, target, wagon_cost AS cost, wagon_reverse_cost AS reverse_cost FROM routing_edges WHERE wagon_cost < 100000";
        };
    }
}
