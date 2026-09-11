package com.openggf.audio;

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
    CNZ_LAUNCH, // SFX 0xE2 - CNZ LauncherSpring (SndID_CNZLaunch)
    CNZ_ELEVATOR, // SFX 0xD6 - CNZ Elevator moving sound
    SLOW_SMASH, // Special stage bomb explosion
    WALL_SMASH, // Wall smash (S1 sfx_WallSmash 0xCB)
    ERROR, // Error/fail sound (used for checkpoint failure)
    SPLASH, // SFX 0xAA - Water splash (entering/exiting water)
    AIR_DING, // SFX 0xC2 - Air warning ding (underwater countdown warning)
    CASINO_BONUS, // SFX 0xC0 - Casino cage points sound (SndID_CasinoBonus)
    OIL_SLIDE, // SFX 0xF0 - OOZ oil slide (SndID_OilSlide)
    FIRE_SHIELD,
    LIGHTNING_SHIELD,
    BUBBLE_SHIELD,
    FIRE_ATTACK,
    LIGHTNING_ATTACK,
    BUBBLE_ATTACK,
    INSTA_SHIELD,
    TAILS_FLYING,
    TAILS_FLY_TIRED,
    GRAB,       // SFX $4B - Knuckles wall grab (SndID_Grab)
    GLIDE_LAND  // SFX $4C - Knuckles glide land/slide (SndID_GlideLand)
}
