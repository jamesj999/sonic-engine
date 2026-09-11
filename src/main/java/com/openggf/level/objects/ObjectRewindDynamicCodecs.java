package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.logging.Logger;

/**
 * Shared dynamic-object rewind recreate helpers. Game-specific registries compose
 * these helpers with their concrete object classes.
 *
 * <p>The static {@link #genericRecreate(ObjectManagerSnapshot.DynamicObjectEntry, DynamicObjectRecreateContext)}
 * method is the Phase-2 uniform recreate entry point (Task 4). It supersedes per-object
 * restore hooks for classes that either:
 * <ul>
 *   <li>Can be reconstructed from the captured {@link ObjectSpawn} via
 *       {@link ObjectRegistry#create(ObjectSpawn)} (registry path), or</li>
 *   <li>Implement {@link RewindRecreatable} and supply their own creation hook
 *       ({@link RewindRecreatable} path).</li>
 * </ul>
 * Construction-spawned children continue to use the adoption keystone; they are never
 * passed to {@code genericRecreate}.
 */
public final class ObjectRewindDynamicCodecs {
    private static final Logger LOG = Logger.getLogger(ObjectRewindDynamicCodecs.class.getName());

    private ObjectRewindDynamicCodecs() {
    }

    /**
     * Generic recreate entry point for Phase-2 rewind (Task 4).
     *
     * <p>Decision tree:
     * <ol>
     *   <li>Load the captured class name.</li>
     *   <li>If the class implements {@link RewindRecreatable}: construct a minimal probe
     *       instance via {@link ObjectConstructionContext} (trying known harmless
     *       constructor signatures) and delegate to
     *       {@link RewindRecreatable#recreateForRewind(RewindRecreateContext)}.</li>
     *   <li>Else: rebuild via {@link ObjectRegistry#create(ObjectSpawn)} using the registry
     *       in {@link DynamicObjectRecreateContext#objectRegistry()}.</li>
     * </ol>
     *
     * <p><strong>Adoption safety:</strong> this method is called only AFTER
     * {@code ObjectManager.adoptRewindReconstructionChild} returns {@code null}, so it will
     * never be invoked for a class that the adoption keystone already handled. No additional
     * deduplication is needed here.
     *
     * @param entry the captured dynamic-object entry from the snapshot
     * @param ctx   restore-time context with services and registry
     * @return the recreated instance, or {@code null} if recreation is not possible
     */
    public static ObjectInstance genericRecreate(
            ObjectManagerSnapshot.DynamicObjectEntry entry,
            DynamicObjectRecreateContext ctx) {
        String className = entry.className();
        if (className == null) {
            return null;
        }
        Class<?> rawClass;
        try {
            rawClass = Class.forName(className);
        } catch (ClassNotFoundException e) {
            LOG.fine("genericRecreate: class not found: " + className);
            return null;
        }
        if (!AbstractObjectInstance.class.isAssignableFrom(rawClass)) {
            LOG.fine("genericRecreate: not an AbstractObjectInstance subclass: " + className);
            return null;
        }
        @SuppressWarnings("unchecked")
        Class<? extends AbstractObjectInstance> cls =
                (Class<? extends AbstractObjectInstance>) rawClass;

        // Path 1: RewindRecreatable — class provides its own creation hook.
        if (RewindRecreatable.class.isAssignableFrom(cls)) {
            AbstractObjectInstance probe = constructProbeForRewindRecreatable(cls, entry, ctx);
            if (probe instanceof RewindRecreatable rr) {
                RewindRecreateContext rewindCtx = new RewindRecreateContext(
                        entry.spawn(), entry.state(), ctx.objectServices(), ctx.objectManager(), entry);
                return rr.recreateForRewind(rewindCtx);
            }
            LOG.fine("genericRecreate: RewindRecreatable probe construction failed for " + className);
            return null;
        }

        // Path 2: Registry — rebuild from spawn via the game registry factory.
        ObjectRegistry registry = ctx.objectRegistry();
        if (registry == null || entry.spawn() == null) {
            return null;
        }
        try {
            return registry.create(entry.spawn());
        } catch (Exception e) {
            LOG.fine("genericRecreate: registry.create threw for " + className + ": " + e);
            return null;
        }
    }

    /**
     * Constructs a minimal probe instance of a {@link RewindRecreatable} class so that
     * {@link RewindRecreatable#recreateForRewind} can be called on it.
     *
     * <p>Tries constructors in order:
     * <ol>
     *   <li>{@code (AbstractPlayableSprite)} or {@code (PlayableEntity)} —
     *       player-bound dynamic objects using the captured owner as a probe</li>
     *   <li>non-static member constructors using a live enclosing object from
     *       {@link DynamicObjectRecreateContext#objectManager()} plus harmless
     *       placeholders — parent-linked inner children</li>
     *   <li>{@code (ObjectSpawn)} — single-arg spawn constructor</li>
     *   <li>{@code (ObjectSpawn, String)} — spawn plus harmless name placeholder</li>
     *   <li>{@code (ObjectSpawn, int)} — spawn plus harmless zero placeholder</li>
     *   <li>{@code (ObjectSpawn, boolean)} — spawn plus default false option</li>
     *   <li>{@code (ObjectSpawn, ObjectServices)} — spawn plus restore-time services</li>
     *   <li>{@code (ObjectSpawn, ObjectServices, int)} — points-style constructor
     *       with default score/frame placeholder</li>
     *   <li>{@code (ObjectSpawn, ParentType)} — spawn plus a live parent</li>
     *   <li>{@code (ObjectSpawn, ParentType, int)} — spawn, a live parent, and a
     *       harmless zero placeholder</li>
     *   <li>{@code (ObjectSpawn, ParentType, int, int)} — spawn, a live parent, and
     *       harmless zero offset placeholders</li>
     *   <li>{@code (ObjectSpawn, ParentType, int, int, boolean)} — spawn, a live parent,
     *       zero offsets, and a default option</li>
     *   <li>{@code (ObjectSpawn, ParentType, int, int, int)} — spawn, a live parent,
     *       and harmless zero coordinate/index placeholders</li>
     *   <li>{@code (ObjectSpawn, int, int, ParentType)} — spawn, coordinate
     *       placeholders, and a live or null parent placeholder</li>
     *   <li>{@code (int, int, ParentType)} — coordinate placeholders and a
     *       live parent placeholder</li>
     *   <li>{@code (ParentType)} — live parent constructor for structural children</li>
     *   <li>{@code (ParentType, int, int)} — live parent plus harmless coordinate placeholders</li>
     *   <li>{@code (ParentType, int, int, int)} — live parent plus harmless scalar placeholders</li>
     *   <li>{@code (ParentType, int, int, int, int)} — live parent plus harmless scalar placeholders</li>
     *   <li>{@code (ParentType, int, int, boolean)} — live parent plus harmless scalar placeholders</li>
     *   <li>{@code (ParentType, SiblingType, int)} — live parent/sibling plus harmless scalar placeholder</li>
     *   <li>{@code (int, int)} — primitive-only coordinate constructor with zero placeholders</li>
     *   <li>{@code (int, int, int)} — primitive-only constructor with zero placeholders</li>
     *   <li>{@code (int, int, int, int)} — primitive-only constructor with zero placeholders</li>
     *   <li>{@code (int, int, int, boolean)} — primitive-only constructor with zero/false placeholders</li>
     *   <li>zero-arg — no-argument default constructor</li>
     * </ol>
     *
     * <p><strong>Failure handling:</strong> a missing constructor signature
     * ({@link NoSuchMethodException}) is benign — the next strategy is tried, and if none
     * matches {@code null} is returned. But a constructor that EXISTS and throws mid-body
     * is a hard error: the probe-construction failure would otherwise silently produce a
     * {@code null} recreate (a missing object on rewind). Such failures are logged at
     * {@code WARNING} and re-thrown so Task-6 migration mistakes surface loudly rather than
     * corrupting restored state.
     *
     * @return the probe instance, or {@code null} if no compatible constructor signature exists
     * @throws RuntimeException if a matching constructor exists but throws while constructing
     */
    private static AbstractObjectInstance constructProbeForRewindRecreatable(
            Class<? extends AbstractObjectInstance> cls,
            ObjectManagerSnapshot.DynamicObjectEntry entry,
            DynamicObjectRecreateContext ctx) {
        ObjectSpawn spawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);

        if (entry.playerOwner() instanceof AbstractPlayableSprite player) {
            Constructor<? extends AbstractObjectInstance> playerCtor =
                    findCtor(cls, AbstractPlayableSprite.class);
            if (playerCtor != null) {
                return invokeProbeCtor(cls, playerCtor, ctx, player);
            }
        }

        if (entry.playerOwner() instanceof PlayableEntity player) {
            Constructor<? extends AbstractObjectInstance> playerCtor =
                    findCtor(cls, PlayableEntity.class);
            if (playerCtor != null) {
                return invokeProbeCtor(cls, playerCtor, ctx, player);
            }
        }

        AbstractObjectInstance enclosingInstance = findLiveEnclosingInstanceForProbe(cls, ctx);
        if (enclosingInstance != null) {
            Class<?> enclosingType = cls.getEnclosingClass();
            Constructor<? extends AbstractObjectInstance> enclosingCtor = findCtor(cls, enclosingType);
            if (enclosingCtor != null) {
                return invokeProbeCtor(cls, enclosingCtor, ctx, enclosingInstance);
            }

            Constructor<? extends AbstractObjectInstance> enclosingSpawnCtor =
                    findCtor(cls, enclosingType, ObjectSpawn.class);
            if (enclosingSpawnCtor != null) {
                return invokeProbeCtor(cls, enclosingSpawnCtor, ctx, enclosingInstance, spawn);
            }

            Constructor<? extends AbstractObjectInstance> enclosingIntIntIntCtor =
                    findCtor(cls, enclosingType, int.class, int.class, int.class);
            if (enclosingIntIntIntCtor != null) {
                return invokeProbeCtor(cls, enclosingIntIntIntCtor, ctx, enclosingInstance, 0, 0, 0);
            }

            Constructor<? extends AbstractObjectInstance> enclosingIntIntIntIntCtor =
                    findCtor(cls, enclosingType, int.class, int.class, int.class, int.class);
            if (enclosingIntIntIntIntCtor != null) {
                return invokeProbeCtor(cls, enclosingIntIntIntIntCtor, ctx, enclosingInstance, 0, 0, 0, 0);
            }

            Constructor<? extends AbstractObjectInstance> enclosingIntIntIntBooleanCtor =
                    findCtor(cls, enclosingType, int.class, int.class, int.class, boolean.class);
            if (enclosingIntIntIntBooleanCtor != null) {
                return invokeProbeCtor(
                        cls, enclosingIntIntIntBooleanCtor, ctx, enclosingInstance, 0, 0, 0, false);
            }
        }

        Constructor<? extends AbstractObjectInstance> spawnCtor = findCtor(cls, ObjectSpawn.class);
        if (spawnCtor != null) {
            return invokeProbeCtor(cls, spawnCtor, ctx, spawn);
        }

        Constructor<? extends AbstractObjectInstance> spawnStringCtor =
                findCtor(cls, ObjectSpawn.class, String.class);
        if (spawnStringCtor != null) {
            return invokeProbeCtor(cls, spawnStringCtor, ctx, spawn, "RewindProbe");
        }

        Constructor<? extends AbstractObjectInstance> spawnIntCtor =
                findCtor(cls, ObjectSpawn.class, int.class);
        if (spawnIntCtor != null) {
            return invokeProbeCtor(cls, spawnIntCtor, ctx, spawn, 0);
        }

        Constructor<? extends AbstractObjectInstance> spawnIntIntCtor =
                findCtor(cls, ObjectSpawn.class, int.class, int.class);
        if (spawnIntIntCtor != null) {
            return invokeProbeCtor(cls, spawnIntIntCtor, ctx, spawn, 0, 0);
        }

        Constructor<? extends AbstractObjectInstance> spawnBooleanCtor =
                findCtor(cls, ObjectSpawn.class, boolean.class);
        if (spawnBooleanCtor != null) {
            return invokeProbeCtor(cls, spawnBooleanCtor, ctx, spawn, false);
        }

        Constructor<? extends AbstractObjectInstance> spawnServicesCtor =
                findCtor(cls, ObjectSpawn.class, ObjectServices.class);
        if (spawnServicesCtor != null) {
            return invokeProbeCtor(cls, spawnServicesCtor, ctx, spawn, ctx.objectServices());
        }

        Constructor<? extends AbstractObjectInstance> spawnServicesIntCtor =
                findCtor(cls, ObjectSpawn.class, ObjectServices.class, int.class);
        if (spawnServicesIntCtor != null) {
            return invokeProbeCtor(cls, spawnServicesIntCtor, ctx, spawn, ctx.objectServices(), 0);
        }

        AbstractObjectInstance spawnParentProbe =
                constructSpawnParentProbe(cls, spawn, ctx);
        if (spawnParentProbe != null) {
            return spawnParentProbe;
        }

        AbstractObjectInstance spawnParentIntProbe =
                constructSpawnParentIntProbe(cls, spawn, ctx);
        if (spawnParentIntProbe != null) {
            return spawnParentIntProbe;
        }

        AbstractObjectInstance spawnParentIntIntProbe =
                constructSpawnParentIntIntProbe(cls, spawn, ctx);
        if (spawnParentIntIntProbe != null) {
            return spawnParentIntIntProbe;
        }

        AbstractObjectInstance spawnParentIntIntBooleanProbe =
                constructSpawnParentIntIntBooleanProbe(cls, spawn, ctx);
        if (spawnParentIntIntBooleanProbe != null) {
            return spawnParentIntIntBooleanProbe;
        }

        AbstractObjectInstance spawnParentIntIntIntProbe =
                constructSpawnParentIntIntIntProbe(cls, spawn, ctx);
        if (spawnParentIntIntIntProbe != null) {
            return spawnParentIntIntIntProbe;
        }

        AbstractObjectInstance spawnIntIntParentProbe =
                constructSpawnIntIntParentProbe(cls, spawn, ctx);
        if (spawnIntIntParentProbe != null) {
            return spawnIntIntParentProbe;
        }

        AbstractObjectInstance intIntParentProbe =
                constructIntIntParentProbe(cls, ctx);
        if (intIntParentProbe != null) {
            return intIntParentProbe;
        }

        AbstractObjectInstance parentProbe =
                constructParentProbe(cls, ctx);
        if (parentProbe != null) {
            return parentProbe;
        }

        AbstractObjectInstance parentIntIntProbe =
                constructParentIntIntProbe(cls, ctx);
        if (parentIntIntProbe != null) {
            return parentIntIntProbe;
        }

        AbstractObjectInstance parentIntIntIntProbe =
                constructParentIntIntIntProbe(cls, ctx);
        if (parentIntIntIntProbe != null) {
            return parentIntIntIntProbe;
        }

        AbstractObjectInstance parentIntIntIntIntProbe =
                constructParentIntIntIntIntProbe(cls, ctx);
        if (parentIntIntIntIntProbe != null) {
            return parentIntIntIntIntProbe;
        }

        AbstractObjectInstance parentIntIntBooleanProbe =
                constructParentIntIntBooleanProbe(cls, ctx);
        if (parentIntIntBooleanProbe != null) {
            return parentIntIntBooleanProbe;
        }

        AbstractObjectInstance parentSiblingIntProbe =
                constructParentSiblingIntProbe(cls, ctx);
        if (parentSiblingIntProbe != null) {
            return parentSiblingIntProbe;
        }

        Constructor<? extends AbstractObjectInstance> intIntCtor =
                findCtor(cls, int.class, int.class);
        if (intIntCtor != null) {
            return invokeProbeCtor(cls, intIntCtor, ctx, 0, 0);
        }

        Constructor<? extends AbstractObjectInstance> intIntIntCtor =
                findCtor(cls, int.class, int.class, int.class);
        if (intIntIntCtor != null) {
            return invokeProbeCtor(cls, intIntIntCtor, ctx, 0, 0, 0);
        }

        Constructor<? extends AbstractObjectInstance> intIntIntIntCtor =
                findCtor(cls, int.class, int.class, int.class, int.class);
        if (intIntIntIntCtor != null) {
            return invokeProbeCtor(cls, intIntIntIntCtor, ctx, 0, 0, 0, 0);
        }

        Constructor<? extends AbstractObjectInstance> intIntIntBooleanCtor =
                findCtor(cls, int.class, int.class, int.class, boolean.class);
        if (intIntIntBooleanCtor != null) {
            return invokeProbeCtor(cls, intIntIntBooleanCtor, ctx, 0, 0, 0, false);
        }

        Constructor<? extends AbstractObjectInstance> noArgCtor = findCtor(cls);
        if (noArgCtor != null) {
            return invokeProbeCtor(cls, noArgCtor, ctx);
        }

        // No supported probe constructor — cannot build a probe for this class.
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AbstractObjectInstance constructSpawnParentProbe(
            Class<? extends AbstractObjectInstance> cls,
            ObjectSpawn spawn,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 2
                    || params[0] != ObjectSpawn.class
                    || !ObjectInstance.class.isAssignableFrom(params[1])) {
                continue;
            }
            ObjectInstance parent = findLiveAssignableParentForProbe(params[1], ctx);
            if (parent == null) {
                return null;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            return invokeProbeCtor(cls, ctor, ctx, spawn, parent);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AbstractObjectInstance constructSpawnParentIntProbe(
            Class<? extends AbstractObjectInstance> cls,
            ObjectSpawn spawn,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 3
                    || params[0] != ObjectSpawn.class
                    || !ObjectInstance.class.isAssignableFrom(params[1])
                    || params[2] != int.class) {
                continue;
            }
            ObjectInstance parent = findLiveAssignableParentForProbe(params[1], ctx);
            if (parent == null) {
                return null;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            return invokeProbeCtor(cls, ctor, ctx, spawn, parent, 0);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AbstractObjectInstance constructSpawnParentIntIntProbe(
            Class<? extends AbstractObjectInstance> cls,
            ObjectSpawn spawn,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 4
                    || params[0] != ObjectSpawn.class
                    || !ObjectInstance.class.isAssignableFrom(params[1])
                    || params[2] != int.class
                    || params[3] != int.class) {
                continue;
            }
            ObjectInstance parent = findLiveAssignableParentForProbe(params[1], ctx);
            if (parent == null) {
                return null;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            return invokeProbeCtor(cls, ctor, ctx, spawn, parent, 0, 0);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AbstractObjectInstance constructSpawnParentIntIntBooleanProbe(
            Class<? extends AbstractObjectInstance> cls,
            ObjectSpawn spawn,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 5
                    || params[0] != ObjectSpawn.class
                    || !ObjectInstance.class.isAssignableFrom(params[1])
                    || params[2] != int.class
                    || params[3] != int.class
                    || params[4] != boolean.class) {
                continue;
            }
            ObjectInstance parent = findLiveAssignableParentForProbe(params[1], ctx);
            if (parent == null) {
                return null;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            return invokeProbeCtor(cls, ctor, ctx, spawn, parent, 0, 0, false);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AbstractObjectInstance constructSpawnParentIntIntIntProbe(
            Class<? extends AbstractObjectInstance> cls,
            ObjectSpawn spawn,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 5
                    || params[0] != ObjectSpawn.class
                    || !ObjectInstance.class.isAssignableFrom(params[1])
                    || params[2] != int.class
                    || params[3] != int.class
                    || params[4] != int.class) {
                continue;
            }
            ObjectInstance parent = findLiveAssignableParentForProbe(params[1], ctx);
            if (parent == null) {
                return null;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            return invokeProbeCtor(cls, ctor, ctx, spawn, parent, 0, 0, 0);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AbstractObjectInstance constructSpawnIntIntParentProbe(
            Class<? extends AbstractObjectInstance> cls,
            ObjectSpawn spawn,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 4
                    || params[0] != ObjectSpawn.class
                    || params[1] != int.class
                    || params[2] != int.class
                    || !AbstractObjectInstance.class.isAssignableFrom(params[3])) {
                continue;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            ObjectInstance parent = findLiveAssignableParentForProbe(params[3], ctx);
            if (parent == null) {
                return null;
            }
            return invokeProbeCtor(
                    cls, ctor, ctx, spawn, 0, 0, parent);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AbstractObjectInstance constructIntIntParentProbe(
            Class<? extends AbstractObjectInstance> cls,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 3
                    || params[0] != int.class
                    || params[1] != int.class
                    || !ObjectInstance.class.isAssignableFrom(params[2])) {
                continue;
            }
            ObjectInstance parent = findLiveAssignableParentForProbe(params[2], ctx);
            if (parent == null) {
                return null;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            return invokeProbeCtor(cls, ctor, ctx, 0, 0, parent);
        }
        return null;
    }

    private static ObjectInstance findLiveAssignableParentForProbe(
            Class<?> parentType,
            DynamicObjectRecreateContext ctx) {
        if (ctx == null || ctx.objectManager() == null) {
            return null;
        }
        for (ObjectInstance instance : ctx.objectManager().getActiveObjects()) {
            if (parentType.isInstance(instance) && !instance.isDestroyed()) {
                return instance;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AbstractObjectInstance constructParentProbe(
            Class<? extends AbstractObjectInstance> cls,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 1
                    || !ObjectInstance.class.isAssignableFrom(params[0])) {
                continue;
            }
            ObjectInstance parent = findLiveAssignableParentForProbe(params[0], ctx);
            if (parent == null) {
                return null;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            return invokeProbeCtor(cls, ctor, ctx, parent);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AbstractObjectInstance constructParentIntIntProbe(
            Class<? extends AbstractObjectInstance> cls,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 3
                    || !ObjectInstance.class.isAssignableFrom(params[0])
                    || params[1] != int.class
                    || params[2] != int.class) {
                continue;
            }
            ObjectInstance parent = findLiveAssignableParentForProbe(params[0], ctx);
            if (parent == null) {
                return null;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            return invokeProbeCtor(cls, ctor, ctx, parent, 0, 0);
        }
        return null;
    }

    private static AbstractObjectInstance constructParentIntIntIntProbe(
            Class<? extends AbstractObjectInstance> cls,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 4
                    || !ObjectInstance.class.isAssignableFrom(params[0])
                    || params[1] != int.class
                    || params[2] != int.class
                    || params[3] != int.class) {
                continue;
            }
            ObjectInstance parent = findLiveAssignableParentForProbe(params[0], ctx);
            if (parent == null) {
                return null;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            return invokeProbeCtor(cls, ctor, ctx, parent, 0, 0, 0);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AbstractObjectInstance constructParentIntIntIntIntProbe(
            Class<? extends AbstractObjectInstance> cls,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 5
                    || !ObjectInstance.class.isAssignableFrom(params[0])
                    || params[1] != int.class
                    || params[2] != int.class
                    || params[3] != int.class
                    || params[4] != int.class) {
                continue;
            }
            ObjectInstance parent = findLiveAssignableParentForProbe(params[0], ctx);
            if (parent == null) {
                return null;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            return invokeProbeCtor(cls, ctor, ctx, parent, 0, 0, 0, 0);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AbstractObjectInstance constructParentIntIntBooleanProbe(
            Class<? extends AbstractObjectInstance> cls,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 4
                    || !ObjectInstance.class.isAssignableFrom(params[0])
                    || params[1] != int.class
                    || params[2] != int.class
                    || params[3] != boolean.class) {
                continue;
            }
            ObjectInstance parent = findLiveAssignableParentForProbe(params[0], ctx);
            if (parent == null) {
                return null;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            return invokeProbeCtor(cls, ctor, ctx, parent, 0, 0, false);
        }
        return null;
    }

    private static AbstractObjectInstance constructParentSiblingIntProbe(
            Class<? extends AbstractObjectInstance> cls,
            DynamicObjectRecreateContext ctx) {
        for (Constructor<?> rawCtor : cls.getDeclaredConstructors()) {
            Class<?>[] params = rawCtor.getParameterTypes();
            if (params.length != 3
                    || !ObjectInstance.class.isAssignableFrom(params[0])
                    || !ObjectInstance.class.isAssignableFrom(params[1])
                    || params[2] != int.class) {
                continue;
            }
            ObjectInstance parent = findLiveAssignableParentForProbe(params[0], ctx);
            ObjectInstance sibling = findLiveAssignableParentForProbe(params[1], ctx);
            if (parent == null || sibling == null) {
                return null;
            }
            Constructor<? extends AbstractObjectInstance> ctor =
                    (Constructor<? extends AbstractObjectInstance>) rawCtor;
            ctor.setAccessible(true);
            return invokeProbeCtor(cls, ctor, ctx, parent, sibling, 0);
        }
        return null;
    }

    private static AbstractObjectInstance findLiveEnclosingInstanceForProbe(
            Class<? extends AbstractObjectInstance> cls,
            DynamicObjectRecreateContext ctx) {
        if (!cls.isMemberClass() || Modifier.isStatic(cls.getModifiers())) {
            return null;
        }
        Class<?> enclosingType = cls.getEnclosingClass();
        if (enclosingType == null || !AbstractObjectInstance.class.isAssignableFrom(enclosingType)
                || ctx == null || ctx.objectManager() == null) {
            return null;
        }
        for (ObjectInstance instance : ctx.objectManager().getActiveObjects()) {
            if (enclosingType.isInstance(instance) && instance instanceof AbstractObjectInstance aoi) {
                return aoi;
            }
        }
        return null;
    }

    /**
     * Looks up a declared constructor with the given parameter types, returning {@code null}
     * when no such signature exists (a benign "try the next strategy" condition).
     */
    private static Constructor<? extends AbstractObjectInstance> findCtor(
            Class<? extends AbstractObjectInstance> cls, Class<?>... paramTypes) {
        try {
            Constructor<? extends AbstractObjectInstance> ctor =
                    cls.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Invokes a probe constructor that is known to exist. Any exception thrown while
     * constructing (the constructor body failing) is logged at {@code WARNING} and
     * re-thrown — it is never swallowed into a {@code null} recreate.
     */
    private static AbstractObjectInstance invokeProbeCtor(
            Class<? extends AbstractObjectInstance> cls,
            Constructor<? extends AbstractObjectInstance> ctor,
            DynamicObjectRecreateContext ctx,
            Object... args) {
        try {
            return ObjectConstructionContext.withProbeConstruction(() -> ObjectConstructionContext.construct(
                    ctx.objectServices(),
                    () -> {
                        try {
                            return ctor.newInstance(args);
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    }));
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING,
                    "genericRecreate: RewindRecreatable probe constructor threw for "
                            + cls.getName() + "; recreate cannot proceed", e);
            throw e;
        }
    }

}
