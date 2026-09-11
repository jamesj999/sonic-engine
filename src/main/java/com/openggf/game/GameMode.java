package com.openggf.game;

/**
 * Represents the current game mode/scene type.
 * Used to switch between normal level gameplay and special stages.
 */
public enum GameMode {
    /** Normal level gameplay (Emerald Hill, Chemical Plant, etc.) */
    LEVEL,

    /** Title card display before level starts */
    TITLE_CARD,

    /** Sonic 2 Special Stage (halfpipe ring collection) */
    SPECIAL_STAGE,

    /** Special Stage Results Screen (shown after completing/failing special stage) */
    SPECIAL_STAGE_RESULTS,

    /** Title Screen (shown on game startup before gameplay) */
    TITLE_SCREEN,

    /** ROM Continue countdown and character departure sequence. */
    CONTINUE_SCREEN,

    /** Data Select Screen (S3K save file selection) */
    DATA_SELECT,

    /** Level Select Screen (debug menu for selecting zone/act) */
    LEVEL_SELECT,

    /** Editor mode for editing the current world/session */
    EDITOR,

    /** Credits text display on black screen (ending sequence) */
    CREDITS_TEXT,

    /** Demo playback during ending credits */
    CREDITS_DEMO,

    /** Master title screen for game selection (before any ROM is loaded) */
    MASTER_TITLE_SCREEN,

    /** Legal disclaimer screen shown on engine startup before MASTER_TITLE_SCREEN. */
    LEGAL_DISCLAIMER,

    /** Post-credits "TRY AGAIN" or "END" screen (Sonic 1) */
    TRY_AGAIN_END,

    /** Ending cutscene sequence (managed by EndingProvider) */
    ENDING_CUTSCENE,

    /** S3K bonus stage (Gumball, Pachinko, Slots) — uses level pipeline with coordinator lifecycle */
    BONUS_STAGE
}
