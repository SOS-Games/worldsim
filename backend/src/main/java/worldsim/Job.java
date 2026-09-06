package worldsim;

public enum Job {
    LUMBERJACK,
    MINER,
    FARMER,
    STONECUTTER,
    PROSPECTOR,
    HERBALIST,
    TRADER;

    /** Resource this job harvests from the map, or {@code null} for merchants. */
    public ResourceType harvests() {
        return switch (this) {
            case LUMBERJACK -> ResourceType.WOOD;
            case MINER -> ResourceType.GOLD;
            case FARMER -> ResourceType.FOOD;
            case STONECUTTER -> ResourceType.STONE;
            case PROSPECTOR -> ResourceType.IRON;
            case HERBALIST -> ResourceType.HERBS;
            case TRADER -> null;
        };
    }

    public boolean isTrader() {
        return this == TRADER;
    }

    /** SQL predicate: this job is allowed to harvest this resource column. */
    public static String sqlHarvestMatch(String jobColumn, String resourceColumn) {
        StringBuilder sql = new StringBuilder("(");
        int matched = 0;
        for (Job job : values()) {
            ResourceType type = job.harvests();
            if (type == null) {
                continue;
            }
            if (matched > 0) {
                sql.append(" OR ");
            }
            sql.append('(')
                    .append(jobColumn)
                    .append(" = '")
                    .append(job.name())
                    .append("' AND ")
                    .append(resourceColumn)
                    .append(" = '")
                    .append(type.name())
                    .append("')");
            matched++;
        }
        if (matched == 0) {
            return "(false)";
        }
        return sql.append(')').toString();
    }
}
