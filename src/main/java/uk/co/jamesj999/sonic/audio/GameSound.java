package uk.co.jamesj999.sonic.audio;

public enum GameSound {
    JUMP,
    RING,
    RING_LEFT,
    RING_RIGHT,
    RING_SPILL,
    SPINDASH_CHARGE,
    SPINDASH_RELEASE,
    ROLLING,
    SKID,
    HURT,
    HURT_SPIKE,
    DROWN,
    BADNIK_HIT,
    CHECKPOINT,
    SPRING,
    BUMPER, // SFX 0xB4 - Round/Hex bumper in CNZ (SndID_Bumper)
    BONUS_BUMPER, // SFX 0xD8 - Drop target in CNZ (SndID_BonusBumper)
    LARGE_BUMPER, // SFX 0xD9 - CNZ map bumpers (SndID_LargeBumper)
    FLIPPER, // SFX 0xE3 - CNZ Flipper (SndID_Flipper)
    SLOW_SMASH, // Special stage bomb explosion
    ERROR, // Error/fail sound (used for checkpoint failure)
    SPLASH, // SFX 0xAA - Water splash (entering/exiting water)
    AIR_DING // SFX 0xC2 - Air warning ding (underwater countdown warning)
}
