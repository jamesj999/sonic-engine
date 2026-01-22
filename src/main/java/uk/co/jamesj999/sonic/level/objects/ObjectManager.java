package uk.co.jamesj999.sonic.level.objects;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.spawn.AbstractPlacementManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class ObjectManager {
    private static final int BUCKET_COUNT = RenderPriority.MAX - RenderPriority.MIN + 1;

    private final Placement placement;
    private final ObjectRegistry registry;
    private final GraphicsManager graphicsManager = GraphicsManager.getInstance();
    private final Map<ObjectSpawn, ObjectInstance> activeObjects = new IdentityHashMap<>();
    private final List<ObjectInstance> dynamicObjects = new ArrayList<>();
    private final List<GLCommand> renderCommands = new ArrayList<>();
    private int frameCounter;

    // Pre-bucketed lists for O(n) rendering instead of O(n*buckets)
    @SuppressWarnings("unchecked")
    private final List<ObjectInstance>[] lowPriorityBuckets = new ArrayList[BUCKET_COUNT];
    @SuppressWarnings("unchecked")
    private final List<ObjectInstance>[] highPriorityBuckets = new ArrayList[BUCKET_COUNT];
    private boolean bucketsDirty = true;

    private final PlaneSwitchers planeSwitchers;
    private final SolidContacts solidContacts;
    private final TouchResponses touchResponses;

    public ObjectManager(List<ObjectSpawn> spawns, ObjectRegistry registry,
            int planeSwitcherObjectId, PlaneSwitcherConfig planeSwitcherConfig,
            TouchResponseTable touchResponseTable) {
        this.placement = new Placement(spawns);
        this.registry = registry;
        this.planeSwitchers = planeSwitcherConfig != null
                ? new PlaneSwitchers(placement, planeSwitcherObjectId, planeSwitcherConfig)
                : null;
        this.solidContacts = new SolidContacts(this);
        this.touchResponses = touchResponseTable != null
                ? new TouchResponses(this, touchResponseTable)
                : null;
        // Initialize bucket arrays
        for (int i = 0; i < BUCKET_COUNT; i++) {
            lowPriorityBuckets[i] = new ArrayList<>();
            highPriorityBuckets[i] = new ArrayList<>();
        }
    }

    public void reset(int cameraX) {
        activeObjects.clear();
        dynamicObjects.clear();
        frameCounter = 0;
        placement.reset(cameraX);
        registry.reportCoverage(placement.getAllSpawns());
        if (planeSwitchers != null) {
            planeSwitchers.reset();
        }
        solidContacts.reset();
        if (touchResponses != null) {
            touchResponses.reset();
        }
    }

    void resetTouchResponses() {
        if (touchResponses != null) {
            touchResponses.reset();
        }
    }

    public void update(int cameraX, AbstractPlayableSprite player, int touchFrameCounter) {
        placement.update(cameraX);
        frameCounter++;
        bucketsDirty = true; // Mark for re-bucketing since objects may have changed
        updateCameraBounds();
        syncActiveSpawns();

        Iterator<ObjectInstance> dynamicIterator = dynamicObjects.iterator();
        while (dynamicIterator.hasNext()) {
            ObjectInstance instance = dynamicIterator.next();
            instance.update(frameCounter, player);
            if (instance.isDestroyed()) {
                instance.onUnload();
                dynamicIterator.remove();
            }
        }

        Iterator<Map.Entry<ObjectSpawn, ObjectInstance>> iterator = activeObjects.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ObjectSpawn, ObjectInstance> entry = iterator.next();
            ObjectInstance instance = entry.getValue();
            instance.update(frameCounter, player);
            if (instance.isDestroyed()) {
                instance.onUnload();
                iterator.remove();
            }
        }

        solidContacts.update(player);
        if (touchResponses != null) {
            touchResponses.update(player, touchFrameCounter);
        }
    }

    public void applyPlaneSwitchers(AbstractPlayableSprite player) {
        if (planeSwitchers != null) {
            planeSwitchers.update(player);
        }
    }

    public int getPlaneSwitcherSideState(ObjectSpawn spawn) {
        if (planeSwitchers == null) {
            return -1;
        }
        return planeSwitchers.getSideState(spawn);
    }

    public void drawLowPriority() {
        ensureBucketsPopulated();
        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            drawPriorityBucket(bucket, false);
        }
    }

    public void drawHighPriority() {
        ensureBucketsPopulated();
        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            drawPriorityBucket(bucket, true);
        }
    }

    private void ensureBucketsPopulated() {
        if (!bucketsDirty) {
            return;
        }
        bucketsDirty = false;

        // Clear all buckets
        for (int i = 0; i < BUCKET_COUNT; i++) {
            lowPriorityBuckets[i].clear();
            highPriorityBuckets[i].clear();
        }

        // Bucket active objects
        for (ObjectInstance instance : activeObjects.values()) {
            int bucket = RenderPriority.clamp(instance.getPriorityBucket());
            int idx = bucket - RenderPriority.MIN;
            if (instance.isHighPriority()) {
                highPriorityBuckets[idx].add(instance);
            } else {
                lowPriorityBuckets[idx].add(instance);
            }
        }

        // Bucket dynamic objects
        for (ObjectInstance instance : dynamicObjects) {
            int bucket = RenderPriority.clamp(instance.getPriorityBucket());
            int idx = bucket - RenderPriority.MIN;
            if (instance.isHighPriority()) {
                highPriorityBuckets[idx].add(instance);
            } else {
                lowPriorityBuckets[idx].add(instance);
            }
        }
    }

    public void drawPriorityBucket(int bucket, boolean highPriority) {
        ensureBucketsPopulated();
        int targetBucket = RenderPriority.clamp(bucket);
        int idx = targetBucket - RenderPriority.MIN;
        List<ObjectInstance>[] buckets = highPriority ? highPriorityBuckets : lowPriorityBuckets;
        List<ObjectInstance> instances = buckets[idx];

        if (instances.isEmpty()) {
            return;
        }

        renderCommands.clear();
        for (ObjectInstance instance : instances) {
            instance.appendRenderCommands(renderCommands);
        }

        if (renderCommands.isEmpty()) {
            return;
        }
        graphicsManager.enqueueDebugLineState();
        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, renderCommands));
        graphicsManager.enqueueDefaultShaderState();
    }

    public Collection<ObjectInstance> getActiveObjects() {
        List<ObjectInstance> all = new ArrayList<>(activeObjects.values());
        all.addAll(dynamicObjects);
        return all;
    }

    public Collection<ObjectSpawn> getActiveSpawns() {
        return placement.getActiveSpawns();
    }

    public void addDynamicObject(ObjectInstance object) {
        dynamicObjects.add(object);
    }

    public boolean isRemembered(ObjectSpawn spawn) {
        return placement.isRemembered(spawn);
    }

    public void markRemembered(ObjectSpawn spawn) {
        placement.markRemembered(spawn);
    }

    public void clearRemembered() {
        placement.clearRemembered();
    }

    /**
     * Removes a spawn from the active set without marking it as remembered.
     * The spawn can still respawn when the camera leaves and re-enters the area.
     * Used for badniks which should respawn on camera re-entry but not immediately.
     */
    public void removeFromActiveSpawns(ObjectSpawn spawn) {
        placement.removeFromActive(spawn);
    }

    public boolean isRidingObject() {
        return solidContacts.isRidingObject();
    }

    public boolean isRidingObject(ObjectInstance instance) {
        return solidContacts.isRidingObject(instance);
    }

    public void clearRidingObject() {
        solidContacts.clearRidingObject();
    }

    public boolean hasStandingContact(AbstractPlayableSprite player) {
        return solidContacts.hasStandingContact(player);
    }

    public int getHeadroomDistance(AbstractPlayableSprite player, int hexAngle) {
        return solidContacts.getHeadroomDistance(player, hexAngle);
    }

    /**
     * Run solid contacts resolution for a player sprite.
     * This is called by the CollisionSystem as part of the unified collision pipeline.
     */
    public void updateSolidContacts(AbstractPlayableSprite player) {
        solidContacts.update(player);
    }

    public TouchResponseDebugState getTouchResponseDebugState() {
        return touchResponses != null ? touchResponses.getDebugState() : null;
    }

    private void syncActiveSpawns() {
        Collection<ObjectSpawn> activeSpawns = placement.getActiveSpawns();
        for (ObjectSpawn spawn : activeSpawns) {
            if (!activeObjects.containsKey(spawn)) {
                ObjectInstance instance = registry.create(spawn);
                activeObjects.put(spawn, instance);
            }
        }

        Iterator<Map.Entry<ObjectSpawn, ObjectInstance>> iterator = activeObjects.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ObjectSpawn, ObjectInstance> entry = iterator.next();
            if (!activeSpawns.contains(entry.getKey())) {
                if (!entry.getValue().isPersistent()) {
                    entry.getValue().onUnload();
                    iterator.remove();
                }
            }
        }
    }

    private void updateCameraBounds() {
        Camera camera = Camera.getInstance();
        int left = camera.getX();
        int top = camera.getY();
        int right = left + camera.getWidth();
        int bottom = top + camera.getHeight();
        AbstractObjectInstance.updateCameraBounds(left, top, right, bottom);
    }

    public static int decodePlaneSwitcherHalfSpan(int subtype) {
        return PlaneSwitchers.decodeHalfSpan(subtype);
    }

    public static boolean isPlaneSwitcherHorizontal(int subtype) {
        return PlaneSwitchers.isHorizontal(subtype);
    }

    public static int decodePlaneSwitcherPath(int subtype, int side) {
        return PlaneSwitchers.decodePath(subtype, side);
    }

    public static boolean decodePlaneSwitcherPriority(int subtype, int side) {
        return PlaneSwitchers.decodePriority(subtype, side);
    }

    public static boolean planeSwitcherGroundedOnly(int subtype) {
        return PlaneSwitchers.onlySwitchWhenGrounded(subtype);
    }

    public static char formatPlaneSwitcherLayer(byte layer) {
        return PlaneSwitchers.formatLayer(layer);
    }

    public static char formatPlaneSwitcherPriority(boolean highPriority) {
        return PlaneSwitchers.formatPriority(highPriority);
    }

    static final class Placement extends AbstractPlacementManager<ObjectSpawn> {
        private static final int LOAD_AHEAD = 0x280;
        private static final int UNLOAD_BEHIND = 0x300;

        private final BitSet remembered = new BitSet();
        /** Tracks spawns destroyed while in the window - prevents respawn until they leave the window. */
        private final BitSet destroyedInWindow = new BitSet();
        private int cursorIndex = 0;
        private int lastCameraX = Integer.MIN_VALUE;

        Placement(List<ObjectSpawn> spawns) {
            super(spawns, LOAD_AHEAD, UNLOAD_BEHIND);
        }

        void reset(int cameraX) {
            active.clear();
            remembered.clear();
            destroyedInWindow.clear();
            cursorIndex = 0;
            lastCameraX = cameraX;
            refreshWindow(cameraX);
        }

        void update(int cameraX) {
            if (spawns.isEmpty()) {
                return;
            }
            if (lastCameraX == Integer.MIN_VALUE) {
                reset(cameraX);
                return;
            }

            int delta = cameraX - lastCameraX;
            if (delta < 0 || delta > (getLoadAhead() + getUnloadBehind())) {
                refreshWindow(cameraX);
            } else {
                spawnForward(cameraX);
                trimActive(cameraX);
            }

            lastCameraX = cameraX;
        }

        public List<ObjectSpawn> getAllSpawns() {
            return spawns;
        }

        void markRemembered(ObjectSpawn spawn) {
            if (spawn.objectId() != 0x26) {
                active.remove(spawn);
            }

            int index = getSpawnIndex(spawn);
            if (index < 0) {
                return;
            }
            remembered.set(index);
        }

        boolean isRemembered(ObjectSpawn spawn) {
            int index = getSpawnIndex(spawn);
            return index >= 0 && remembered.get(index);
        }

        void clearRemembered() {
            remembered.clear();
        }

        /**
         * Removes a spawn from the active set without marking it as remembered.
         * The spawn won't respawn until it leaves the camera window entirely.
         * Used for badniks which should respawn on camera re-entry but not immediately.
         */
        void removeFromActive(ObjectSpawn spawn) {
            active.remove(spawn);
            int index = getSpawnIndex(spawn);
            if (index >= 0) {
                destroyedInWindow.set(index);
            }
        }

        private void spawnForward(int cameraX) {
            int spawnLimit = cameraX + getLoadAhead();
            while (cursorIndex < spawns.size() && spawns.get(cursorIndex).x() <= spawnLimit) {
                trySpawn(cursorIndex);
                cursorIndex++;
            }
        }

        private void trimActive(int cameraX) {
            int windowStart = getWindowStart(cameraX);
            int windowEnd = getWindowEnd(cameraX);
            Iterator<ObjectSpawn> iterator = active.iterator();
            while (iterator.hasNext()) {
                ObjectSpawn spawn = iterator.next();
                if (spawn.x() < windowStart || spawn.x() > windowEnd) {
                    iterator.remove();
                }
            }
            // Clear destroyedInWindow for spawns that have left the window (allows respawn on return)
            for (int i = destroyedInWindow.nextSetBit(0); i >= 0; i = destroyedInWindow.nextSetBit(i + 1)) {
                ObjectSpawn spawn = spawns.get(i);
                if (spawn.x() < windowStart || spawn.x() > windowEnd) {
                    destroyedInWindow.clear(i);
                }
            }
        }

        private void refreshWindow(int cameraX) {
            int windowStart = getWindowStart(cameraX);
            int windowEnd = getWindowEnd(cameraX);
            int start = lowerBound(windowStart);
            int end = upperBound(windowEnd);
            cursorIndex = end;
            active.clear();
            for (int i = start; i < end; i++) {
                trySpawn(i);
            }
        }

        private void trySpawn(int index) {
            ObjectSpawn spawn = spawns.get(index);
            if (remembered.get(index) && spawn.objectId() != 0x26) {
                return;
            }
            // Don't respawn if destroyed while still in the window
            if (destroyedInWindow.get(index)) {
                return;
            }
            active.add(spawn);
        }
    }

    static final class PlaneSwitchers {
        private static final int[] HALF_SPANS = new int[]{0x20, 0x40, 0x80, 0x100};
        private static final int MASK_SIZE = 0x03;
        private static final int MASK_HORIZONTAL = 0x04;
        private static final int MASK_PATH_SIDE1 = 0x08;
        private static final int MASK_PATH_SIDE0 = 0x10;
        private static final int MASK_PRIORITY_SIDE1 = 0x20;
        private static final int MASK_PRIORITY_SIDE0 = 0x40;
        private static final int MASK_GROUNDED_ONLY = 0x80;

        private final Placement placement;
        private final int objectId;
        private final PlaneSwitcherConfig config;
        private final Map<ObjectSpawn, PlaneSwitcherState> states = new HashMap<>();

        PlaneSwitchers(Placement placement, int objectId, PlaneSwitcherConfig config) {
            this.placement = placement;
            this.objectId = objectId & 0xFF;
            this.config = config;
        }

        void reset() {
            states.clear();
        }

        void update(AbstractPlayableSprite player) {
            if (placement == null || player == null || config == null) {
                return;
            }
            Collection<ObjectSpawn> active = placement.getActiveSpawns();
            if (active.isEmpty()) {
                return;
            }

            int playerX = player.getCentreX();
            int playerY = player.getCentreY();

            for (ObjectSpawn spawn : active) {
                if (spawn.objectId() != objectId) {
                    continue;
                }

                int subtype = spawn.subtype();
                PlaneSwitcherState state = states.computeIfAbsent(spawn,
                        key -> new PlaneSwitcherState(decodeHalfSpan(subtype)));

                boolean horizontal = isHorizontal(subtype);
                int sideNow = horizontal
                        ? (playerY >= spawn.y() ? 1 : 0)
                        : (playerX >= spawn.x() ? 1 : 0);
                if (!state.seeded) {
                    state.sideState = (byte) sideNow;
                    state.seeded = true;
                }

                int half = state.halfSpanPixels;
                boolean inSpan = horizontal
                        ? (playerX >= spawn.x() - half && playerX < spawn.x() + half)
                        : (playerY >= spawn.y() - half && playerY < spawn.y() + half);
                boolean groundedGate = onlySwitchWhenGrounded(subtype) && player.getAir();

                if (inSpan && !groundedGate && sideNow != state.sideState) {
                    boolean skipCollisionChange = (spawn.renderFlags() & 0x1) != 0;
                    if (!skipCollisionChange) {
                        int path = decodePath(subtype, sideNow);
                        player.setLayer((byte) path);
                        if (path == 0) {
                            player.setTopSolidBit(config.getPath0TopSolidBit());
                            player.setLrbSolidBit(config.getPath0LrbSolidBit());
                        } else {
                            player.setTopSolidBit(config.getPath1TopSolidBit());
                            player.setLrbSolidBit(config.getPath1LrbSolidBit());
                        }
                    }
                    boolean highPriority = decodePriority(subtype, sideNow);
                    player.setHighPriority(highPriority);
                }

                state.sideState = (byte) sideNow;
            }

            states.keySet().removeIf(spawn -> spawn.objectId() == objectId && !active.contains(spawn));
        }

        int getSideState(ObjectSpawn spawn) {
            PlaneSwitcherState state = states.get(spawn);
            if (state == null || !state.seeded) {
                return -1;
            }
            return state.sideState;
        }

        static int decodeHalfSpan(int subtype) {
            int index = subtype & MASK_SIZE;
            if (index < 0 || index >= HALF_SPANS.length) {
                index = 0;
            }
            return HALF_SPANS[index];
        }

        static boolean isHorizontal(int subtype) {
            return (subtype & MASK_HORIZONTAL) != 0;
        }

        static int decodePath(int subtype, int side) {
            int mask = side == 1 ? MASK_PATH_SIDE1 : MASK_PATH_SIDE0;
            return (subtype & mask) != 0 ? 1 : 0;
        }

        static boolean decodePriority(int subtype, int side) {
            int mask = side == 1 ? MASK_PRIORITY_SIDE1 : MASK_PRIORITY_SIDE0;
            return (subtype & mask) != 0;
        }

        static boolean onlySwitchWhenGrounded(int subtype) {
            return (subtype & MASK_GROUNDED_ONLY) != 0;
        }

        static char formatLayer(byte layer) {
            return layer == 0 ? 'A' : 'B';
        }

        static char formatPriority(boolean highPriority) {
            return highPriority ? 'H' : 'L';
        }

        private static final class PlaneSwitcherState {
            private final int halfSpanPixels;
            private byte sideState = 0;
            private boolean seeded = false;

            private PlaneSwitcherState(int halfSpanPixels) {
                this.halfSpanPixels = halfSpanPixels;
            }
        }
    }

    static final class TouchResponses {
        private final ObjectManager objectManager;
        private final TouchResponseTable table;
        private final Set<ObjectInstance> overlapping = Collections.newSetFromMap(new IdentityHashMap<>());
        private final TouchResponseDebugState debugState = new TouchResponseDebugState();
        private int currentFrameCounter;

        TouchResponses(ObjectManager objectManager, TouchResponseTable table) {
            this.objectManager = objectManager;
            this.table = table;
        }

        void reset() {
            overlapping.clear();
            currentFrameCounter = 0;
        }

        void update(AbstractPlayableSprite player, int frameCounter) {
            currentFrameCounter = frameCounter;
            if (player == null || objectManager == null || player.getDead() || table == null) {
                overlapping.clear();
                debugState.clear();
                return;
            }

            if (player.isDebugMode()) {
                overlapping.clear();
                debugState.clear();
                return;
            }

            int playerX = player.getCentreX() - 8;
            int baseYRadius = Math.max(1, player.getYRadius() - 3);
            int playerY = player.getCentreY() - 8 - baseYRadius;
            int playerHeight = baseYRadius * 2;
            boolean crouching = player.getCrouching();
            if (crouching) {
                playerY += 12;
                playerHeight = 20;
            }
            debugState.setPlayer(playerX, playerY, playerHeight, baseYRadius, crouching);
            debugState.clear();

            Set<ObjectInstance> current = Collections.newSetFromMap(new IdentityHashMap<>());
            Collection<ObjectInstance> activeObjects = objectManager.getActiveObjects();
            for (ObjectInstance instance : activeObjects) {
                if (!(instance instanceof TouchResponseProvider provider)) {
                    continue;
                }
                int flags = provider.getCollisionFlags();
                int sizeIndex = flags & 0x3F;
                int width = table.getWidthRadius(sizeIndex);
                int height = table.getHeightRadius(sizeIndex);
                TouchCategory category = decodeCategory(flags);

                boolean overlap = isOverlapping(playerX, playerY, playerHeight, instance.getSpawn(), width, height);
                debugState.addHit(
                        new TouchResponseDebugHit(instance.getSpawn(), flags, sizeIndex, width, height, category, overlap));
                if (!overlap) {
                    continue;
                }

                current.add(instance);
                if (!overlapping.contains(instance)) {
                    TouchResponseResult result = new TouchResponseResult(sizeIndex, width, height, category);
                    TouchResponseListener listener = instance instanceof TouchResponseListener casted ? casted : null;
                    handleTouchResponse(player, instance, listener, result);
                }
            }

            overlapping.clear();
            overlapping.addAll(current);
        }

        private boolean isOverlapping(int playerX, int playerY, int playerHeight,
                ObjectSpawn spawn, int objectWidth, int objectHeight) {
            int dx = spawn.x() - objectWidth - playerX;
            if (dx < 0) {
                int sum = (dx & 0xFFFF) + ((objectWidth * 2) & 0xFFFF);
                if (sum <= 0xFFFF) {
                    return false;
                }
            } else if (dx > 0x10) {
                return false;
            }

            int dy = spawn.y() - objectHeight - playerY;
            if (dy < 0) {
                int sum = (dy & 0xFFFF) + ((objectHeight * 2) & 0xFFFF);
                if (sum <= 0xFFFF) {
                    return false;
                }
            } else if (dy > playerHeight) {
                return false;
            }

            return true;
        }

        private TouchCategory decodeCategory(int flags) {
            int categoryBits = flags & 0xC0;
            return switch (categoryBits) {
                case 0x00 -> TouchCategory.ENEMY;
                case 0x40 -> TouchCategory.SPECIAL;
                case 0x80 -> TouchCategory.HURT;
                default -> TouchCategory.BOSS;
            };
        }

        private void handleTouchResponse(AbstractPlayableSprite player, ObjectInstance instance,
                TouchResponseListener listener, TouchResponseResult result) {
            if (player == null) {
                return;
            }
            if (listener != null) {
                listener.onTouchResponse(player, result, currentFrameCounter);
            }

            switch (result.category()) {
                case HURT -> applyHurt(player, instance);
                case ENEMY -> {
                    if (isPlayerAttacking(player)) {
                        if (instance instanceof TouchResponseAttackable attackable) {
                            attackable.onPlayerAttack(player, result);
                        }
                        applyEnemyBounce(player, instance);
                    } else {
                        applyHurt(player, instance);
                    }
                }
                case SPECIAL, BOSS -> {
                    // Listener handles object-specific logic.
                }
            }
        }

        private boolean isPlayerAttacking(AbstractPlayableSprite player) {
            return player.getInvincibleFrames() > 0
                    || player.getRolling()
                    || player.getSpindash();
        }

        private void applyEnemyBounce(AbstractPlayableSprite player, ObjectInstance instance) {
            player.setAir(true);
            short ySpeed = player.getYSpeed();
            if (ySpeed < 0) {
                player.setYSpeed((short) (ySpeed + 0x100));
                return;
            }
            int playerY = player.getY();
            int enemyY = instance != null ? instance.getY() : playerY;
            if (playerY < enemyY) {
                player.setYSpeed((short) -ySpeed);
            } else {
                player.setYSpeed((short) (ySpeed - 0x100));
            }
        }

        private void applyHurt(AbstractPlayableSprite player, ObjectInstance instance) {
            if (player.getInvulnerable()) {
                return;
            }
            int sourceX = instance != null ? instance.getX() : player.getCentreX();
            boolean spikeHit = instance != null && instance.getSpawn().objectId() == 0x36;
            boolean hadRings = player.getRingCount() > 0;
            if (hadRings && !player.hasShield()) {
                LevelManager.getInstance().spawnLostRings(player, currentFrameCounter);
            }
            player.applyHurtOrDeath(sourceX, spikeHit, hadRings);
        }

        TouchResponseDebugState getDebugState() {
            return debugState;
        }
    }

    static final class SolidContacts {
        private static final Logger LOGGER = Logger.getLogger(SolidContacts.class.getName());
        private final ObjectManager objectManager;
        private int frameCounter;
        private ObjectInstance ridingObject;
        private int ridingX;
        private int ridingY;
        private int ridingPieceIndex = -1;

        SolidContacts(ObjectManager objectManager) {
            this.objectManager = objectManager;
        }

        void reset() {
            frameCounter = 0;
            ridingObject = null;
            ridingPieceIndex = -1;
        }

        boolean isRidingObject() {
            return ridingObject != null;
        }

        boolean isRidingObject(ObjectInstance instance) {
            return ridingObject == instance;
        }

        void clearRidingObject() {
            ridingObject = null;
        }

        boolean hasStandingContact(AbstractPlayableSprite player) {
            if (player == null || objectManager == null || player.getDead()) {
                return false;
            }
            if (player.isDebugMode()) {
                return false;
            }
            if (player.getYSpeed() < 0) {
                return false;
            }
            Collection<ObjectInstance> activeObjects = objectManager.getActiveObjects();
            for (ObjectInstance instance : activeObjects) {
                if (!(instance instanceof SolidObjectProvider provider)) {
                    continue;
                }
                if (!provider.isSolidFor(player)) {
                    continue;
                }

                if (provider instanceof MultiPieceSolidProvider multiPiece) {
                    if (hasStandingContactMultiPiece(player, multiPiece, instance)) {
                        return true;
                    }
                    continue;
                }

                SolidObjectParams params = provider.getSolidParams();
                int anchorX = instance.getSpawn().x() + params.offsetX();
                int anchorY = instance.getSpawn().y() + params.offsetY();
                int halfHeight = player.getAir() ? params.airHalfHeight() : params.groundHalfHeight();
                byte[] slopeData = null;
                if (instance instanceof SlopedSolidProvider sloped) {
                    slopeData = sloped.getSlopeData();
                }
                SolidContact contact;
                if (slopeData != null && instance instanceof SlopedSolidProvider sloped) {
                    int slopeHalfHeight = params.groundHalfHeight();
                    contact = resolveSlopedContact(player, anchorX, anchorY, params.halfWidth(), slopeHalfHeight,
                            slopeData, sloped.isSlopeFlipped(), provider.isTopSolidOnly(), instance, false);
                } else {
                    contact = resolveContact(player, anchorX, anchorY, params.halfWidth(), halfHeight,
                            provider.isTopSolidOnly(), instance, false);
                }
                if (contact != null && contact.standing()) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasStandingContactMultiPiece(AbstractPlayableSprite player,
                MultiPieceSolidProvider multiPiece, ObjectInstance instance) {
            int pieceCount = multiPiece.getPieceCount();
            for (int i = 0; i < pieceCount; i++) {
                SolidObjectParams params = multiPiece.getPieceParams(i);
                int anchorX = multiPiece.getPieceX(i) + params.offsetX();
                int anchorY = multiPiece.getPieceY(i) + params.offsetY();
                int halfHeight = player.getAir() ? params.airHalfHeight() : params.groundHalfHeight();

                SolidContact contact = resolveContact(player, anchorX, anchorY, params.halfWidth(), halfHeight,
                        multiPiece.isTopSolidOnly(), instance, false);
                if (contact != null && contact.standing()) {
                    return true;
                }
            }
            return false;
        }

        int getHeadroomDistance(AbstractPlayableSprite player, int hexAngle) {
            if (player == null || objectManager == null || player.getDead()) {
                return Integer.MAX_VALUE;
            }
            if (player.isDebugMode()) {
                return Integer.MAX_VALUE;
            }

            int overheadAngle = (hexAngle + 0x80) & 0xFF;
            int quadrant = (overheadAngle + 0x20) & 0xC0;

            int minDistance = Integer.MAX_VALUE;
            int playerCenterX = player.getCentreX();
            int playerCenterY = player.getCentreY();
            int playerXRadius = player.getXRadius();
            int playerYRadius = player.getYRadius();

            Collection<ObjectInstance> activeObjects = objectManager.getActiveObjects();
            for (ObjectInstance instance : activeObjects) {
                if (!(instance instanceof SolidObjectProvider provider)) {
                    continue;
                }
                if (!provider.isSolidFor(player)) {
                    continue;
                }
                if (provider.isTopSolidOnly()) {
                    continue;
                }
                SolidObjectParams params = provider.getSolidParams();
                int anchorX = instance.getSpawn().x() + params.offsetX();
                int anchorY = instance.getSpawn().y() + params.offsetY();
                int halfWidth = params.halfWidth();
                int halfHeight = params.groundHalfHeight();

                int distance = calculateOverheadDistance(quadrant, playerCenterX, playerCenterY,
                        playerXRadius, playerYRadius, anchorX, anchorY, halfWidth, halfHeight);
                if (distance >= 0 && distance < minDistance) {
                    minDistance = distance;
                }
            }
            return minDistance;
        }

        private int calculateOverheadDistance(int quadrant, int playerCenterX, int playerCenterY,
                int playerXRadius, int playerYRadius, int objX, int objY, int objHalfWidth, int objHalfHeight) {
            switch (quadrant) {
                case 0x40 -> {
                    int objRight = objX + objHalfWidth;
                    int playerLeft = playerCenterX - playerXRadius;
                    if (playerLeft < objRight) {
                        return -1;
                    }
                    int objTop = objY - objHalfHeight;
                    int objBottom = objY + objHalfHeight;
                    int playerTop = playerCenterY - playerYRadius;
                    int playerBottom = playerCenterY + playerYRadius;
                    if (playerBottom < objTop || playerTop > objBottom) {
                        return -1;
                    }
                    return playerLeft - objRight;
                }
                case 0x80 -> {
                    int objBottom = objY + objHalfHeight;
                    int playerTop = playerCenterY - playerYRadius;
                    if (playerTop < objBottom) {
                        return -1;
                    }
                    int objLeft = objX - objHalfWidth;
                    int objRight = objX + objHalfWidth;
                    int playerLeft = playerCenterX - playerXRadius;
                    int playerRight = playerCenterX + playerXRadius;
                    if (playerRight < objLeft || playerLeft > objRight) {
                        return -1;
                    }
                    return playerTop - objBottom;
                }
                case 0xC0 -> {
                    int objLeft = objX - objHalfWidth;
                    int playerRight = playerCenterX + playerXRadius;
                    if (playerRight > objLeft) {
                        return -1;
                    }
                    int objTop = objY - objHalfHeight;
                    int objBottom = objY + objHalfHeight;
                    int playerTop = playerCenterY - playerYRadius;
                    int playerBottom = playerCenterY + playerYRadius;
                    if (playerBottom < objTop || playerTop > objBottom) {
                        return -1;
                    }
                    return objLeft - playerRight;
                }
                default -> {
                    return Integer.MAX_VALUE;
                }
            }
        }

        void update(AbstractPlayableSprite player) {
            frameCounter++;
            if (player == null || objectManager == null || player.getDead()) {
                ridingObject = null;
                return;
            }

            if (player.isDebugMode()) {
                ridingObject = null;
                return;
            }

            player.setPushing(false);

            Collection<ObjectInstance> activeObjects = objectManager.getActiveObjects();

            if (ridingObject != null && ridingObject instanceof SolidObjectProvider provider) {
                int currentX;
                int currentY;
                SolidObjectParams params;

                if (ridingPieceIndex >= 0 && ridingObject instanceof MultiPieceSolidProvider multiPiece) {
                    currentX = multiPiece.getPieceX(ridingPieceIndex);
                    currentY = multiPiece.getPieceY(ridingPieceIndex);
                    params = multiPiece.getPieceParams(ridingPieceIndex);
                } else {
                    currentX = ridingObject.getX();
                    currentY = ridingObject.getY();
                    params = provider.getSolidParams();
                }

                int halfWidth = params.halfWidth();
                int relX = player.getCentreX() - currentX + halfWidth;
                boolean inBounds = relX >= 0 && relX < halfWidth * 2;

                if (inBounds && provider.isSolidFor(player)) {
                    int deltaX = currentX - ridingX;
                    int deltaY = currentY - ridingY;
                    if (deltaX != 0) {
                        int baseX = player.getCentreX() - (player.getWidth() / 2);
                        player.setX((short) (baseX + deltaX));
                    }
                    if (deltaY != 0) {
                        int baseY = player.getCentreY() - (player.getHeight() / 2);
                        player.setY((short) (baseY + deltaY));
                    }
                    ridingX = currentX;
                    ridingY = currentY;
                } else {
                    ridingObject = null;
                    ridingPieceIndex = -1;
                }
            }

            ObjectInstance nextRidingObject = null;
            int nextRidingX = 0;
            int nextRidingY = 0;
            for (ObjectInstance instance : activeObjects) {
                if (!(instance instanceof SolidObjectProvider provider)) {
                    continue;
                }
                if (!provider.isSolidFor(player)) {
                    continue;
                }

                if (provider instanceof MultiPieceSolidProvider multiPiece) {
                    MultiPieceContactResult result = processMultiPieceCollision(player, multiPiece, instance, frameCounter);
                    if (result.pushing) {
                        player.setPushing(true);
                    }
                    if (result.standing) {
                        nextRidingObject = instance;
                        nextRidingX = result.ridingX;
                        nextRidingY = result.ridingY;
                        ridingPieceIndex = result.pieceIndex;
                    }
                    continue;
                }

                SolidObjectParams params = provider.getSolidParams();
                int anchorX = instance.getSpawn().x() + params.offsetX();
                int anchorY = instance.getSpawn().y() + params.offsetY();
                int halfHeight = player.getAir() ? params.airHalfHeight() : params.groundHalfHeight();
                SolidContact contact;
                byte[] slopeData = null;
                if (instance instanceof SlopedSolidProvider sloped) {
                    slopeData = sloped.getSlopeData();
                }

                if (slopeData != null && instance instanceof SlopedSolidProvider sloped) {
                    int slopeHalfHeight = params.groundHalfHeight();
                    contact = resolveSlopedContact(player, anchorX, anchorY, params.halfWidth(), slopeHalfHeight,
                            slopeData, sloped.isSlopeFlipped(), provider.isTopSolidOnly(), instance, true);
                } else {
                    contact = resolveContact(player, anchorX, anchorY, params.halfWidth(), halfHeight,
                            provider.isTopSolidOnly(), instance, true);
                }
                if (contact == null) {
                    continue;
                }
                if (contact.pushing()) {
                    player.setPushing(true);
                }
                if (contact.standing()) {
                    nextRidingObject = instance;
                    nextRidingX = instance.getX();
                    nextRidingY = instance.getY();
                    ridingPieceIndex = -1;
                }
                if (instance instanceof SolidObjectListener listener) {
                    listener.onSolidContact(player, contact, frameCounter);
                }
            }
            ridingObject = nextRidingObject;
            ridingX = nextRidingX;
            ridingY = nextRidingY;
        }

        private record MultiPieceContactResult(boolean standing, boolean pushing, int ridingX, int ridingY, int pieceIndex) {}

        private MultiPieceContactResult processMultiPieceCollision(AbstractPlayableSprite player,
                MultiPieceSolidProvider multiPiece, ObjectInstance instance, int frameCounter) {
            int pieceCount = multiPiece.getPieceCount();

            boolean anyStanding = false;
            boolean anyPushing = false;
            boolean anyTouchTop = false;
            boolean anyTouchBottom = false;
            boolean anyTouchSide = false;

            int standingPieceIndex = -1;
            int standingPieceX = 0;
            int standingPieceY = 0;

            for (int i = 0; i < pieceCount; i++) {
                SolidObjectParams params = multiPiece.getPieceParams(i);
                int pieceX = multiPiece.getPieceX(i);
                int pieceY = multiPiece.getPieceY(i);
                int anchorX = pieceX + params.offsetX();
                int anchorY = pieceY + params.offsetY();
                int halfHeight = player.getAir() ? params.airHalfHeight() : params.groundHalfHeight();

                SolidContact contact = resolveContact(player, anchorX, anchorY, params.halfWidth(), halfHeight,
                        multiPiece.isTopSolidOnly(), instance, true);

                if (contact == null) {
                    continue;
                }

                if (contact.standing()) {
                    anyStanding = true;
                    if (standingPieceIndex < 0) {
                        standingPieceIndex = i;
                        standingPieceX = pieceX;
                        standingPieceY = pieceY;
                    }
                }
                if (contact.touchTop()) {
                    anyTouchTop = true;
                }
                if (contact.touchBottom()) {
                    anyTouchBottom = true;
                }
                if (contact.touchSide()) {
                    anyTouchSide = true;
                }
                if (contact.pushing()) {
                    anyPushing = true;
                }

                multiPiece.onPieceContact(i, player, contact, frameCounter);
            }

            if (anyStanding || anyTouchTop || anyTouchBottom || anyTouchSide || anyPushing) {
                if (instance instanceof SolidObjectListener listener) {
                    SolidContact aggregateContact = new SolidContact(
                            anyStanding, anyTouchSide, anyTouchBottom, anyTouchTop, anyPushing);
                    listener.onSolidContact(player, aggregateContact, frameCounter);
                }
            }

            return new MultiPieceContactResult(anyStanding, anyPushing, standingPieceX, standingPieceY, standingPieceIndex);
        }

        private SolidContact resolveContact(AbstractPlayableSprite player,
                int anchorX, int anchorY, int halfWidth, int halfHeight, boolean topSolidOnly, ObjectInstance instance,
                boolean apply) {
            int playerCenterX = player.getCentreX();
            int playerCenterY = player.getCentreY();

            int relX = playerCenterX - anchorX + halfWidth;
            if (relX < 0 || relX > halfWidth * 2) {
                return null;
            }

            int playerYRadius = player.getYRadius();
            int maxTop = halfHeight + playerYRadius;
            int relY = playerCenterY - anchorY + 4 + maxTop;

            boolean riding = isRidingObject(instance);
            int minRelY = riding ? -16 : 0;

            if (relY < minRelY || relY >= maxTop * 2) {
                return null;
            }

            return resolveContactInternal(player, relX, relY, halfWidth, maxTop, playerCenterX, playerCenterY,
                    topSolidOnly, riding, apply);
        }

        private SolidContact resolveSlopedContact(AbstractPlayableSprite player, int anchorX, int anchorY, int halfWidth,
                int halfHeight, byte[] slopeData, boolean xFlip, boolean topSolidOnly, ObjectInstance instance,
                boolean apply) {
            if (slopeData == null || slopeData.length == 0) {
                return null;
            }
            int playerCenterX = player.getCentreX();
            int playerCenterY = player.getCentreY();

            int relX = playerCenterX - anchorX + halfWidth;
            int width2 = halfWidth * 2;
            if (relX < 0 || relX > width2) {
                return null;
            }

            int sampleX = relX;
            if (xFlip) {
                sampleX = width2 - sampleX;
            }
            sampleX = sampleX >> 1;
            if (sampleX < 0 || sampleX >= slopeData.length) {
                return null;
            }

            int slopeSample = (byte) slopeData[sampleX];
            int slopeBase = (byte) slopeData[0];
            boolean riding = isRidingObject(instance);
            int minRelY = riding ? -16 : 0;

            int slopeOffset = slopeSample - slopeBase;
            int baseY = anchorY - slopeOffset;

            int playerYRadius = player.getYRadius();
            int maxTop = halfHeight + playerYRadius;
            int relY = playerCenterY - baseY + 4 + maxTop;

            if (relY < minRelY || relY >= maxTop * 2) {
                return null;
            }

            return resolveContactInternal(player, relX, relY, halfWidth, maxTop, playerCenterX, playerCenterY,
                    topSolidOnly, riding, apply);
        }

        private SolidContact resolveContactInternal(AbstractPlayableSprite player, int relX, int relY, int halfWidth,
                int maxTop, int playerCenterX, int playerCenterY, boolean topSolidOnly, boolean sticky, boolean apply) {
            int distX;
            int absDistX;
            if (relX >= halfWidth) {
                distX = relX - (halfWidth * 2);
                absDistX = -distX;
            } else {
                distX = relX;
                absDistX = distX;
            }

            int distY;
            int absDistY;
            if (relY <= maxTop) {
                distY = relY;
                absDistY = distY;
            } else {
                distY = relY - 4 - (maxTop * 2);
                absDistY = Math.abs(distY);
            }

            if (absDistX <= absDistY) {
                if (topSolidOnly) {
                    return null;
                }
                boolean leftSide = distX > 0;
                boolean nearVerticalEdge = absDistY <= 6;

                if (nearVerticalEdge) {
                    return new SolidContact(false, true, false, false, false);
                }

                boolean pushing = !player.getAir();
                boolean movingInto = leftSide ? player.getXSpeed() > 0 : player.getXSpeed() < 0;
                if (apply) {
                    if (movingInto) {
                        player.setXSpeed((short) 0);
                        player.setGSpeed((short) 0);
                    }
                    player.setCentreX((short) (playerCenterX - distX));
                }
                return new SolidContact(false, true, false, false, pushing);
            }

            if (distY >= 0 || (sticky && distY >= -16)) {
                if (player.getYSpeed() < 0) {
                    return null;
                }

                if (topSolidOnly && player.getYSpeed() < 0) {
                    return null;
                }

                int landingThreshold = 0x18;
                if (distY >= landingThreshold) {
                    return null;
                }

                if (apply) {
                    int newCenterY = playerCenterY - distY + 3;
                    int newY = newCenterY - (player.getHeight() / 2);
                    player.setY((short) newY);
                    if (player.getYSpeed() > 0) {
                        player.setYSpeed((short) 0);
                    }
                    if (player.getAir()) {
                        LOGGER.fine(() -> "Solid object landing at (" + player.getX() + "," + player.getY() +
                            ") distY=" + distY);
                        player.setGSpeed(player.getXSpeed());
                        player.setAir(false);
                        if (!player.getPinballMode()) {
                            player.setRolling(false);
                        }
                        player.setPinballMode(false);
                        player.setAngle((byte) 0);
                        player.setGroundMode(GroundMode.GROUND);
                    }
                }
                return new SolidContact(true, false, false, true, false);
            }

            if (topSolidOnly) {
                return null;
            }

            if (player.getYSpeed() == 0 && !player.getAir()) {
                return null;
            }

            if (apply) {
                int newCenterY = playerCenterY - distY;
                int newY = newCenterY - (player.getHeight() / 2);
                player.setY((short) newY);
                if (player.getYSpeed() < 0) {
                    LOGGER.fine(() -> "Solid object ceiling hit, zeroing ySpeed from " + player.getYSpeed());
                    player.setYSpeed((short) 0);
                }
            }
            return new SolidContact(false, false, true, false, false);
        }
    }
}
