package com.openggf.game.animation;

import com.openggf.level.Level;

/**
 * Frame-local execution context passed to animated tile channels.
 *
 * <p>The record carries both shared frame state and, when invoked from
 * {@link AnimatedTileChannelGraph#update(ChannelContext)}, the specific channel
 * currently being evaluated.
 */
public record ChannelContext(
        AnimatedTileChannelGraph graph,
        AnimatedTileChannel channel,
        Level level,
        Object runtimeState,
        int zoneIndex,
        int actIndex,
        int frameCounter) {
}
