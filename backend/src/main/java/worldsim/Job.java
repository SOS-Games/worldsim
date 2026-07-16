package worldsim;

public enum Job {
    LUMBERJACK,
    MINER,
    TRADER;

    public ResourceType harvests() {
        return switch (this) {
            case LUMBERJACK -> ResourceType.WOOD;
            case MINER -> ResourceType.GOLD;
            case TRADER -> ResourceType.FOOD;
        };
    }
}
