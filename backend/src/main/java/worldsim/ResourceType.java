package worldsim;

public enum ResourceType {
    WOOD(80, TerrainType.FOREST, 4),
    FOOD(90, TerrainType.FARM, 5),
    GOLD(70, TerrainType.MINE, 12),
    STONE(75, TerrainType.QUARRY, 3),
    IRON(65, TerrainType.VEIN, 8),
    HERBS(55, TerrainType.MEADOW, 7);

    private final int cap;
    private final TerrainType patchTerrain;
    private final int basePrice;

    ResourceType(int cap, TerrainType patchTerrain, int basePrice) {
        this.cap = cap;
        this.patchTerrain = patchTerrain;
        this.basePrice = basePrice;
    }

    public int cap() {
        return cap;
    }

    public TerrainType patchTerrain() {
        return patchTerrain;
    }

    public int basePrice() {
        return basePrice;
    }

    /** True when this tile can still grow this resource. */
    public static String sqlBelowCap(String resourceColumn, String quantityColumn) {
        StringBuilder sql = new StringBuilder("(");
        ResourceType[] types = values();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append('(')
                    .append(resourceColumn)
                    .append(" = '")
                    .append(types[i].name())
                    .append("' AND ")
                    .append(quantityColumn)
                    .append(" < ")
                    .append(types[i].cap())
                    .append(')');
        }
        return sql.append(')').toString();
    }
}
