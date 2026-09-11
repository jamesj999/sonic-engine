package com.openggf.graphics.shaderlib;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Engine-owned VHS picture-search post-process for live rewind.
 * <p>
 * Owns a private {@link DisplayShaderPipeline} independent of the user's
 * display-shader selection. Prewarmed once at GL init when the VHS effect is
 * enabled in config so a later launch-profile rewind override does not pay a
 * shader-compile hitch; applied per frame only while the rewind effect envelope
 * intensity is above zero. On any activation or apply failure the
 * pass latches failed and never retries for the session — the pipeline's own
 * logging covers the details, this class adds a single summary warning.
 */
public final class RewindVhsEffectPass {

    private static final Logger LOG = Logger.getLogger(RewindVhsEffectPass.class.getName());
    static final String SHADER_RESOURCE = "/shaders/shader_vhs_rewind.glsl";

    private final DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
    private boolean activated;
    private boolean failed;
    private int frameCounter;
    private float scrollPhase;

    /** Per-frame tear-band scroll advance at base tape speed (fraction of screen height). */
    private static final float SCROLL_RATE_PER_FRAME = 0.006f;

    /** Tear-band scroll direction for a tape running backward. */
    public static final float REWIND_SCROLL_DIRECTION = 1.0f;

    /** Tear-band scroll direction for a tape running forward faster than play. */
    public static final float FAST_FORWARD_SCROLL_DIRECTION = -1.0f;

    /**
     * Advance the 0..1 band scroll phase by one frame at the given signed tape
     * speed. The sign is the transport direction: positive scrolls the bands
     * the way a rewinding tape does, negative the way a fast-forwarding one
     * does. Wraps either way, so the phase stays in 0..1.
     */
    static float advanceScrollPhase(float phase, float signedSpeed) {
        float advanced = phase + SCROLL_RATE_PER_FRAME * signedSpeed;
        return advanced - (float) Math.floor(advanced);
    }

    /** Builds the built-in preset from the classpath. No GL required. */
    public static DisplayShaderPreset builtInPreset() throws IOException {
        String fragment = loadResource(SHADER_RESOURCE);
        return new DisplayShaderPreset("vhs-rewind", ShaderPhase.PRESENTATION, List.of(
                new DisplayShaderPass(null, fragment, GlslShape.FRAGMENT_ONLY,
                        1.0, 1.0, ScaleType.VIEWPORT, ScaleType.VIEWPORT,
                        false, WrapMode.CLAMP_TO_EDGE)));
    }

    /** Compile and activate the pipeline. Call once during engine GL init. */
    public void prewarm(int sourceW, int sourceH, int viewportW, int viewportH) {
        if (activated || failed) {
            return;
        }
        try {
            pipeline.resize(sourceW, sourceH, viewportW, viewportH);
            if (!pipeline.activate(builtInPreset())) {
                markFailed("activation failed: " + pipeline.lastActivationFailure());
                return;
            }
            activated = true;
        } catch (Exception e) {
            markFailed("load failed: " + e.getMessage());
        }
    }

    /**
     * Apply the effect over the current default-framebuffer viewport contents.
     * No-op unless prewarmed, healthy, and intensity is above zero.
     *
     * @param scrollDirection transport direction for the tear-band scroll:
     *        {@code +1} for rewind, {@code -1} for fast-forward. Only the band
     *        motion is signed — the tape damage itself is the same either way,
     *        so {@code speed} stays the unsigned magnitude the shader clamps.
     */
    public void apply(float intensity, float speed, float scrollDirection, boolean tearBands,
                      int sourceW, int sourceH,
                      int vpX, int vpY, int vpW, int vpH) {
        if (!activated || failed || intensity <= 0.0f || vpW <= 0 || vpH <= 0) {
            return;
        }
        pipeline.resize(sourceW, sourceH, vpW, vpH);
        // Integrate the band scroll phase CPU-side: the shader must not derive it
        // from FrameCount * speed, or a speed change would teleport the bands.
        scrollPhase = advanceScrollPhase(
                scrollPhase, Math.max(speed, 0.0f) * scrollDirection);
        pipeline.apply(vpX, vpY, vpW, vpH, frameCounter,
                Map.of("RewindIntensity", intensity, "RewindSpeed", speed,
                        "RewindScroll", scrollPhase, "RewindTearBands", tearBands ? 1.0f : 0.0f));
        // Wrap well below the int range: the shader's sin-hash needs FrameCount
        // to stay small for float precision, long before a 32-bit wrap would occur.
        frameCounter = (frameCounter + 1) & 0xFFFF;
        if (!pipeline.isActive()) {
            // apply() disposes the pipeline internally on failure
            activated = false;
            markFailed("apply failed; see DisplayShaderPipeline log");
        }
    }

    public boolean isFailed() {
        return failed;
    }

    public void dispose() {
        pipeline.dispose();
        activated = false;
    }

    private void markFailed(String reason) {
        failed = true;
        LOG.warning("VHS rewind effect disabled for this session: " + reason);
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream in = RewindVhsEffectPass.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing shader resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
