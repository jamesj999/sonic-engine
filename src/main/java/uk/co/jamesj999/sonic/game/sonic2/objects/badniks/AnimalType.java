package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;

public enum AnimalType {
    RABBIT(-0x200, -0x400, MappingSet.E, false, Sonic2Constants.ART_NEM_RABBIT_ADDR, "Rabbit"),
    CHICKEN(-0x200, -0x300, MappingSet.A, true, Sonic2Constants.ART_NEM_CHICKEN_ADDR, "Chicken"),
    PENGUIN(-0x180, -0x300, MappingSet.E, false, Sonic2Constants.ART_NEM_PENGUIN_ADDR, "Penguin"),
    SEAL(-0x140, -0x180, MappingSet.D, false, Sonic2Constants.ART_NEM_SEAL_ADDR, "Seal"),
    PIG(-0x1C0, -0x300, MappingSet.C, false, Sonic2Constants.ART_NEM_PIG_ADDR, "Pig"),
    FLICKY(-0x300, -0x400, MappingSet.A, true, Sonic2Constants.ART_NEM_FLICKY_ADDR, "Flicky"),
    SQUIRREL(-0x280, -0x380, MappingSet.B, false, Sonic2Constants.ART_NEM_SQUIRREL_ADDR, "Squirrel"),
    EAGLE(-0x280, -0x300, MappingSet.A, true, Sonic2Constants.ART_NEM_EAGLE_ADDR, "Eagle"),
    MOUSE(-0x200, -0x380, MappingSet.B, false, Sonic2Constants.ART_NEM_MOUSE_ADDR, "Mouse"),
    MONKEY(-0x2C0, -0x300, MappingSet.B, false, Sonic2Constants.ART_NEM_MONKEY_ADDR, "Monkey"),
    TURTLE(-0x140, -0x200, MappingSet.B, false, Sonic2Constants.ART_NEM_TURTLE_ADDR, "Turtle"),
    BEAR(-0x200, -0x300, MappingSet.B, false, Sonic2Constants.ART_NEM_BEAR_ADDR, "Bear");

    public enum MappingSet {
        A,
        B,
        C,
        D,
        E
    }

    private final int xVel;
    private final int yVel;
    private final MappingSet mappingSet;
    private final boolean flying;
    private final int artAddr;
    private final String displayName;

    AnimalType(int xVel, int yVel, MappingSet mappingSet, boolean flying, int artAddr, String displayName) {
        this.xVel = xVel;
        this.yVel = yVel;
        this.mappingSet = mappingSet;
        this.flying = flying;
        this.artAddr = artAddr;
        this.displayName = displayName;
    }

    public int xVel() {
        return xVel;
    }

    public int yVel() {
        return yVel;
    }

    public MappingSet mappingSet() {
        return mappingSet;
    }

    public boolean flying() {
        return flying;
    }

    public int artAddr() {
        return artAddr;
    }

    public String displayName() {
        return displayName;
    }

    public static AnimalType fromIndex(int index) {
        AnimalType[] values = values();
        if (index < 0 || index >= values.length) {
            return RABBIT;
        }
        return values[index];
    }
}
