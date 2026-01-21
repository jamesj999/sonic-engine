package uk.co.jamesj999.sonic.level.objects;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.graphics.RenderPriority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ObjectManager {
    private final ObjectPlacementManager placementManager;
    private final ObjectRegistry registry;
    private final GraphicsManager graphicsManager = GraphicsManager.getInstance();
    private final Map<ObjectSpawn, ObjectInstance> activeObjects = new IdentityHashMap<>();
    private final List<ObjectInstance> dynamicObjects = new ArrayList<>();
    private final List<GLCommand> renderCommands = new ArrayList<>();
    private int frameCounter;

    public ObjectManager(ObjectPlacementManager placementManager, ObjectRegistry registry) {
        this.placementManager = placementManager;
        this.registry = registry;
    }

    public void reset(int cameraX, List<ObjectSpawn> allSpawns) {
        activeObjects.clear();
        dynamicObjects.clear();
        frameCounter = 0;
        placementManager.reset(cameraX);
        registry.reportCoverage(allSpawns);
    }

    public void update(int cameraX, AbstractPlayableSprite player) {
        placementManager.update(cameraX);
        frameCounter++;
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
                placementManager.markRemembered(entry.getKey());
                iterator.remove();
            }
        }
    }

    public void drawLowPriority() {
        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            drawPriorityBucket(bucket, false);
        }
    }

    public void drawHighPriority() {
        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            drawPriorityBucket(bucket, true);
        }
    }

    public void drawPriorityBucket(int bucket, boolean highPriority) {
        if (activeObjects.isEmpty() && dynamicObjects.isEmpty()) {
            return;
        }
        renderCommands.clear();
        int targetBucket = RenderPriority.clamp(bucket);
        for (ObjectInstance instance : activeObjects.values()) {
            if (instance.isHighPriority() != highPriority) {
                continue;
            }
            if (RenderPriority.clamp(instance.getPriorityBucket()) != targetBucket) {
                continue;
            }
            instance.appendRenderCommands(renderCommands);
        }
        for (ObjectInstance instance : dynamicObjects) {
            if (instance.isHighPriority() != highPriority) {
                continue;
            }
            if (RenderPriority.clamp(instance.getPriorityBucket()) != targetBucket) {
                continue;
            }
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

    public void addDynamicObject(ObjectInstance object) {
        dynamicObjects.add(object);
    }

    private void syncActiveSpawns() {
        Collection<ObjectSpawn> activeSpawns = placementManager.getActiveSpawns();
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
                // Don't remove persistent objects (e.g., spin tubes controlling a player)
                if (!entry.getValue().isPersistent()) {
                    entry.getValue().onUnload();
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Computes camera bounds once per frame and caches them for all objects.
     * ROM equivalent: Objects read Camera_X_pos/Camera_Y_pos in MarkObjGone.
     */
    private void updateCameraBounds() {
        Camera camera = Camera.getInstance();
        int left = camera.getX();
        int top = camera.getY();
        int right = left + camera.getWidth();
        int bottom = top + camera.getHeight();
        AbstractObjectInstance.updateCameraBounds(new CameraBounds(left, top, right, bottom));
    }
}

