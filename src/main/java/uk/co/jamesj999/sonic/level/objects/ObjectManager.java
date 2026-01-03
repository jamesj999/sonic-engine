package uk.co.jamesj999.sonic.level.objects;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

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
    private int frameCounter;

    public ObjectManager(ObjectPlacementManager placementManager) {
        this(placementManager, ObjectRegistry.getInstance());
    }

    public ObjectManager(ObjectPlacementManager placementManager, ObjectRegistry registry) {
        this.placementManager = placementManager;
        this.registry = registry;
    }

    public void reset(int cameraX, List<ObjectSpawn> allSpawns) {
        activeObjects.clear();
        frameCounter = 0;
        placementManager.reset(cameraX);
        registry.reportCoverage(allSpawns);
    }

    public void update(int cameraX, AbstractPlayableSprite player) {
        placementManager.update(cameraX);
        frameCounter++;
        syncActiveSpawns();

        Iterator<Map.Entry<ObjectSpawn, ObjectInstance>> iterator = activeObjects.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ObjectSpawn, ObjectInstance> entry = iterator.next();
            ObjectInstance instance = entry.getValue();
            instance.update(frameCounter, player);
            if (instance.isDestroyed()) {
                placementManager.markRemembered(entry.getKey());
                iterator.remove();
            }
        }
    }

    public void drawLowPriority() {
        draw(false);
    }

    public void drawHighPriority() {
        draw(true);
    }

    private void draw(boolean highPriority) {
        if (activeObjects.isEmpty()) {
            return;
        }
        List<GLCommand> commands = new ArrayList<>();
        for (ObjectInstance instance : activeObjects.values()) {
            if (instance.isHighPriority() != highPriority) {
                continue;
            }
            instance.appendRenderCommands(commands);
        }

        if (commands.isEmpty()) {
            return;
        }
        graphicsManager.enqueueDebugLineState();
        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, commands));
        graphicsManager.enqueueDefaultShaderState();
    }

    public Collection<ObjectInstance> getActiveObjects() {
        return List.copyOf(activeObjects.values());
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
                iterator.remove();
            }
        }
    }
}
