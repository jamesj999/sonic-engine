package com.openggf.tools;

import com.openggf.GameLoop;
import com.openggf.capture.AudioFrameTap;
import com.openggf.capture.CaptureException;
import com.openggf.capture.CaptureRecorder;
import com.openggf.capture.CapturedFrame;
import com.openggf.capture.VideoFrameGrabber;
import com.openggf.game.GameServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.sprites.ghost.GhostTraceRenderer;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.trace.replay.TraceGhostHook;
import com.openggf.trace.replay.TraceReplayDriver;

import java.nio.file.Path;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glFinish;

/**
 * Ties together a booted {@link GameLoop}, a deterministic {@link TraceReplayDriver},
 * a {@link VideoFrameGrabber}, an {@link AudioFrameTap}, and a {@link CaptureRecorder}
 * into a per-frame capture loop:
 * <em>step → present → render → grab → drain → submit</em>.
 *
 * <p>Per frame, {@link #stepAndCapture()}:
 * <ol>
 *   <li>returns {@code false} when {@link TraceReplayDriver#isComplete()} (the
 *       trace has been fully replayed);</li>
 *   <li>advances the game one tick via {@link GameLoop#step()}. A tick only
 *       enqueues audio commands: {@code GameLoop.step()}/{@code stepInternal()}
 *       present no audio, so the driver owns the outer-frame audio boundary;</li>
 *   <li>presents exactly one forward final-PCM packet for this outer
 *       framebuffer frame via
 *       {@link HeadlessGameBoot#presentHeadlessOuterAudioFrame()};</li>
 *   <li>renders the LEVEL scene the same way {@code Engine.draw()} does — clear
 *       with the level's background colour, {@code drawWithSpritePriority}, flush,
 *       {@code glFinish};</li>
 *   <li>grabs the back buffer as RGBA, drains that presented packet exactly
 *       once, and submits a {@link CapturedFrame} to the recorder.</li>
 * </ol>
 *
 * <p>This class assumes a current GL context on the calling thread (the headless
 * boot owns it) and that the {@link TraceReplayDriver} has already had
 * {@code start(zone, act)} run, so the comparator/observer are wired before the
 * first step.
 */
public final class TraceCaptureSession {

    private final GameLoop loop;
    private final TraceReplayDriver driver;
    private final VideoFrameGrabber grabber;
    private final AudioFrameTap audioTap;
    private final CaptureRecorder recorder;
    private final int fps;

    private final int width;
    private final int height;

    // Reusable PCM drain buffer. Sized far above any plausible per-frame stereo
    // sample count (e.g. 48000/60 = 800 stereo frames -> 1600 shorts); the
    // CapturedFrame constructor defensively copies it, so reuse is safe.
    private final short[] pcmBuffer = new short[16384];

    // Desync ghosts: rendered via the shared TraceGhostHook so LevelRenderer
    // draws them interleaved in the sprite-priority passes, exactly as live
    // Trace Test Mode does.
    private final GhostTraceRenderer ghostRenderer = new GhostTraceRenderer();
    private final TraceGhostHook.GhostLayerRenderer ghostHook = this::renderGhostsForLayer;

    private long frameIndex;
    private boolean started;

    public TraceCaptureSession(GameLoop loop, TraceReplayDriver driver,
                               VideoFrameGrabber grabber, AudioFrameTap audioTap,
                               CaptureRecorder recorder, int fps) {
        this.loop = loop;
        this.driver = driver;
        this.grabber = grabber;
        this.audioTap = audioTap;
        this.recorder = recorder;
        this.fps = fps;
        this.width = grabber.width();
        this.height = grabber.height();
    }

    /**
     * Takes the offline audio-capture lease on the unified presentation
     * producer and opens the recorder. The lease is taken first so the very
     * first presented outer frame is already observable by the tap; it is a
     * non-consuming view, so it neither replaces the producer nor opens an
     * audio device. Must be called once before the first
     * {@link #stepAndCapture()}.
     *
     * <p>The session's {@code fps} must be the rate the presentation producer
     * is clocked at ({@code AudioManager.presentationFrameRate()}); the lease
     * is rejected otherwise, because a mismatched capture clock truncates or
     * zero-pads every presented packet.
     */
    public void start(int width, int height, int sampleRate) throws CaptureException {
        if (width != this.width || height != this.height) {
            throw new CaptureException("capture dimensions " + width + "x" + height
                    + " do not match grabber " + this.width + "x" + this.height);
        }
        GameServices.audio().beginCaptureMode(sampleRate, fps);
        try {
            recorder.start(width, height, fps, sampleRate);
        } catch (Throwable failedToOpen) {
            // The recorder never opened, so finish() will never run: release
            // the lease here or it is leaked onto the producer, which then
            // rejects every later beginCaptureMode in this process.
            GameServices.audio().endCaptureMode();
            throw failedToOpen;
        }
        TraceGhostHook.set(ghostHook);
        started = true;
    }

    /**
     * Advances one frame and captures it.
     *
     * @return {@code false} once the trace is complete (no frame captured);
     *         {@code true} after a frame was rendered, grabbed, and submitted.
     */
    public boolean stepAndCapture() throws CaptureException {
        if (!started) {
            throw new CaptureException("start() must be called before stepAndCapture()");
        }
        if (driver.isComplete()) {
            return false;
        }

        // 1. Advance the game one tick. GameLoop.step()/stepInternal() never
        //    present audio, so a tick only enqueues audio commands.
        loop.step();

        // 2. Present exactly one final-PCM packet for this outer framebuffer
        //    frame. This is the only audio cadence in the capture loop.
        HeadlessGameBoot.presentHeadlessOuterAudioFrame();

        // 3. Render the LEVEL scene the same way Engine.draw() does for the
        //    default (non-debug) LEVEL path.
        renderFrame();

        // 4. Grab the rendered back buffer as RGBA (bottom-up; ffmpeg vflip
        //    corrects orientation downstream).
        byte[] rgba = grabber.grab();

        // 5. Drain that presented packet exactly once, after the grab.
        int sampleCount = audioTap.drain(pcmBuffer);

        // 6. Submit the captured frame.
        recorder.submit(new CapturedFrame(rgba, width, height,
                pcmBuffer, sampleCount, frameIndex++));
        return true;
    }

    /**
     * Finalizes the recording and releases the offline capture lease. The
     * recorder is stopped first, then the lease is released unconditionally —
     * a failing stop must not leak a lease onto the producer.
     */
    public Path finish() throws CaptureException {
        try {
            try {
                return recorder.stop();
            } finally {
                TraceGhostHook.clear(ghostHook);
            }
        } finally {
            GameServices.audio().endCaptureMode();
        }
    }

    /**
     * Renders the desync ghost(s) for one priority bucket, sourced from the
     * driver's comparator (recorded trace position) against the live engine
     * sprites. Invoked by {@code LevelRenderer} via {@link TraceGhostHook} during
     * {@link #renderFrame()}.
     */
    private void renderGhostsForLayer(int bucket, boolean highPriority) {
        LiveTraceComparator comparator = driver.comparator();
        if (comparator == null) {
            return;
        }
        SpriteManager sprites = GameServices.spritesOrNull();
        List<com.openggf.sprites.playable.AbstractPlayableSprite> sidekicks =
                sprites != null ? sprites.getRegisteredSidekicks() : List.of();
        ghostRenderer.renderForLayer(
                comparator.metadata(),
                comparator.currentVisualFrame(),
                GameServices.camera().getFocusedSprite(),
                sidekicks,
                bucket,
                highPriority);
    }

    /**
     * Renders the current LEVEL scene to the back buffer. Mirrors the default
     * LEVEL branch of {@code Engine.draw()} / the headless render in
     * {@code VisualReferenceGenerator}: clear with the level background colour,
     * draw level + sprites by priority, flush the command queue, and block until
     * GL is finished so the subsequent {@code glReadPixels} sees the completed
     * frame.
     */
    private void renderFrame() {
        LevelManager levelManager = GameServices.level();
        GraphicsManager graphicsManager = GameServices.graphics();

        levelManager.setClearColor();
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        levelManager.drawWithSpritePriority(GameServices.sprites());

        graphicsManager.flush();
        glFinish();
    }
}
