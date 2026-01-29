package uk.co.jamesj999.sonic.game.sonic2.constants;

public final class Sonic2AnimationIds {
    public static final int WALK = 0x00;
    public static final int RUN = 0x01;
    public static final int ROLL = 0x02;
    public static final int ROLL2 = 0x03;
    public static final int PUSH = 0x04;
    public static final int WAIT = 0x05;
    public static final int BALANCE = 0x06;   // Balancing on edge, facing toward edge
    public static final int LOOK_UP = 0x07;
    public static final int DUCK = 0x08;
    public static final int SPINDASH = 0x09;
    public static final int BALANCE2 = 0x0C;  // Balancing on edge, more precarious (closer to falling)
    public static final int SKID = 0x0D;      // Braking/halt animation
    public static final int FLOAT = 0x0E;      // Suspended/floating (used by Grabber)
    public static final int FLOAT2 = 0x0F;     // Alternate float
    public static final int SPRING = 0x10;
    public static final int HANG = 0x11;      // Hanging from horizontal bar
    public static final int HANG2 = 0x14;     // Alternate hang
    public static final int BUBBLE = 0x15;    // Breathing air bubble underwater
    public static final int DEATH = 0x18;
    public static final int HURT = 0x19;
    public static final int BALANCE3 = 0x1D;  // Balancing on edge, facing away from edge
    public static final int BALANCE4 = 0x1E;  // Balancing on edge, facing away, more precarious

    private Sonic2AnimationIds() {
    }
}
