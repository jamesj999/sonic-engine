package uk.co.jamesj999.sonic.level;

public enum LevelData {
    EMERALD_HILL_1(0x00),
    EMERALD_HILL_2(0x01),
    CHEMICAL_PLANT_1(0x02),
    CHEMICAL_PLANT_2(0x03),
    AQUATIC_RUIN_1(0x04),
    AQUATIC_RUIN_2(0x05),
    CASINO_NIGHT_1(0x06),
    CASINO_NIGHT_2(0x07),
    HILL_TOP_1(0x08),
    HILL_TOP_2(0x09),
    MYSTIC_CAVE_1(0x0A),
    MYSTIC_CAVE_2(0x0B),
    OIL_OCEAN_1(0x0C),
    OIL_OCEAN_2(0x0D),
    METROPOLIS_1(0x0E),
    METROPOLIS_2(0x0F),
    METROPOLIS_3(0x10),
    SKY_CHASE(0x11),
    WING_FORTRESS(0x12),
    DEATH_EGG(0x13);

    private final int levelIndex;

    LevelData(int levelIndex) {
        this.levelIndex = levelIndex;
    }

    public int getLevelIndex() {
        return levelIndex;
    }
}
