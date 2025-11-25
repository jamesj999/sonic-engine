package uk.co.jamesj999.sonic.level;

public enum LevelData {
    EMERALD_HILL_1(0x00),
    EMERALD_HILL_2(0x01),
    CHEMICAL_PLANT_1(0x0D),
    CHEMICAL_PLANT_2(0x0D),
    AQUATIC_RUIN_1(0x0F),
    AQUATIC_RUIN_2(0x0F),
    CASINO_NIGHT_1(0x0C),
    CASINO_NIGHT_2(0x0C),
    HILL_TOP_1(0x07),
    HILL_TOP_2(0x07),
    MYSTIC_CAVE_1(0x0B),
    MYSTIC_CAVE_2(0x0B),
    OIL_OCEAN_1(0x0A),
    OIL_OCEAN_2(0x0A),
    METROPOLIS_1(0x04),
    METROPOLIS_2(0x04),
    METROPOLIS_3(0x05),
    SKY_CHASE(0x10),
    WING_FORTRESS(0x06),
    DEATH_EGG(0x0E);

    private final int levelIndex;

    LevelData(int levelIndex) {
        this.levelIndex = levelIndex;
    }

    public int getLevelIndex() {
        return levelIndex;
    }
}
