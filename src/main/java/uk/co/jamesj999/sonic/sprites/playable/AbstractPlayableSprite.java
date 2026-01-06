package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.data.games.Sonic2Constants;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.InvincibilityStarsObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ShieldObjectInstance;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.managers.DebugSpriteMovementManager;
import uk.co.jamesj999.sonic.sprites.SensorConfiguration;
import uk.co.jamesj999.sonic.sprites.managers.PlayableSpriteMovementManager;
import uk.co.jamesj999.sonic.sprites.managers.PlayableSpriteAnimationManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteMovementManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.render.PlayerSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationProfile;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;
import uk.co.jamesj999.sonic.sprites.managers.SpindashDustManager;

/**
 * Movement speeds are in subpixels (256 subpixels per pixel...).
 * 
 * @author james
 * 
 */
public abstract class AbstractPlayableSprite extends AbstractSprite {
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
                DROWN
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

        protected int ringCount = 0;
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
        private SpindashDustManager spindashDustManager;

        protected boolean shield = false;
        private ShieldObjectInstance shieldObject;
        private InvincibilityStarsObjectInstance invincibilityObject;
        protected boolean speedShoes = false;
        protected int speedShoesFrames = 0;

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
                this.speedShoesFrames = 0;
                this.invincibleFrames = 0;
                this.invulnerableFrames = 0;
                this.ringCount = 0;
                this.dead = false;
                this.hurt = false;
                this.deathCountdown = 0;
                this.air = false;
                this.springing = false;
                this.springingFrames = 0;
                this.rolling = false;
                this.spindash = false;
                this.pushing = false;
                this.crouching = false;
                defineSpeeds(); // Reset speeds to default
        }

        public void giveShield() {
                System.out.println("DEBUG: giveShield() called. Current shield state: " + shield);
                if (hasShield()) {
                        System.out.println("DEBUG: Player already has shield. Returning.");
                        return;
                }
                this.shield = true;
                System.out.println("DEBUG: Shield flag set to true.");
                try {
                        this.shieldObject = new ShieldObjectInstance(this);
                        System.out.println("DEBUG: ShieldObjectInstance created successfully: " + shieldObject);
                        LevelManager.getInstance().getObjectManager().addDynamicObject(shieldObject);
                        System.out.println("DEBUG: ShieldObjectInstance added to ObjectManager.");
                } catch (Exception e) {
                        System.out.println("DEBUG: Failed to create/add ShieldObjectInstance: " + e.getMessage());
                        e.printStackTrace();
                        throw e;
                }
        }

        public void giveSpeedShoes() {
                this.speedShoes = true;
                this.speedShoesFrames = 1200; // 20 seconds @ 60fps
                defineSpeeds(); // Recalculate speeds
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
                return ringCount;
        }

        public void setRingCount(int ringCount) {
                this.ringCount = Math.max(0, ringCount);
        }

        public void addRings(int delta) {
                if (delta == 0) {
                        return;
                }
                int next = ringCount + delta;
                ringCount = Math.max(0, next);
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

        public boolean getAir() {
                return air;
        }

        public void setAir(boolean air) {
                // If landing from hurt state, set invulnerability and clear hurt
                if (!air && this.air && hurt) {
                        hurt = false;
                        setInvulnerableFrames(0x78); // 120 frames invulnerability on landing
                }
                // TODO Update ground sensors here
                this.air = air;
                if (air) {
                        setGroundMode(GroundMode.GROUND);
                        setAngle((byte) 0);
                }
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
                return invulnerableFrames > 0 || invincibleFrames > 0 || hurt;
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
                                AudioManager.getInstance().getBackend().restoreMusic();
                        }
                }
                if (springingFrames > 0) {
                        springingFrames--;
                        if (springingFrames == 0) {
                                springing = false;
                        }
                }
                if (speedShoesFrames > 0) {
                        speedShoesFrames--;
                        if (speedShoesFrames == 0) {
                                speedShoes = false;
                                defineSpeeds();
                                AudioManager.getInstance().playMusic(
                                                Sonic2Constants.CMD_SLOW_DOWN);
                        }
                }
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
                        System.out.println("DEBUG: applyHurt called. removing shield.");
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
                        System.out.println("DEBUG: applyHurt called. No shield.");
                }

                hurt = true; // Set hurt state - invulnerability is applied on landing (ROM behavior)
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
                AudioManager.getInstance().playSfx(resolveDamageSound(cause));
                return true;
        }

        private GameSound resolveDamageSound(DamageCause cause) {
                return switch (cause) {
                        case SPIKE -> GameSound.HURT_SPIKE;
                        case DROWN -> GameSound.DROWN;
                        default -> GameSound.HURT;
                };
        }

        public float getSpindashConstant() {
                return spindashConstant;
        }

        public void setSpindashConstant(float spindashConstant) {
                this.spindashConstant = spindashConstant;
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

        protected AbstractPlayableSprite(String code, short x, short y, boolean debug) {
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
                if (debug) {
                        movementManager = new DebugSpriteMovementManager(this);
                } else {
                        movementManager = new PlayableSpriteMovementManager(this);
                }
                animationManager = new PlayableSpriteAnimationManager(this);
        }

        public short getGSpeed() {
                return gSpeed;
        }

        public void setGSpeed(short gSpeed) {
                this.gSpeed = gSpeed;
        }

        public short getRunAccel() {
                return runAccel;
        }

        public short getRunDecel() {
                return runDecel;
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
                return friction;
        }

        public short getMax() {
                return max;
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
}
