package com.openggf.level;

import com.openggf.level.objects.InitialObjectDispatchScope;
import com.openggf.sprites.managers.InitialPlayableInput;

/**
 * Reconstructs the one initial S3K {@code Process_Sprites} walk across the
 * engine's separately-owned SST domains.
 *
 * <p>ROM order: {@code Load_Sprites}, Player 1, Player 2, collision-list reset,
 * absolute dynamic slot 3, managed dynamic slots 4-93, then fixed slots 94-109
 * (docs/skdisasm/sonic3k.asm:7848-7856,35965-36008;
 * docs/skdisasm/sonic3k.constants.asm:303-323).
 */
final class InitialProcessSpritesCoordinator {
    enum Checkpoint {
        AFTER_PLAYABLES_BEFORE_RESET,
        AFTER_DYNAMIC_BEFORE_FIXED
    }

    private final java.util.function.Consumer<Checkpoint> checkpoint;

    InitialProcessSpritesCoordinator() {
        this(ignored -> { });
    }

    InitialProcessSpritesCoordinator(
            java.util.function.Consumer<Checkpoint> checkpoint) {
        this.checkpoint = checkpoint;
    }

    void execute(InitialProcessSpritesContext context) {
        InitialProcessSpritesStages stages = context.stages();
        InitialDynamicSstDispatcher dynamic = stages.dynamic();
        try (InitialObjectDispatchScope ignored = dynamic.begin(context.epoch())) {
            stages.fixed().onInitialScopeAcquired();
            stages.collisionList().freezePreviousReadView();
            dynamic.loadSprites();
            stages.playables().processInitialPlayableSlots(
                    context.epoch(), InitialPlayableInput.nativeNeutral());
            checkpoint.accept(Checkpoint.AFTER_PLAYABLES_BEFORE_RESET);
            stages.collisionList().resetCurrentBuild();
            dynamic.processAbsoluteDynamicSlot3();
            dynamic.processManagedDynamicSlots4Through93();
            stages.collisionList().markDynamicBuildComplete();
            checkpoint.accept(Checkpoint.AFTER_DYNAMIC_BEFORE_FIXED);
            stages.fixed().processPostDynamicFixedSlots(context.epoch());
            stages.collisionList().captureCompletedBuild();
        }
    }
}
