package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.audio.GameAudioProfile;

import java.util.logging.Logger;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.game.sonic2.objects.InvincibilityStarsObjectInstance;
import uk.co.jamesj999.sonic.game.sonic2.objects.ShieldObjectInstance;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.SensorConfiguration;
import uk.co.jamesj999.sonic.sprites.managers.PlayableSpriteMovementManager;
import uk.co.jamesj999.sonic.sprites.managers.PlayableSpriteAnimationManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteMovementManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.render.PlayerSpriteRenderer;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationProfile;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;
import uk.co.jamesj999.sonic.sprites.managers.SpindashDustManager;
import uk.co.jamesj999.sonic.timer.TimerManager;
import uk.co.jamesj999.sonic.timer.timers.SpeedShoesTimer;

/**
 * Movement speeds are in subpixels (256 subpixels per pixel...).
 * 
 * @author james
 * 
 */
public abstract class AbstractPlayableSprite extends AbstractSprite {
        private static final Logger LOGGER = Logger.getLogger(AbstractPlayableSprite.class.getName());

        protected final SpriteMovementManager movementManager;
        protected final PlayableSpriteAnimationManager animationManager;

        protected GroundMode runningMode = GroundMode.GROUND;

        /**
         * gSpeed is the speed this sprite is moving across the 'ground'.
         * Calculations will be performed against this and 'angle' to calculate new
         * x/y values for each step.
         */
        protected short gSpeed = 0;

        /**
         * Current angle of the terrain this sprite is on.
         */
        protected byte angle;

        /**
         * Chunk entry solidity bit indices (REV01 defaults).
         */
        protected byte topSolidBit = 0x0C;
        protected byte lrbSolidBit = 0x0D;

        /**
         * Speed (in subpixels) at which this sprite walks
         */
        protected short jump = 0;

        protected short xSpeed = 0;
        protected short ySpeed = 0;

        private short[] xHistory = new short[32];
        private short[] yHistory = new short[32];

        private byte historyPos = 0;

        /**
         * Whether or not this sprite is rolling
         */
        protected boolean rolling = false;

        /**
         * Whether the current jump originated from a rolling state.
         * In Sonic 1, 2, 3 & K, air control is locked when jumping while rolling.
         * Reset to false when landing.
         */
        protected boolean rollingJump = false;

        /**
         * Whether or not this sprite is in the air
         */
        protected boolean air = false;

        /**
         * Whether or not this sprite is pushing a solid object.
         */
        protected boolean pushing = false;

        /**
         * Frames remaining for post-hit invulnerability.
         */
        protected int invulnerableFrames = 0;

        /**
         * Frames remaining for invincibility power-up.
         */
        protected int invincibleFrames = 0;

        /**
         * Whether or not this sprite is in the spring animation state.
         */
        protected boolean springing = false;

        /**
         * Frames remaining for springing state.
         */
        protected int springingFrames = 0;

        /**
         * Whether or not this sprite is dead.
         */
        protected boolean dead = false;

        /**
         * Whether or not this sprite is in the hurt/knockback state.
         * Mirrors ROM routine=4 check. Invulnerability is set when landing from hurt.
         */
        protected boolean hurt = false;

        /**
         * Countdown frames before level reload after death.
         * Set to 60 when player falls off screen, decrements each frame.
         * When it reaches 0, triggers level reload.
         */
        protected int deathCountdown = 0;

        public enum DamageCause {
                NORMAL,
                SPIKE,
                DROWN,
                TIME_OVER,
                PIT
        }

        /**
         * Whether or not this sprite is preparing for a spindash.
         */
        protected boolean spindash = false;
        /**
         * Whether or not this sprite is crouching.
         */
        protected boolean crouching = false;

        protected float spindashConstant = 0f;

        private PlayerSpriteRenderer spriteRenderer;
        private int mappingFrame = 0;
        private int animationFrameCount = 0;
        private SpriteAnimationProfile animationProfile;
        private SpriteAnimationSet animationSet;
        private int animationId = 0;
        private int animationFrameIndex = 0;
        private int animationTick = 0;
        private boolean renderHFlip = false;
        private boolean renderVFlip = false;
        private boolean highPriority = false;
        private int priorityBucket = RenderPriority.PLAYER_DEFAULT;
        private SpindashDustManager spindashDustManager;

        protected boolean shield = false;
        private ShieldObjectInstance shieldObject;
        private InvincibilityStarsObjectInstance invincibilityObject;
        protected boolean speedShoes = false;

        /**
         * When true, forces right input regardless of actual keyboard input.
         * Used for end-of-act walk-off sequences (Control_Locked + button_right_mask in
         * ROM).
         */
        protected boolean forceInputRight = false;
        /**
         * When true, user inputs are ignored (Control_Locked in ROM).
         */
        protected boolean controlLocked = false;
        /**
         * Tracks whether the jump button is currently pressed this frame.
         * Set by movement manager, used by objects (like flippers) to detect jump input.
         */
        protected boolean jumpInputPressed = false;
        private int spiralActiveFrame = Integer.MIN_VALUE;
        private byte flipAngle = 0;
        private byte flipSpeed = 0;
        private byte flipsRemaining = 0;
        private boolean flipTurned = false;

        /**
         * Whether this sprite is currently underwater.
         * Affects physics constants and triggers entry/exit speed changes.
         */
        protected boolean inWater = false;
        /**
         * Previous frame's water state, used for detecting transitions.
         */
        protected boolean wasInWater = false;

        /**
         * Clears all active power-ups (shield, invincibility, speed shoes).
         * Called when entering special stage to remove power-up effects.
         */
        public void clearPowerUps() {
                // Clear shield
                this.shield = false;
                if (this.shieldObject != null) {
                        this.shieldObject.destroy();
                        this.shieldObject = null;
                }
                // Clear invincibility
                if (this.invincibilityObject != null) {
                        this.invincibilityObject.destroy();
                        this.invincibilityObject = null;
                }
                this.invincibleFrames = 0;
                // Clear speed shoes
                if (this.speedShoes) {
                        this.speedShoes = false;
                        TimerManager.getInstance().removeTimerForCode("SpeedShoes-" + getCode());
                        defineSpeeds(); // Reset speeds to default
                }
        }

        public void resetState() {
                this.shield = false;
                if (this.shieldObject != null) {
                        this.shieldObject.destroy();
                        this.shieldObject = null;
                }
                if (this.invincibilityObject != null) {
                        this.invincibilityObject.destroy();
                        this.invincibilityObject = null;
                }
                this.speedShoes = false;
                // Cancel any active speed shoes timer
                TimerManager.getInstance().removeTimerForCode("SpeedShoes-" + getCode());
                this.invincibleFrames = 0;
                this.invulnerableFrames = 0;
                this.invincibleFrames = 0;
                this.invulnerableFrames = 0;
                // Ring count is managed by LevelGamestate and reset by LevelManager
                this.dead = false;
                this.hurt = false;
                this.deathCountdown = 0;
                this.air = false;
                this.springing = false;
                this.springingFrames = 0;
                this.rolling = false;
                this.rollingJump = false;
                this.spindash = false;
                this.pushing = false;
                this.crouching = false;
                this.highPriority = false;
                this.priorityBucket = RenderPriority.PLAYER_DEFAULT;
                this.forceInputRight = false;
                this.controlLocked = false;
                this.spiralActiveFrame = Integer.MIN_VALUE;
                this.flipAngle = 0;
                this.flipSpeed = 0;
                this.flipsRemaining = 0;
                this.flipTurned = false;
                this.inWater = false;
                this.wasInWater = false;
                defineSpeeds(); // Reset speeds to default
        }

        public void giveShield() {
                LOGGER.fine("DEBUG: giveShield() called. Current shield state: " + shield);
                if (hasShield()) {
                        LOGGER.fine("DEBUG: Player already has shield. Returning.");
                        return;
                }
                this.shield = true;
                LOGGER.fine("DEBUG: Shield flag set to true.");
                try {
                        this.shieldObject = new ShieldObjectInstance(this);
                        LOGGER.fine("DEBUG: ShieldObjectInstance created successfully: " + shieldObject);
                        LevelManager.getInstance().getObjectManager().addDynamicObject(shieldObject);
                        LOGGER.fine("DEBUG: ShieldObjectInstance added to ObjectManager.");
                        // If picked up while invincible, hide shield until invincibility ends
                        if (invincibleFrames > 0) {
                                shieldObject.setVisible(false);
                        }
                } catch (Exception e) {
                        LOGGER.fine("DEBUG: Failed to create/add ShieldObjectInstance: " + e.getMessage());
                        e.printStackTrace();
                        throw e;
                }
        }

        public void giveSpeedShoes() {
                this.speedShoes = true;
                // Register speed shoes timer using the existing timer framework
                // Duration is 1200 frames (20 seconds @ 60fps) per SPG Sonic 2
                TimerManager.getInstance().registerTimer(
                                new SpeedShoesTimer("SpeedShoes-" + getCode(), this));
        }

        /**
         * Called by SpeedShoesTimer when the effect expires.
         * Deactivates speed shoes and resets physics values.
         */
        public void deactivateSpeedShoes() {
                this.speedShoes = false;
        }

        public void giveInvincibility() {
                setInvincibleFrames(1200); // 20 seconds @ 60fps
                if (shieldObject != null) {
                        shieldObject.setVisible(false);
                }
                if (invincibilityObject == null) {
                        invincibilityObject = new InvincibilityStarsObjectInstance(this);
                        LevelManager.getInstance().getObjectManager().addDynamicObject(invincibilityObject);
                }
        }

        public boolean hasShield() {
                return shield;
        }

        public boolean hasSpeedShoes() {
                return speedShoes;
        }

        public int getRingCount() {
                var levelState = LevelManager.getInstance().getLevelGamestate();
                return levelState != null ? levelState.getRings() : 0;
        }

        public void setRingCount(int ringCount) {
                var levelState = LevelManager.getInstance().getLevelGamestate();
                if (levelState != null) {
                        levelState.setRings(ringCount);
                }
        }

        public void addRings(int delta) {
                var levelState = LevelManager.getInstance().getLevelGamestate();
                if (levelState != null) {
                        levelState.addRings(delta);
                }
        }

        public PlayerSpriteRenderer getSpriteRenderer() {
                return spriteRenderer;
        }

        public void setSpriteRenderer(PlayerSpriteRenderer spriteRenderer) {
                this.spriteRenderer = spriteRenderer;
        }

        public int getMappingFrame() {
                return mappingFrame;
        }

        public void setMappingFrame(int mappingFrame) {
                this.mappingFrame = Math.max(0, mappingFrame);
        }

        public int getAnimationFrameCount() {
                return animationFrameCount;
        }

        public void setAnimationFrameCount(int animationFrameCount) {
                this.animationFrameCount = Math.max(0, animationFrameCount);
        }

        public SpriteAnimationProfile getAnimationProfile() {
                return animationProfile;
        }

        public void setAnimationProfile(SpriteAnimationProfile animationProfile) {
                this.animationProfile = animationProfile;
        }

        public SpriteAnimationSet getAnimationSet() {
                return animationSet;
        }

        public void setAnimationSet(SpriteAnimationSet animationSet) {
                this.animationSet = animationSet;
        }

        public int getAnimationId() {
                return animationId;
        }

        public void setAnimationId(int animationId) {
                this.animationId = Math.max(0, animationId);
        }

        public int getAnimationFrameIndex() {
                return animationFrameIndex;
        }

        public void setAnimationFrameIndex(int animationFrameIndex) {
                this.animationFrameIndex = Math.max(0, animationFrameIndex);
        }

        public int getAnimationTick() {
                return animationTick;
        }

        public void setAnimationTick(int animationTick) {
                this.animationTick = Math.max(0, animationTick);
        }

        public SpindashDustManager getSpindashDustManager() {
                return spindashDustManager;
        }

        public void setSpindashDustManager(SpindashDustManager spindashDustManager) {
                this.spindashDustManager = spindashDustManager;
        }

        public boolean getRenderHFlip() {
                return renderHFlip;
        }

        public boolean getRenderVFlip() {
                return renderVFlip;
        }

        public void setRenderFlips(boolean hFlip, boolean vFlip) {
                this.renderHFlip = hFlip;
                this.renderVFlip = vFlip;
        }

        public boolean isHighPriority() {
                return highPriority;
        }

        public void setHighPriority(boolean highPriority) {
                this.highPriority = highPriority;
        }

        public int getPriorityBucket() {
                return priorityBucket;
        }

        public void setPriorityBucket(int bucket) {
                this.priorityBucket = RenderPriority.clamp(bucket);
        }

        public boolean getAir() {
                return air;
        }

        public void setAir(boolean air) {
                // If landing from hurt state, clear hurt flag
                // (invulnerableFrames already set in applyHurt() per ROM behavior)
                if (!air && this.air && hurt) {
                        hurt = false;
                }
                // Reset rolling jump flag when landing
                if (!air && this.air) {
                        rollingJump = false;
                }
                // TODO Update ground sensors here
                this.air = air;
                if (air) {
                        setGroundMode(GroundMode.GROUND);
                        setAngle((byte) 0);
                } else {
                        // Reset badnik chain when landing
                        resetBadnikChain();
                }
        }

        private int badnikChainCounter = 0;

        public void resetBadnikChain() {
                badnikChainCounter = 0;
        }

        public int incrementBadnikChain() {
                badnikChainCounter++;
                // 1st: 100, 2nd: 200, 3rd: 500, 4th+: 1000
                return switch (badnikChainCounter) {
                        case 1 -> 100;
                        case 2 -> 200;
                        case 3 -> 500;
                        default -> 1000;
                };
        }

        public byte getTopSolidBit() {
                return topSolidBit;
        }

        public void setTopSolidBit(byte topSolidBit) {
                this.topSolidBit = topSolidBit;
        }

        public byte getLrbSolidBit() {
                return lrbSolidBit;
        }

        public void setLrbSolidBit(byte lrbSolidBit) {
                this.lrbSolidBit = lrbSolidBit;
        }

        public short getJump() {
                // Water: reduced jump force (0x300 vs normal ~0x680)
                if (inWater) {
                        return 0x300;
                }
                return jump;
        }

        public boolean getSpindash() {
                return spindash;
        }

        public void setSpindash(boolean spindash) {
                this.spindash = spindash;
        }

        public boolean getCrouching() {
                return crouching;
        }

        public void setCrouching(boolean crouching) {
                this.crouching = crouching;
        }

        public boolean getPushing() {
                return pushing;
        }

        public void setPushing(boolean pushing) {
                this.pushing = pushing;
        }

        public boolean getInvulnerable() {
                // Debug mode makes player completely invulnerable
                return debugMode || invulnerableFrames > 0 || invincibleFrames > 0 || hurt;
        }

        public int getInvulnerableFrames() {
                return invulnerableFrames;
        }

        public void setInvulnerableFrames(int frames) {
                invulnerableFrames = Math.max(0, frames);
        }

        public int getInvincibleFrames() {
                return invincibleFrames;
        }

        public void setInvincibleFrames(int frames) {
                invincibleFrames = Math.max(0, frames);
        }

        public boolean getSpringing() {
                return springing;
        }

        public boolean getDead() {
                return dead;
        }

        public void setDead(boolean dead) {
                this.dead = dead;
        }

        public boolean isHurt() {
                return hurt;
        }

        public void setHurt(boolean hurt) {
                this.hurt = hurt;
        }

        public int getDeathCountdown() {
                return deathCountdown;
        }

        public void setDeathCountdown(int frames) {
                this.deathCountdown = Math.max(0, frames);
        }

        /**
         * Starts the death sequence countdown (60 frames).
         * Called when player falls below the level boundaries.
         */
        public void startDeathCountdown() {
                if (deathCountdown == 0 && dead) {
                        deathCountdown = 60;
                }
        }

        /**
         * Decrements death countdown and returns true if level should reload.
         */
        public boolean tickDeathCountdown() {
                if (deathCountdown > 0) {
                        deathCountdown--;
                        if (deathCountdown == 0) {
                                return true; // Time to reload level
                        }
                }
                return false;
        }

        public void setSpringing(int frames) {
                if (frames <= 0) {
                        springing = false;
                        springingFrames = 0;
                        return;
                }
                springing = true;
                springingFrames = frames;
        }

        public void tickStatus() {
                if (invulnerableFrames > 0) {
                        invulnerableFrames--;
                }
                if (invincibleFrames > 0) {
                        invincibleFrames--;
                        if (invincibleFrames == 0) {
                                if (invincibilityObject != null) {
                                        invincibilityObject.destroy();
                                        invincibilityObject = null;
                                }
                                if (shieldObject != null) {
                                        shieldObject.setVisible(true);
                                }
                                AudioManager audioManager = AudioManager.getInstance();
                                GameAudioProfile audioProfile = audioManager.getAudioProfile();
                                if (audioProfile != null) {
                                        audioManager.endMusicOverride(audioProfile.getInvincibilityMusicId());
                                }
                        }
                }
                if (springingFrames > 0) {
                        springingFrames--;
                        if (springingFrames == 0) {
                                springing = false;
                        }
                }
                // Speed shoes countdown is now handled by SpeedShoesTimer
        }

        public boolean applyHurt(int sourceX) {
                return applyHurt(sourceX, false);
        }

        public boolean applyHurt(int sourceX, boolean spikeHit) {
                DamageCause cause = spikeHit ? DamageCause.SPIKE : DamageCause.NORMAL;
                return applyHurt(sourceX, cause);
        }

        public boolean applyHurt(int sourceX, DamageCause cause) {
                if (getInvulnerable()) {
                        return false;
                }

                if (shield) {
                        LOGGER.fine("DEBUG: applyHurt called. removing shield.");
                        shield = false;
                        if (shieldObject != null) {
                                shieldObject.destroy();
                                shieldObject = null;
                        }
                        // Shield loss sound overrides generic hurt sound if desired, but often it's
                        // just HURT.
                        // However, we MUST prevent death logic if we had no rings.
                        // Handled in applyHurtOrDeath.
                } else {
                        LOGGER.fine("DEBUG: applyHurt called. No shield.");
                }

                hurt = true;
                setInvulnerableFrames(0x78); // Set invulnerability immediately (ROM: s2.asm line 84954)
                setSpringing(0);
                setSpindash(false);
                setRolling(false);
                setCrouching(false);
                setAir(true);
                setGSpeed((short) 0);
                int dir = (getCentreX() >= sourceX) ? 1 : -1;
                setXSpeed((short) (0x200 * dir));
                setYSpeed((short) -0x400);
                AudioManager.getInstance().playSfx(resolveDamageSound(cause));
                return true;
        }

        public boolean applyHurtOrDeath(int sourceX, boolean spikeHit, boolean hadRings) {
                DamageCause cause = spikeHit ? DamageCause.SPIKE : DamageCause.NORMAL;
                return applyHurtOrDeath(sourceX, cause, hadRings);
        }

        public boolean applyHurtOrDeath(int sourceX, DamageCause cause, boolean hadRings) {
                if (getInvulnerable()) {
                        return false;
                }
                if (!hadRings && !shield) {
                        return applyDeath(cause);
                }
                return applyHurt(sourceX, cause);
        }

        public boolean applyDrownDeath() {
                return applyDeath(DamageCause.DROWN);
        }

        public boolean applyPitDeath() {
                return applyDeath(DamageCause.PIT);
        }

        private boolean applyDeath(DamageCause cause) {
                if (dead) {
                        return false;
                }
                dead = true;
                // Lock camera when dying - prevent following the falling corpse
                uk.co.jamesj999.sonic.camera.Camera.getInstance().setFrozen(true);
                setInvulnerableFrames(0);
                setInvincibleFrames(0);
                setSpringing(0);
                setSpindash(false);
                setRolling(false);
                setCrouching(false);
                setPushing(false);
                setAir(true);
                setGSpeed((short) 0);
                setXSpeed((short) 0);
                setYSpeed((short) -0x700);
                setHighPriority(true);
                GameSound sound = resolveDamageSound(cause);
                if (sound != null) {
                        AudioManager.getInstance().playSfx(sound);
                }
                return true;
        }

        private GameSound resolveDamageSound(DamageCause cause) {
                return switch (cause) {
                        case SPIKE -> GameSound.HURT_SPIKE;
                        case DROWN, TIME_OVER -> GameSound.DROWN; // Time over usually uses Drown or specific logic,
                                                                  // checking s2 asm... usually it's just game over
                                                                  // music. but for damage sound?
                        case PIT -> GameSound.HURT;
                        default -> GameSound.HURT;
                };
        }

        public float getSpindashConstant() {
                return spindashConstant;
        }

        public void setSpindashConstant(float spindashConstant) {
                this.spindashConstant = spindashConstant;
        }

        public boolean isForceInputRight() {
                return forceInputRight;
        }

        public void setForceInputRight(boolean forceInputRight) {
                this.forceInputRight = forceInputRight;
        }

        public boolean isControlLocked() {
                return controlLocked;
        }

        public void setControlLocked(boolean controlLocked) {
                this.controlLocked = controlLocked;
        }

        /**
         * Returns whether the jump button is currently pressed.
         * Used by objects (like CNZ flippers) to detect jump input for triggering.
         */
        public boolean isJumpPressed() {
                return jumpInputPressed;
        }

        /**
         * Sets the jump input state for this frame.
         * Called by movement manager each frame with the current jump button state.
         */
        public void setJumpInputPressed(boolean pressed) {
                this.jumpInputPressed = pressed;
        }

        public void markSpiralActive(int frameCounter) {
                spiralActiveFrame = frameCounter;
        }

        public boolean wasSpiralActive(int frameCounter) {
                return spiralActiveFrame == frameCounter || spiralActiveFrame == frameCounter - 1;
        }

        public boolean isSpiralActiveThisFrame(int frameCounter) {
                return spiralActiveFrame == frameCounter;
        }

        public void clearSpiralActive() {
                spiralActiveFrame = Integer.MIN_VALUE;
        }

        public int getFlipAngle() {
                return flipAngle & 0xFF;
        }

        public void setFlipAngle(int value) {
                this.flipAngle = (byte) (value & 0xFF);
        }

        public int getFlipSpeed() {
                return flipSpeed & 0xFF;
        }

        public void setFlipSpeed(int value) {
                this.flipSpeed = (byte) (value & 0xFF);
        }

        public int getFlipsRemaining() {
                return flipsRemaining & 0xFF;
        }

        public void setFlipsRemaining(int value) {
                this.flipsRemaining = (byte) (value & 0xFF);
        }

        public boolean isFlipTurned() {
                return flipTurned;
        }

        public void setFlipTurned(boolean flipTurned) {
                this.flipTurned = flipTurned;
        }

        public short getXSpeed() {
                return xSpeed;
        }

        public void setXSpeed(short xSpeed) {
                this.xSpeed = xSpeed;
        }

        public short getYSpeed() {
                return ySpeed;
        }

        public void setYSpeed(short ySpeed) {
                this.ySpeed = ySpeed;
        }

        /**
         * The amount this sprite's speed is effected by when running down/up a
         * slope.
         */
        protected short slopeRunning;
        /**
         * The amount this sprite's speed is effected by when rolling up a slope.
         */
        protected short slopeRollingUp;
        /**
         * The amount this sprite's speed is effected by when rolling down a slope.
         */
        protected short slopeRollingDown;
        /**
         * The speed at which this sprite accelerates when running.
         */

        protected short runAccel;
        /**
         * The speed at which this sprite decelerates when the opposite direction is
         * pressed.
         */
        protected short runDecel;
        /**
         * The speed at which this sprite slows down while running with no
         * directional keys pressed.
         */
        protected short friction;
        /**
         * Maximum rolling speed of this Sprite per step.
         */
        protected short maxRoll;
        /**
         * Maximum running speed of this Sprite per step.
         */
        protected short max;

        /**
         * The speed at which this sprite slows down while rolling with no
         * directional keys pressed.
         */
        protected short rollDecel;

        /**
         * Minimum speed required to start rolling.
         */
        protected short minStartRollSpeed;

        /**
         * Speed at which to stop rolling
         */
        protected short minRollSpeed;

        /**
         * Height when rolling
         */
        protected short rollHeight;

        /**
         * Height when running
         */
        protected short runHeight;

        /**
         * Collision radii (standing/rolling) used for sensor placement.
         */
        protected short standXRadius = 9;
        protected short standYRadius = 19;
        protected short rollXRadius = 7;
        protected short rollYRadius = 14;

        protected short xRadius = standXRadius;
        protected short yRadius = standYRadius;

        /**
         * Visual render offsets (do not affect collision).
         */
        protected short renderXOffset = 0;
        protected short renderYOffset = 0;

        /**
         * When true, debug movement mode is active.
         * Player can fly freely with direction keys, ignores collision/damage.
         */
        protected boolean debugMode = false;

        protected AbstractPlayableSprite(String code, short x, short y) {
                super(code, x, y);
                // Must define speeds before creating Manager (it will read speeds upon
                // instantiation).
                defineSpeeds();

                applyStandingRadii(false);

                // Set our entire history for x and y to be the starting position so if
                // the player spindashes immediately the camera effect won't be b0rked.
                for (short i = 0; i < 32; i++) {
                        xHistory[i] = x;
                        yHistory[i] = y;
                }
                // Always use PlayableSpriteMovementManager - it checks debugMode internally
                movementManager = new PlayableSpriteMovementManager(this);
                animationManager = new PlayableSpriteAnimationManager(this);
        }

        /**
         * Returns whether debug movement mode is active.
         */
        public boolean isDebugMode() {
                return debugMode;
        }

        /**
         * Toggles debug movement mode on/off.
         */
        public void toggleDebugMode() {
                debugMode = !debugMode;
        }

        /**
         * Sets debug movement mode.
         */
        public void setDebugMode(boolean debugMode) {
                this.debugMode = debugMode;
        }

        public short getGSpeed() {
                return gSpeed;
        }

        public void setGSpeed(short gSpeed) {
                this.gSpeed = gSpeed;
        }

        public short getRunAccel() {
                // Water: halved, Speed shoes: doubled
                short value = runAccel;
                if (inWater) {
                        value = (short) (value / 2);
                }
                if (hasSpeedShoes()) {
                        value = (short) (value * 2);
                }
                return value;
        }

        public short getRunDecel() {
                // Water: halved (speed shoes don't affect decel)
                return inWater ? (short) (runDecel / 2) : runDecel;
        }

        public short getSlopeRunning() {
                return slopeRunning;
        }

        public short getSlopeRollingUp() {
                return slopeRollingUp;
        }

        public short getSlopeRollingDown() {
                return slopeRollingDown;
        }

        public short getFriction() {
                // Water: halved, Speed shoes: doubled
                short value = friction;
                if (inWater) {
                        value = (short) (value / 2);
                }
                if (hasSpeedShoes()) {
                        value = (short) (value * 2);
                }
                return value;
        }

        public short getMax() {
                // Water: halved, Speed shoes: doubled
                short value = max;
                if (inWater) {
                        value = (short) (value / 2);
                }
                if (hasSpeedShoes()) {
                        value = (short) (value * 2);
                }
                return value;
        }

        /**
         * Override gravity to reduce it underwater.
         * Normal: 0x38 (56 subpixels)
         * Underwater: 0x10 (16 subpixels)
         */
        @Override
        public float getGravity() {
                if (inWater) {
                        return 0x10; // Reduced underwater gravity
                }
                return gravity; // Normal gravity (0x38)
        }

        public byte getAngle() {
                return angle;
        }

        public void setAngle(byte angle) {
                this.angle = angle;
        }

        public short[] getXHistory() {
                return xHistory;
        }

        public short[] getYHistory() {
                return yHistory;
        }

        public boolean getRolling() {
                return rolling;
        }

        public void setRolling(boolean rolling) {
                if (this.rolling == rolling) {
                        return;
                }

                if (GroundMode.CEILING.equals(runningMode) || GroundMode.GROUND.equals(runningMode)) {
                        int oldHeight = getHeight();
                        int newHeight = rolling ? rollHeight : runHeight;
                        if (oldHeight != newHeight) {
                                int delta = (oldHeight - newHeight) / 2;
                                yPixel = (short) (yPixel + delta);
                                setHeight(newHeight);
                        }
                } else {
                        int oldWidth = getWidth();
                        int newWidth = rolling ? rollHeight : runHeight;
                        if (oldWidth != newWidth) {
                                int delta = (oldWidth - newWidth) / 2;
                                xPixel = (short) (xPixel + delta);
                                setWidth(newWidth);
                        }
                }

                if (rolling) {
                        applyRollingRadii(false);
                } else {
                        applyStandingRadii(false);
                }

                byte delta = 5;
                if (!rolling) {
                        delta = (byte) -delta;
                }
                moveForGroundModeAndDirection(delta, Direction.DOWN);

                this.rolling = rolling;
        }

        public boolean getRollingJump() {
                return rollingJump;
        }

        public void setRollingJump(boolean rollingJump) {
                this.rollingJump = rollingJump;
        }

        @Override
        public void setHeight(int height) {
                super.setHeight(height);
        }

        public short getRollDecel() {
                return rollDecel;
        }

        public short getMaxRoll() {
                return maxRoll;
        }

        public short getMinStartRollSpeed() {
                return minStartRollSpeed;
        }

        public short getMinRollSpeed() {
                return minRollSpeed;
        }

        public short getXRadius() {
                return xRadius;
        }

        public short getYRadius() {
                return yRadius;
        }

        protected void applyStandingRadii(boolean adjustY) {
                setCollisionRadii(standXRadius, standYRadius, adjustY);
        }

        protected void applyRollingRadii(boolean adjustY) {
                setCollisionRadii(rollXRadius, rollYRadius, adjustY);
        }

        protected void setCollisionRadii(short newXRadius, short newYRadius, boolean adjustY) {
                this.xRadius = newXRadius;
                this.yRadius = newYRadius;
                updateSensorOffsetsFromRadii();
        }

        private void updateSensorOffsetsFromRadii() {
                if (groundSensors == null || ceilingSensors == null || pushSensors == null) {
                        return;
                }

                byte xRad = (byte) xRadius;
                byte yRad = (byte) yRadius;
                byte push = (byte) (xRadius + 1);

                if (groundSensors != null && groundSensors.length >= 2) {
                        groundSensors[0].setOffset((byte) -xRad, yRad);
                        groundSensors[1].setOffset(xRad, yRad);
                }

                if (ceilingSensors != null && ceilingSensors.length >= 2) {
                        ceilingSensors[0].setOffset((byte) -xRad, (byte) -yRad);
                        ceilingSensors[1].setOffset(xRad, (byte) -yRad);
                }

                if (pushSensors != null && pushSensors.length >= 2) {
                        pushSensors[0].setOffset((byte) -push, (byte) 0);
                        pushSensors[1].setOffset(push, (byte) 0);
                }
        }

        public SpriteMovementManager getMovementManager() {
                return movementManager;
        }

        public PlayableSpriteAnimationManager getAnimationManager() {
                return animationManager;
        }

        protected abstract void defineSpeeds();

        public final void move() {
                move(xSpeed, ySpeed);
        }

        public GroundMode getGroundMode() {
                return runningMode;
        }

        public void setGroundMode(GroundMode groundMode) {
                if (this.runningMode != groundMode) {
                        updateSpriteShapeForRunningMode(groundMode, this.runningMode);
                        this.runningMode = groundMode;
                }
        }

        protected void updateSpriteShapeForRunningMode(GroundMode newRunningMode, GroundMode oldRunningMode) {
                // Best if statement ever...
                if (((GroundMode.CEILING.equals(newRunningMode) || GroundMode.GROUND.equals(newRunningMode)) &&
                                (GroundMode.LEFTWALL.equals(oldRunningMode)
                                                || GroundMode.RIGHTWALL.equals(oldRunningMode)))
                                ||
                                ((GroundMode.RIGHTWALL.equals(newRunningMode)
                                                || GroundMode.LEFTWALL.equals(newRunningMode)) &&
                                                ((GroundMode.CEILING.equals(oldRunningMode)
                                                                || GroundMode.GROUND.equals(oldRunningMode))))) {
                        int oldHeight = getHeight();
                        int oldWidth = getWidth();

                        short oldCentreX = getCentreX();
                        short oldCentreY = getCentreY();

                        setHeight(oldWidth);
                        setWidth(oldHeight);

                        setX((short) (oldCentreX - (getWidth() / 2)));
                        setY((short) (oldCentreY - (getHeight() / 2)));
                }
        }

        public final short getCentreX(int framesBehind) {
                int desired = historyPos - framesBehind;
                if (desired < 0) {
                        desired += xHistory.length;
                }
                return (short) (xHistory[desired] + (width / 2));
        }

        public final short getCentreY(int framesBehind) {
                int desired = historyPos - framesBehind;
                if (desired < 0) {
                        desired += yHistory.length;
                }
                return (short) (yHistory[desired] + (height / 2));
        }

        public void updateSensors(short originalX, short originalY) {
                Sensor[] sensorsToActivate;
                Sensor[] sensorsToDeactivate;

                Sensor groundA = groundSensors[0];
                Sensor groundB = groundSensors[1];

                Sensor ceilingC = ceilingSensors[0];
                Sensor ceilingD = ceilingSensors[1];

                Sensor pushE = pushSensors[0];
                Sensor pushF = pushSensors[1];

                if (getAir()) {
                        short xSpeedPositive = (short) Math.abs(xSpeed);
                        short ySpeedPositive = (short) Math.abs(ySpeed);

                        if (xSpeedPositive > ySpeedPositive) {
                                if (xSpeed > 0) {
                                        sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD,
                                                        pushF };
                                        sensorsToDeactivate = new Sensor[] { pushE };
                                } else {
                                        sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD,
                                                        pushE };
                                        sensorsToDeactivate = new Sensor[] { pushF };
                                }
                        } else {
                                if (ySpeed > 0) {
                                        sensorsToActivate = new Sensor[] { groundA, groundB, pushE, pushF };
                                        sensorsToDeactivate = new Sensor[] { ceilingC, ceilingD };
                                } else {
                                        sensorsToActivate = new Sensor[] { ceilingC, ceilingD, pushE, pushF };
                                        sensorsToDeactivate = new Sensor[] { groundA, groundB };
                                }
                        }
                } else {
                        boolean pushActive = Math.abs(angle) <= 64;
                        if (xSpeed > 0) {
                                sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD, pushF };
                                sensorsToDeactivate = new Sensor[] { pushE };
                                if (!pushActive) {
                                        sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD };
                                        sensorsToDeactivate = new Sensor[] { pushE, pushF };
                                }
                        } else if (xSpeed < 0) {
                                sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD, pushE };
                                sensorsToDeactivate = new Sensor[] { pushF };
                                if (!pushActive) {
                                        sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD };
                                        sensorsToDeactivate = new Sensor[] { pushE, pushF };
                                }
                        } else {
                                sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD };
                                sensorsToDeactivate = new Sensor[] { pushE, pushF };
                        }
                }

                setSensorActive(sensorsToActivate, true);
                setSensorActive(sensorsToDeactivate, false);
        }

        private void setSensorActive(Sensor[] sensors, boolean active) {
                for (Sensor sensor : sensors) {
                        sensor.setActive(active);
                }
        }

        public Sensor[] getAllSensors() {
                Sensor[] sensors = new Sensor[6];
                sensors[0] = groundSensors[0];
                sensors[1] = groundSensors[1];
                sensors[2] = ceilingSensors[0];
                sensors[3] = ceilingSensors[1];
                sensors[4] = pushSensors[0];
                sensors[5] = pushSensors[1];

                return sensors;
        }

        public void moveForGroundModeAndDirection(byte distance, Direction direction) {
                SensorConfiguration sensorConfiguration = SpriteManager
                                .getSensorConfigurationForGroundModeAndDirection(getGroundMode(), direction);
                switch (sensorConfiguration.direction()) {
                        case DOWN -> {
                                yPixel = (short) (yPixel + distance);
                        }
                        case RIGHT -> {
                                xPixel = (short) (xPixel + distance);
                        }
                        case UP -> {
                                yPixel = (short) (yPixel - distance);
                        }
                        case LEFT -> {
                                xPixel = (short) (xPixel - distance);
                        }
                }
        }

        /**
         * Causes the sprite to update its position history as we are now at the end
         * of the tick so all movement calculations have been performed.
         */
        public void endOfTick() {
                if (historyPos == 31) {
                        historyPos = 0;
                } else {
                        historyPos++;
                }
                xHistory[historyPos] = xPixel;
                yHistory[historyPos] = yPixel;
        }

        public short getRenderCentreX() {
                return (short) (getCentreX() + renderXOffset);
        }

        public short getRenderCentreY() {
                return (short) (getCentreY() + renderYOffset);
        }

        public void setRenderOffsets(short xOffset, short yOffset) {
                this.renderXOffset = xOffset;
                this.renderYOffset = yOffset;
        }

        // ==================== Water Physics ====================

        /**
         * Updates water state based on player Y position relative to water level.
         *
         * @param waterLevelY Water surface Y position in world coordinates (pixels)
         */
        public void updateWaterState(int waterLevelY) {
                wasInWater = inWater;

                // Check if player's center Y is below water surface
                // Original uses player's Y position, not center - checking bottom of sprite
                int playerBottomY = yPixel + height;
                inWater = playerBottomY > waterLevelY;

                // Detect transitions
                if (!wasInWater && inWater) {
                        onEnterWater();
                } else if (wasInWater && !inWater) {
                        onExitWater();
                }
        }

        /**
         * Called when player enters water.
         * Applies instantaneous velocity changes per original game logic.
         */
        protected void onEnterWater() {
                LOGGER.fine("Player entered water");

                // Halve horizontal velocity (xSpeed and gSpeed)
                xSpeed = (short) (xSpeed / 2);
                gSpeed = (short) (gSpeed / 2);

                // Reduce downward velocity more significantly
                if (ySpeed > 0) {
                        ySpeed = (short) (ySpeed / 4);
                } else {
                        // Upward velocity halved
                        ySpeed = (short) (ySpeed / 2);
                }

                // TODO: Play splash sound
                // TODO: Spawn splash object
        }

        /**
         * Called when player exits water.
         * Applies velocity boost per original game logic.
         */
        protected void onExitWater() {
                LOGGER.fine("Player exited water");

                // Double horizontal velocity
                xSpeed = (short) Math.min(xSpeed * 2, max);
                gSpeed = (short) Math.min(gSpeed * 2, max);

                // Boost upward velocity (only if moving upward)
                if (ySpeed < 0) {
                        ySpeed = (short) (ySpeed * 2);
                        // Cap to reasonable value
                        if (ySpeed < -0x1000) {
                                ySpeed = -0x1000;
                        }
                }

                // TODO: Play splash sound
                // TODO: Spawn splash object
        }

        /**
         * Returns true if player is currently underwater.
         */
        public boolean isInWater() {
                return inWater;
        }

        /**
         * Sets water state directly (for loading checkpoints, testing, etc.).
         */
        public void setInWater(boolean inWater) {
                this.inWater = inWater;
                this.wasInWater = inWater;
        }

        // ==================== Physics Constant Getters with Modifiers
        // ====================
        // These apply underwater and speed shoes modifiers dynamically

        /**
         * Returns effective run acceleration, accounting for underwater and speed
         * shoes.
         * Underwater: halved
         * Speed shoes: doubled
         */
        public short getEffectiveRunAccel() {
                short value = runAccel;
                if (inWater) {
                        value = (short) (value / 2);
                }
                if (speedShoes) {
                        value = (short) (value * 2);
                }
                return value;
        }

        /**
         * Returns effective run deceleration, accounting for modifiers.
         */
        public short getEffectiveRunDecel() {
                short value = runDecel;
                if (inWater) {
                        value = (short) (value / 2);
                }
                // Speed shoes don't affect decel in original
                return value;
        }

        /**
         * Returns effective friction, accounting for modifiers.
         */
        public short getEffectiveFriction() {
                short value = friction;
                if (inWater) {
                        value = (short) (value / 2);
                }
                if (speedShoes) {
                        value = (short) (value * 2);
                }
                return value;
        }

        /**
         * Returns effective max speed, accounting for modifiers.
         * Underwater: halved
         * Speed shoes: doubled
         */
        public short getEffectiveMax() {
                short value = max;
                if (inWater) {
                        value = (short) (value / 2);
                }
                if (speedShoes) {
                        value = (short) (value * 2);
                }
                return value;
        }

        /**
         * Returns effective jump force, accounting for underwater modifier.
         * Underwater: reduced (0x300 in original vs normal 0x680)
         */
        public short getEffectiveJump() {
                if (inWater) {
                        return 0x300; // Reduced underwater jump
                }
                return jump;
        }

        /**
         * Returns effective gravity value.
         * Normal: 0x38 (56 subpixels)
         * Underwater: 0x10 (16 subpixels)
         */
        public short getEffectiveGravity() {
                if (inWater) {
                        return 0x10; // Reduced underwater gravity
                }
                return 0x38; // Normal gravity
        }

        /**
         * Returns effective air drag threshold.
         * Normal: -0x400
         * Underwater: -0x200
         */
        public short getEffectiveAirDragThreshold() {
                if (inWater) {
                        return -0x200;
                }
                return -0x400;
        }
}
