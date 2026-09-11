package com.openggf.game;

/** ROM-owned presentation and sequencing for the Continue game mode. */
public interface ContinueScreenProvider {
    void initialize(int continues);

    /** Seed animation gates from the current ROM V-int clock. */
    default void initialize(int continues, int vintRunCount) {
        initialize(continues);
    }

    /** V-int continues while a blocking palette fade holds the object loop. */
    default void advanceFadeFrame() { }

    /** Global ROM V-int clock, including setup waits and blocking fade ticks. */
    int currentVintRunCount();

    void update(boolean startPressed, boolean start2Pressed);
    void draw();
    void reset();
    boolean isAccepted();
    boolean isFinished();

    /** S1 Cont_GotoLevel and S2 ContinueScreen clear their checkpoint byte. */
    default boolean clearsCheckpointOnContinue() { return true; }

    /** S3K loc_5C48A persists the new life/continue counts. */
    default boolean savesOnContinue() { return false; }
}
