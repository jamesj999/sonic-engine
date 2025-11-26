package uk.co.jamesj999.sonic.level;

public enum LevelData {
    EMERALD_HILL_1(0x00, 0x0060, 0x028F),
    EMERALD_HILL_2(0x01, 0x0060, 0x02AF),
    CHEMICAL_PLANT_1(0x02, 0x0060, 0x01EC),
    CHEMICAL_PLANT_2(0x03, 0x0060, 0x012C),
    AQUATIC_RUIN_1(0x04, 0x0060, 0x037E),
    AQUATIC_RUIN_2(0x05, 0x0060, 0x037E),
    CASINO_NIGHT_1(0x06, 0x0060, 0x02AC),
    CASINO_NIGHT_2(0x07, 0x0060, 0x058C),
    HILL_TOP_1(0x08, 0x0060, 0x03EF),
    HILL_TOP_2(0x09, 0x0060, 0x06AF),
    MYSTIC_CAVE_1(0x0A, 0x0060, 0x06AC),
    MYSTIC_CAVE_2(0x0B,0x0060, 0x05AC),
    OIL_OCEAN_1(0x0C, 0x0060, 0x06AC),
    OIL_OCEAN_2(0x0D, 0x0060, 0x056C),
    METROPOLIS_1(0x0E, 0x0060, 0x028C),
    METROPOLIS_2(0x0F, 0x0060, 0x05EC),
    METROPOLIS_3(0x10, 0x0060, 0x020C),
    SKY_CHASE(0x11, 0x0120, 0x0070),
    WING_FORTRESS(0x12, 0x0060, 0x04CC),
    DEATH_EGG(0x13,0x0060,0x012D);

    private final int levelIndex;
    private final int startXPos;
    private final int startYPos;

    LevelData(int levelIndex, int startXPos, int startYPos) {
        this.levelIndex = levelIndex;
        this.startXPos = startXPos;
        this.startYPos = startYPos;
    }

    public int getLevelIndex() {
        return levelIndex;
    }
    public int getStartXPos() { return startXPos; }
    public int getStartYPos() { return startYPos; }
}
