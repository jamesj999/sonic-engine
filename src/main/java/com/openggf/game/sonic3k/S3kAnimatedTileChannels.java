package com.openggf.game.sonic3k;

import com.openggf.game.animation.AnimatedTileCachePolicy;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.game.animation.DestinationPlan;
import com.openggf.game.animation.strategies.ComposedTransferApplyStrategy;
import com.openggf.game.animation.strategies.SplitTransferApplyStrategy;
import com.openggf.level.animation.AniPlcScriptState;

import java.util.ArrayList;
import java.util.List;

final class S3kAnimatedTileChannels {
    private S3kAnimatedTileChannels() {
    }

    static List<AnimatedTileChannel> buildMgzChannels(Sonic3kPatternAnimator owner,
                                                      List<AniPlcScriptState> scripts) {
        List<AnimatedTileChannel> channels = new ArrayList<>(scripts.size());
        for (int i = 0; i < scripts.size(); i++) {
            AniPlcScriptState script = scripts.get(i);
            channels.add(new AnimatedTileChannel(
                    "s3k.mgz.script." + i,
                    owner::shouldRunMgzScriptChannels,
                    ctx -> ctx.frameCounter(),
                    scriptDestination(script),
                    AnimatedTileCachePolicy.ALWAYS,
                    ctx -> owner.tickScript(script)
            ));
        }
        return channels;
    }

    static List<AnimatedTileChannel> buildHczChannels(Sonic3kPatternAnimator owner,
                                                      List<AniPlcScriptState> scripts,
                                                      int actIndex) {
        List<AnimatedTileChannel> channels = new ArrayList<>(scripts.size() + 1);
        for (int i = 0; i < scripts.size(); i++) {
            AniPlcScriptState script = scripts.get(i);
            channels.add(new AnimatedTileChannel(
                    "s3k.hcz.script." + i,
                    owner::shouldRunScriptChannels,
                    ctx -> ctx.frameCounter(),
                    scriptDestination(script),
                    AnimatedTileCachePolicy.ALWAYS,
                    ctx -> owner.tickScript(script)
            ));
        }

        if (actIndex == 0) {
            channels.add(new AnimatedTileChannel(
                    "s3k.hcz1.waterline",
                    owner::shouldRunHcz1CustomChannels,
                    ctx -> owner.computeHcz1WaterlineDelta(),
                    new DestinationPlan(0x2DC, 0x30B),
                    AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                    new ComposedTransferApplyStrategy(owner::updateHcz1BackgroundStripsForGraph)
            ));
        } else {
            channels.add(new AnimatedTileChannel(
                    "s3k.hcz2.strips",
                    owner::shouldRunHcz2CustomChannels,
                    ctx -> owner.computeHcz2CompositePhase(),
                    new DestinationPlan(0x2D2, 0x31D),
                    AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                    new SplitTransferApplyStrategy(owner::updateHcz2BackgroundStripsForGraph)
            ));
        }

        return channels;
    }

    static List<AnimatedTileChannel> buildSozChannels(Sonic3kPatternAnimator owner,
                                                      List<AniPlcScriptState> scripts) {
        List<AnimatedTileChannel> channels = new ArrayList<>(scripts.size() + 1);
        for (int i = 0; i < scripts.size(); i++) {
            AniPlcScriptState script = scripts.get(i);
            channels.add(new AnimatedTileChannel(
                    "s3k.soz.script." + i,
                    owner::shouldRunScriptChannels,
                    ctx -> ctx.frameCounter(),
                    scriptDestination(script),
                    AnimatedTileCachePolicy.ALWAYS,
                    ctx -> owner.tickScript(script)
            ));
        }

        channels.add(new AnimatedTileChannel(
                "s3k.soz1.scroll",
                owner::shouldRunSoz1CustomChannels,
                ctx -> owner.computeSoz1Phase(),
                new DestinationPlan(0x330, 0x33E),
                AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                new SplitTransferApplyStrategy(owner::updateSoz1BackgroundTilesForGraph)
        ));

        return channels;
    }

    static List<AnimatedTileChannel> buildCnzChannels(Sonic3kPatternAnimator owner,
                                                      List<AniPlcScriptState> scripts) {
        List<AnimatedTileChannel> channels = new ArrayList<>(scripts.size() + 1);
        for (int i = 0; i < scripts.size(); i++) {
            AniPlcScriptState script = scripts.get(i);
            channels.add(new AnimatedTileChannel(
                    "s3k.cnz.script." + i,
                    owner::shouldRunScriptChannels,
                    ctx -> ctx.frameCounter(),
                    scriptDestination(script),
                    AnimatedTileCachePolicy.ALWAYS,
                    ctx -> owner.tickScript(script)
            ));
        }

        channels.add(new AnimatedTileChannel(
                "s3k.cnz.scroll",
                owner::shouldRunCnzCustomChannels,
                ctx -> owner.computeCnzPhase(),
                new DestinationPlan(0x308, 0x327),
                AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                new SplitTransferApplyStrategy(owner::updateCnzBackgroundTilesForGraph)
        ));

        return channels;
    }

    static List<AnimatedTileChannel> buildIczChannels(Sonic3kPatternAnimator owner,
                                                      List<AniPlcScriptState> scripts,
                                                      int actIndex) {
        List<AnimatedTileChannel> channels = new ArrayList<>(scripts.size() + 2);
        for (int i = 0; i < scripts.size(); i++) {
            AniPlcScriptState script = scripts.get(i);
            channels.add(new AnimatedTileChannel(
                    "s3k.icz.script." + i,
                    owner::shouldRunScriptChannels,
                    ctx -> ctx.frameCounter(),
                    scriptDestination(script),
                    AnimatedTileCachePolicy.ALWAYS,
                    ctx -> owner.tickScript(script)
            ));
        }

        channels.add(new AnimatedTileChannel(
                "s3k.icz.scroll.x",
                owner::shouldRunIczHorizontalCustomChannels,
                ctx -> owner.computeIczHorizontalPhase(),
                new DestinationPlan(0x10E, 0x11D),
                AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                new SplitTransferApplyStrategy(owner::updateIczHorizontalTilesForGraph)
        ));

        if (actIndex == 0) {
            channels.add(new AnimatedTileChannel(
                    "s3k.icz1.scroll.y",
                    owner::shouldRunIczAct1VerticalCustomChannels,
                    ctx -> owner.computeIczAct1VerticalCompositePhase(),
                    new DestinationPlan(0x122, 0x130),
                    AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                    new SplitTransferApplyStrategy(owner::updateIczAct1VerticalTilesForGraph)
            ));
        }

        return channels;
    }

    static List<AnimatedTileChannel> buildLbzChannels(Sonic3kPatternAnimator owner,
                                                      List<AniPlcScriptState> scripts,
                                                      int actIndex,
                                                      int regularScriptCount) {
        List<AnimatedTileChannel> channels = new ArrayList<>(scripts.size() + 4);
        if (actIndex == 1) {
            channels.add(new AnimatedTileChannel(
                    "s3k.lbz2.rideTrigger",
                    owner::shouldRunLbz2RideTriggerChannel,
                    ctx -> ctx.frameCounter(),
                    DestinationPlan.single(0),
                    AnimatedTileCachePolicy.ALWAYS,
                    ctx -> owner.consumeLbz2RideTriggerForGraph()
            ));
        }
        channels.add(new AnimatedTileChannel(
                "s3k.lbz.shared",
                owner::shouldRunLbzSharedChannel,
                ctx -> owner.computeLbzSharedPhase(ctx.frameCounter()),
                new DestinationPlan(0x160, 0x16F),
                AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                ctx -> owner.updateLbzSharedTilesForGraph(ctx.frameCounter())
        ));

        if (actIndex == 0) {
            channels.add(new AnimatedTileChannel(
                    "s3k.lbz1.scroll",
                    owner::shouldRunLbz1CustomChannels,
                    ctx -> owner.computeLbz1ScrollPhase(),
                    new DestinationPlan(0x350, 0x364),
                    AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                    ctx -> owner.updateLbz1ScrollTilesForGraph()
            ));
        } else {
            channels.add(new AnimatedTileChannel(
                    "s3k.lbz2.scroll",
                    owner::shouldRunLbz2ScrollChannel,
                    ctx -> owner.computeLbz2ScrollPhase(),
                    new DestinationPlan(0x2E3, 0x2E4),
                    AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                    ctx -> owner.updateLbz2ScrollTilesForGraph()
            ));
            channels.add(new AnimatedTileChannel(
                    "s3k.lbz2.waterline",
                    owner::shouldRunLbz2WaterlineChannel,
                    ctx -> owner.computeLbz2WaterlinePhase(),
                    new DestinationPlan(0x2C3, 0x2E2),
                    AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                    ctx -> owner.updateLbz2WaterlineTilesForGraph()
            ));
        }

        for (int i = 0; i < scripts.size(); i++) {
            AniPlcScriptState script = scripts.get(i);
            String prefix = actIndex == 0 && i >= regularScriptCount
                    ? "s3k.lbz1.spec." + (i - regularScriptCount)
                    : "s3k.lbz" + (actIndex + 1) + ".script." + i;
            if (actIndex == 0 && i < regularScriptCount) {
                channels.add(new AnimatedTileChannel(
                        prefix,
                        () -> owner.shouldRunLbz1AlarmScriptChannel(script),
                        ctx -> owner.computeLbz1AlarmScriptPhase(script, ctx.frameCounter()),
                        scriptDestination(script),
                        AnimatedTileCachePolicy.ALWAYS,
                        ctx -> owner.updateLbz1AlarmScriptForGraph(script)
                ));
                continue;
            }
            channels.add(new AnimatedTileChannel(
                    prefix,
                    owner::shouldRunScriptChannels,
                    ctx -> ctx.frameCounter(),
                    scriptDestination(script),
                    AnimatedTileCachePolicy.ALWAYS,
                    ctx -> owner.tickScript(script)
            ));
        }

        return channels;
    }

    static List<AnimatedTileChannel> buildMhzChannels(Sonic3kPatternAnimator owner,
                                                      List<AniPlcScriptState> scripts) {
        List<AnimatedTileChannel> channels = new ArrayList<>(scripts.size() + 3);
        channels.add(new AnimatedTileChannel(
                "s3k.mhz.bg1",
                owner::shouldRunMhzBackgroundLayer1Channel,
                ctx -> owner.computeMhzBackgroundLayer1Phase(),
                new DestinationPlan(0x1B8, 0x1BF),
                AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                new SplitTransferApplyStrategy(owner::updateMhzBackgroundLayer1ForGraph)
        ));
        channels.add(new AnimatedTileChannel(
                "s3k.mhz.bg2",
                owner::shouldRunMhzBackgroundLayer2Channel,
                ctx -> owner.computeMhzBackgroundLayer2Phase(),
                new DestinationPlan(0x1D5, 0x1F4),
                AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                new SplitTransferApplyStrategy(owner::updateMhzBackgroundLayer2ForGraph)
        ));
        channels.add(new AnimatedTileChannel(
                "s3k.mhz.mushroomCapCounter",
                owner::shouldRunMhzMushroomCapCounterChannel,
                ctx -> ctx.frameCounter(),
                DestinationPlan.single(0),
                AnimatedTileCachePolicy.ALWAYS,
                ctx -> owner.advanceMhzMushroomCapPositionCounterForGraph()
        ));

        for (int i = 0; i < scripts.size(); i++) {
            AniPlcScriptState script = scripts.get(i);
            channels.add(new AnimatedTileChannel(
                    "s3k.mhz.script." + i,
                    owner::shouldRunScriptChannels,
                    ctx -> ctx.frameCounter(),
                    scriptDestination(script),
                    AnimatedTileCachePolicy.ALWAYS,
                    ctx -> owner.tickScript(script)
            ));
        }

        return channels;
    }

    private static DestinationPlan scriptDestination(AniPlcScriptState script) {
        int startTile = script.destinationTileIndex();
        if (script.tilesPerFrame() <= 1) {
            return DestinationPlan.single(startTile);
        }
        return new DestinationPlan(startTile, startTile + script.tilesPerFrame() - 1);
    }
}
