package com.openggf.game.rewind.schema;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.game.rewind.RewindStateful;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.ObjectRefKind;
import com.openggf.game.rewind.identity.PlayerRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.level.Palette;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.PlatformBobHelper;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.AnimationTimer;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class RewindCodecs {
    private static final ConcurrentHashMap<Field, Optional<RewindCodec>> FIELD_CODEC_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, List<Field>> PLAIN_STATE_FIELDS_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, RecordCodec> NESTED_RECORD_CODEC_CACHE =
            new ConcurrentHashMap<>();

    private static final Set<Class<?>> WRAPPER_TYPES = Set.of(
            Boolean.class,
            Byte.class,
            Character.class,
            Short.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class
    );

    public static Optional<RewindCodec> codecFor(Field field) {
        Objects.requireNonNull(field, "field");
        // Not computeIfAbsent: buildCodecFor re-enters codecFor for plain-state holder
        // fields, and ConcurrentHashMap forbids map updates inside a mapping function.
        Optional<RewindCodec> cached = FIELD_CODEC_CACHE.get(field);
        if (cached != null) {
            return cached;
        }
        Optional<RewindCodec> built = buildCodecFor(field);
        Optional<RewindCodec> existing = FIELD_CODEC_CACHE.putIfAbsent(field, built);
        return existing != null ? existing : built;
    }

    private static Optional<RewindCodec> buildCodecFor(Field field) {
        Optional<RewindCodec> collectionCodec = collectionCodecFor(field);
        Optional<RewindCodec> codec = collectionCodec.isPresent() ? collectionCodec : codecFor(field.getType());
        if (codec.isPresent()) {
            field.setAccessible(true);
        }
        return codec;
    }

    public static Optional<RewindCodec> codecFor(Class<?> type) {
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive() || WRAPPER_TYPES.contains(type)) {
            return Optional.of(new ScalarCodec(type));
        }
        if (type == String.class) {
            return Optional.of(new OpaqueCodec());
        }
        if (type.isEnum()) {
            return Optional.of(new EnumCodec(type));
        }
        if (isPlayerReferenceType(type)) {
            return Optional.of(new PlayerReferenceCodec(type));
        }
        if (isObjectReferenceType(type)) {
            return Optional.of(new ObjectReferenceCodec(type));
        }
        if (type.isArray() && supportedArrayComponent(type.getComponentType())) {
            return Optional.of(new ArrayCodec(type.getComponentType()));
        }
        if (type == Palette.Color.class) {
            return Optional.of(new PaletteColorCodec());
        }
        if (RewindStateful.class.isAssignableFrom(type)) {
            return Optional.of(new StatefulValueCodec());
        }
        if (type == BitSet.class) {
            return Optional.of(new BitSetCodec());
        }
        if (type == SubpixelMotion.State.class) {
            return Optional.of(new SubpixelMotionStateCodec());
        }
        if (type == ObjectAnimationState.class) {
            return Optional.of(new ObjectAnimationStateCodec());
        }
        if (type == PlatformBobHelper.class) {
            return Optional.of(new PlatformBobHelperCodec());
        }
        if (type == AnimationTimer.class) {
            return Optional.of(new AnimationTimerCodec());
        }
        if (isSupportedPlainStateHolder(type)) {
            return Optional.of(new PlainStateHolderCodec(type));
        }
        if (type.isRecord() && supportedRecord(type)) {
            return Optional.of(new RecordCodec(type));
        }
        return Optional.empty();
    }

    public static boolean collectionCodecUsesIdentityReferences(Field field) {
        Objects.requireNonNull(field, "field");
        Class<?> type = field.getType();
        if (!Collection.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type)) {
            return false;
        }
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return false;
        }
        Type[] args = parameterizedType.getActualTypeArguments();
        if (Collection.class.isAssignableFrom(type)) {
            return args.length == 1
                    && args[0] instanceof Class<?> elementType
                    && isIdentityReferenceType(elementType);
        }
        return args.length == 2
                && args[0] instanceof Class<?> keyType
                && args[1] instanceof Class<?> valueType
                && (isIdentityReferenceType(keyType) || isIdentityReferenceType(valueType));
    }

    public static boolean supports(Class<?> type) {
        return codecFor(type).isPresent();
    }

    public static boolean requiresIdentityTable(Field field) {
        Objects.requireNonNull(field, "field");
        return codecFor(field).map(RewindCodec::requiresIdentityTable).orElse(false);
    }

    public static boolean supportsInPlaceStateHolder(Class<?> type) {
        return isSupportedPlainStateHolder(type);
    }

    /**
     * Returns whether an array of {@code componentType} can be restored into a
     * freshly-allocated array (non-final field, no existing elements to reuse):
     * every element must be materializable without an existing target value.
     * Plain state holders qualify only when they expose a no-arg constructor
     * ({@code newPlainStateHolder} would otherwise throw on restore).
     */
    static boolean supportsFreshArrayElementAllocation(Class<?> componentType) {
        return componentType.isPrimitive()
                || componentType.isEnum()
                || isPlayerReferenceType(componentType)
                || isObjectReferenceType(componentType)
                || componentType == Palette.Color.class
                || isSupportedConstructiblePlainStateHolder(componentType);
    }

    /**
     * Returns whether a <em>non-final</em> field whose codec reports
     * {@code requiresExistingTargetValue()} can nonetheless be restored without an
     * existing target value, because the codec allocates a fresh value on restore
     * rather than mutating one in place. Two shapes qualify:
     *
     * <ul>
     *   <li>codec-backed arrays — {@code ArrayCodec} allocates a fresh array (and
     *       freshly-allocatable elements) for non-final fields;</li>
     *   <li>{@link ObjectAnimationState} — {@code ObjectAnimationStateCodec} rebuilds a
     *       fresh instance from the captured {@code animationSet} reference (carried as an
     *       in-memory opaque value) plus the int payload, so the reconstructed target need
     *       not already hold a non-null animation state.</li>
     * </ul>
     *
     * <p>Without this, a single non-final field of one of these types silently knocks the
     * whole class off the compact schema path, dropping every policy-CAPTURED collection /
     * reference field with it (see the MGZ top platform grab-state and cork-floor / spring
     * launch-player rewind bugs).
     */
    static boolean supportsFreshTargetAllocation(Field field) {
        Class<?> type = field.getType();
        if (type.isArray()) {
            return supportsFreshArrayElementAllocation(type.getComponentType());
        }
        return type == ObjectAnimationState.class;
    }

    private static boolean supportedArrayComponent(Class<?> componentType) {
        return componentType.isPrimitive()
                || componentType.isEnum()
                || isPlayerReferenceType(componentType)
                || isObjectReferenceType(componentType)
                || componentType == Palette.Color.class
                || isSupportedPlainStateHolder(componentType);
    }

    private static boolean supportedRecord(Class<?> type) {
        for (RecordComponent component : type.getRecordComponents()) {
            if (!isOpaqueRecordComponent(component.getType())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOpaqueRecordComponent(Class<?> type) {
        return type.isPrimitive()
                || WRAPPER_TYPES.contains(type)
                || type == String.class
                || type.isEnum()
                || (type.isRecord() && supportedRecord(type));
    }

    private static boolean isSupportedPlainStateHolder(Class<?> type) {
        if (type.isPrimitive()
                || type.isArray()
                || type.isEnum()
                || type.isRecord()
                || type.isInterface()
                || Modifier.isAbstract(type.getModifiers())
                || type.getSuperclass() != Object.class
                || type.getName().startsWith("java.")) {
            return false;
        }
        if (!isPlainStateHolderName(type)) {
            return false;
        }
        List<Field> fields = plainStateFields(type);
        if (fields.isEmpty()) {
            return false;
        }
        for (Field field : fields) {
            RewindCodec codec = codecFor(field).orElse(null);
            if (codec == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPlainStateHolderName(Class<?> type) {
        String simpleName = type.getSimpleName();
        return simpleName.endsWith("State")
                || simpleName.endsWith("CharState")
                || simpleName.endsWith("Context")
                || simpleName.endsWith("Motion")
                || simpleName.endsWith("Sequencer")
                || simpleName.endsWith("Flasher")
                || simpleName.endsWith("Element")
                || "Segment".equals(simpleName)
                || "RiderSlot".equals(simpleName);
    }

    private static List<Field> plainStateFields(Class<?> type) {
        return PLAIN_STATE_FIELDS_CACHE.computeIfAbsent(type, RewindCodecs::buildPlainStateFields);
    }

    private static List<Field> buildPlainStateFields(Class<?> type) {
        List<Field> fields = new ArrayList<>(List.of(type.getDeclaredFields()));
        fields.removeIf(field -> {
            int mods = field.getModifiers();
            return Modifier.isStatic(mods) || Modifier.isTransient(mods) || field.isSynthetic();
        });
        fields.sort((left, right) -> {
            int byName = left.getName().compareTo(right.getName());
            return byName != 0 ? byName : left.getType().getName().compareTo(right.getType().getName());
        });
        for (Field field : fields) {
            field.setAccessible(true);
        }
        return List.copyOf(fields);
    }

    private static Optional<RewindCodec> collectionCodecFor(Field field) {
        Class<?> type = field.getType();
        if (!Collection.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type)) {
            return Optional.empty();
        }
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return Optional.empty();
        }
        Type[] args = parameterizedType.getActualTypeArguments();
        if (Collection.class.isAssignableFrom(type)) {
            if (!List.class.isAssignableFrom(type) && !Set.class.isAssignableFrom(type)) {
                return Optional.empty();
            }
            if (args.length != 1
                    || !(args[0] instanceof Class<?> elementType)
                    || !isSupportedCollectionElementType(elementType)) {
                return Optional.empty();
            }
            return Optional.of(new CollectionCodec(type, elementType));
        }
        if (Map.class.isAssignableFrom(type)) {
            if (args.length != 2
                    || !(args[0] instanceof Class<?> keyType)
                    || !(args[1] instanceof Class<?> valueType)
                    || !isSupportedCollectionElementType(keyType)
                    || !isSupportedCollectionElementType(valueType)) {
                return Optional.empty();
            }
            return Optional.of(new MapCodec(type, keyType, valueType));
        }
        return Optional.empty();
    }

    private static boolean isCollectionValueType(Class<?> type) {
        return WRAPPER_TYPES.contains(type)
                || type == String.class
                || type.isEnum()
                || type.isArray() && supportedArrayComponent(type.getComponentType())
                || type == Palette.Color.class
                || RewindStateful.class.isAssignableFrom(type)
                || isSupportedConstructiblePlainStateHolder(type);
    }

    private static boolean isSupportedCollectionElementType(Class<?> type) {
        return isCollectionValueType(type) || isPlayerReferenceType(type) || isObjectReferenceType(type);
    }

    private static boolean isIdentityReferenceType(Class<?> type) {
        return isPlayerReferenceType(type) || isObjectReferenceType(type);
    }

    private static boolean collectionValueRequiresIdentityTable(Class<?> type) {
        if (isIdentityReferenceType(type)) {
            return true;
        }
        if (RewindStateful.class.isAssignableFrom(type)) {
            return false;
        }
        if ((type.isArray() && supportedArrayComponent(type.getComponentType()))
                || isSupportedConstructiblePlainStateHolder(type)) {
            return codecFor(type).map(RewindCodec::requiresIdentityTable).orElse(false);
        }
        return false;
    }

    private static boolean isSupportedConstructiblePlainStateHolder(Class<?> type) {
        if (!isSupportedPlainStateHolder(type)
                || type.isMemberClass() && !Modifier.isStatic(type.getModifiers())) {
            return false;
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean isPlayerReferenceType(Class<?> type) {
        return type == PlayableEntity.class || AbstractPlayableSprite.class.isAssignableFrom(type);
    }

    private static boolean isObjectReferenceType(Class<?> type) {
        return ObjectInstance.class.isAssignableFrom(type);
    }

    private static Object get(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read rewind field " + field, e);
        }
    }

    private static void set(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write rewind field " + field, e);
        }
    }

    private static int getInt(Field field, Object target) {
        try {
            return field.getInt(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read rewind field " + field, e);
        }
    }

    private static void setInt(Field field, Object target, int value) {
        try {
            field.setInt(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write rewind field " + field, e);
        }
    }

    private static Object requireExistingValue(Field field, Object target) {
        Object value = get(field, target);
        if (value == null) {
            throw new IllegalStateException("Cannot restore in-place rewind field " + field
                    + " because the target value is null.");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> mutableCollectionFor(Class<?> declaredType) {
        if (Set.class.isAssignableFrom(declaredType)) {
            return new LinkedHashSet<>();
        }
        return new ArrayList<>();
    }

    private static boolean isFinal(Field field) {
        return Modifier.isFinal(field.getModifiers());
    }

    private static final class ScalarCodec implements RewindCodec {
        private final boolean primitive;
        private final RewindScalarTag tag;

        private ScalarCodec(Class<?> type) {
            this.primitive = type.isPrimitive();
            this.tag = RewindScalarTag.forType(type);
            if (tag == null) {
                throw new IllegalArgumentException("Unsupported scalar rewind type: " + type.getName());
            }
        }

        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            if (primitive) {
                capturePrimitive(field, target, scalarData);
                return;
            }
            Object value = get(field, target);
            scalarData.writeBoolean(value != null);
            if (value != null) {
                writeScalar(tag, value, scalarData);
            }
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            if (primitive) {
                restorePrimitive(field, target, scalarData);
                return;
            }
            set(field, target, scalarData.readBoolean() ? readScalar(tag, scalarData) : null);
        }

        private void capturePrimitive(Field field, Object target, RewindStateBuffer scalarData) {
            try {
                switch (tag) {
                    case BOOLEAN -> scalarData.writeBoolean(field.getBoolean(target));
                    case BYTE -> scalarData.writeByte(field.getByte(target));
                    case CHAR -> scalarData.writeShort(field.getChar(target));
                    case SHORT -> scalarData.writeShort(field.getShort(target));
                    case INT -> scalarData.writeInt(field.getInt(target));
                    case LONG -> scalarData.writeLong(field.getLong(target));
                    case FLOAT -> scalarData.writeFloat(field.getFloat(target));
                    case DOUBLE -> scalarData.writeDouble(field.getDouble(target));
                    // A silently unwritten tag would misalign the buffer.
                    default -> throw new IllegalStateException(
                            "Unhandled scalar tag " + tag + " for " + field);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read rewind field " + field, e);
            }
        }

        private void restorePrimitive(Field field, Object target, RewindStateBuffer.Reader scalarData) {
            try {
                switch (tag) {
                    case BOOLEAN -> field.setBoolean(target, scalarData.readBoolean());
                    case BYTE -> field.setByte(target, scalarData.readByte());
                    case CHAR -> field.setChar(target, (char) (scalarData.readShort() & 0xFFFF));
                    case SHORT -> field.setShort(target, scalarData.readShort());
                    case INT -> field.setInt(target, scalarData.readInt());
                    case LONG -> field.setLong(target, scalarData.readLong());
                    case FLOAT -> field.setFloat(target, scalarData.readFloat());
                    case DOUBLE -> field.setDouble(target, scalarData.readDouble());
                    // A silently unread tag would misalign the buffer.
                    default -> throw new IllegalStateException(
                            "Unhandled scalar tag " + tag + " for " + field);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot write rewind field " + field, e);
            }
        }
    }

    /**
     * True for the plain scalar codec whose primitive wire format matches
     * {@link RewindFieldPlan}'s typed fast path (no null marker byte).
     */
    static boolean isPlainScalarCodec(RewindCodec codec) {
        return codec instanceof ScalarCodec;
    }

    private static final class EnumCodec implements RewindCodec {
        private final Object[] constants;

        private EnumCodec(Class<?> type) {
            this.constants = type.getEnumConstants();
        }

        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            Enum<?> value = (Enum<?>) get(field, target);
            scalarData.writeInt(value == null ? -1 : value.ordinal());
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            int ordinal = scalarData.readInt();
            set(field, target, ordinal < 0 ? null : constants[ordinal]);
        }
    }

    private static final class ArrayCodec implements RewindCodec {
        private final Class<?> componentType;

        private ArrayCodec(Class<?> componentType) {
            this.componentType = componentType;
        }

        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            capture(field, target, scalarData, opaqueValues, RewindCaptureContext.none());
        }

        @Override
        public void capture(
                Field field,
                Object target,
                RewindStateBuffer scalarData,
                List<Object> opaqueValues,
                RewindCaptureContext context) {

            Object array = get(field, target);
            scalarData.writeInt(array == null ? -1 : Array.getLength(array));
            if (array == null) {
                return;
            }
            int length = Array.getLength(array);
            for (int i = 0; i < length; i++) {
                writeArrayValue(componentType, Array.get(array, i), scalarData, opaqueValues, context);
            }
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            restore(field, target, scalarData, opaqueValues, opaqueIndex, RewindCaptureContext.none());
        }

        @Override
        public void restore(
                Field field,
                Object target,
                RewindStateBuffer.Reader scalarData,
                Object[] opaqueValues,
                OpaqueIndex opaqueIndex,
                RewindCaptureContext context) {

            int length = scalarData.readInt();
            if (length < 0) {
                set(field, target, null);
                return;
            }
            Object array;
            if (isFinal(field)) {
                array = requireExistingValue(field, target);
                if (Array.getLength(array) != length) {
                    throw new IllegalStateException("Cannot restore final rewind array field " + field
                            + " with changed length " + Array.getLength(array) + " -> " + length);
                }
            } else {
                array = Array.newInstance(componentType, length);
                set(field, target, array);
            }
            for (int i = 0; i < length; i++) {
                Object existing = Array.get(array, i);
                Object value = readArrayValue(componentType, existing, scalarData, opaqueValues, opaqueIndex, context);
                Array.set(array, i, value);
            }
        }

        @Override
        public boolean capturesFinalFields() {
            return true;
        }

        @Override
        public boolean requiresExistingTargetValue() {
            return true;
        }

        @Override
        public boolean requiresIdentityTable() {
            return codecFor(componentType).map(RewindCodec::requiresIdentityTable).orElse(false);
        }
    }

    private static final class PaletteColorCodec implements RewindCodec {
        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            Palette.Color color = (Palette.Color) get(field, target);
            scalarData.writeBoolean(color != null);
            if (color != null) {
                writePaletteColor(color, scalarData);
            }
        }

        @Override
        public void restore(
                Field field,
                Object target,
                RewindStateBuffer.Reader scalarData,
                Object[] opaqueValues,
                OpaqueIndex opaqueIndex) {

            set(field, target, scalarData.readBoolean()
                    ? readPaletteColor((Palette.Color) get(field, target), scalarData)
                    : null);
        }

        @Override
        public boolean capturesFinalFields() {
            return true;
        }
    }

    private static final class BitSetCodec implements RewindCodec {
        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            BitSet value = (BitSet) get(field, target);
            if (value == null) {
                scalarData.writeInt(-1);
                return;
            }
            byte[] bytes = value.toByteArray();
            scalarData.writeInt(bytes.length);
            scalarData.writeBytes(bytes);
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            int length = scalarData.readInt();
            set(field, target, length < 0 ? null : BitSet.valueOf(scalarData.readBytes(length)));
        }
    }

    private static final class PlayerReferenceCodec implements RewindCodec {
        private final Class<?> declaredType;

        private PlayerReferenceCodec(Class<?> declaredType) {
            this.declaredType = declaredType;
        }

        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            capture(field, target, scalarData, opaqueValues, RewindCaptureContext.none());
        }

        @Override
        public void capture(
                Field field,
                Object target,
                RewindStateBuffer scalarData,
                List<Object> opaqueValues,
                RewindCaptureContext context) {

            PlayerRefId id = encodePlayerRef((PlayableEntity) get(field, target), context);
            scalarData.writeInt(id.encoded());
        }

        @Override
        public void restore(
                Field field,
                Object target,
                RewindStateBuffer.Reader scalarData,
                Object[] opaqueValues,
                OpaqueIndex opaqueIndex) {

            restore(field, target, scalarData, opaqueValues, opaqueIndex, RewindCaptureContext.none());
        }

        @Override
        public void restore(
                Field field,
                Object target,
                RewindStateBuffer.Reader scalarData,
                Object[] opaqueValues,
                OpaqueIndex opaqueIndex,
                RewindCaptureContext context) {

            set(field, target, resolvePlayerRef(new PlayerRefId(scalarData.readInt()), context, declaredType));
        }

        @Override
        public boolean requiresIdentityTable() {
            return true;
        }
    }

    private static final class ObjectReferenceCodec implements RewindCodec {
        private final Class<?> declaredType;

        private ObjectReferenceCodec(Class<?> declaredType) {
            this.declaredType = declaredType;
        }

        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            capture(field, target, scalarData, opaqueValues, RewindCaptureContext.none());
        }

        @Override
        public void capture(
                Field field,
                Object target,
                RewindStateBuffer scalarData,
                List<Object> opaqueValues,
                RewindCaptureContext context) {

            writeObjectRef((ObjectInstance) get(field, target), scalarData, context);
        }

        @Override
        public void restore(
                Field field,
                Object target,
                RewindStateBuffer.Reader scalarData,
                Object[] opaqueValues,
                OpaqueIndex opaqueIndex) {

            restore(field, target, scalarData, opaqueValues, opaqueIndex, RewindCaptureContext.none());
        }

        @Override
        public void restore(
                Field field,
                Object target,
                RewindStateBuffer.Reader scalarData,
                Object[] opaqueValues,
                OpaqueIndex opaqueIndex,
                RewindCaptureContext context) {

            set(field, target, readObjectRef(scalarData, context, declaredType));
        }

        @Override
        public boolean requiresIdentityTable() {
            return true;
        }
    }

    /** Captures a final, pre-constructed rewind-stateful helper into the compact schema. */
    private static final class StatefulValueCodec implements RewindCodec {
        @Override
        public void capture(
                Field field,
                Object target,
                RewindStateBuffer scalarData,
                List<Object> opaqueValues) {

            RewindStateful<?> value = (RewindStateful<?>) get(field, target);
            scalarData.writeBoolean(value != null);
            if (value != null) {
                writeStatefulCollectionValue(value, opaqueValues);
            }
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public void restore(
                Field field,
                Object target,
                RewindStateBuffer.Reader scalarData,
                Object[] opaqueValues,
                OpaqueIndex opaqueIndex) {

            if (!scalarData.readBoolean()) {
                if (!Modifier.isFinal(field.getModifiers())) {
                    set(field, target, null);
                }
                return;
            }
            RewindStateful value = (RewindStateful) get(field, target);
            if (value == null) {
                throw new IllegalStateException("Rewind-stateful field requires an existing value: "
                        + field.getDeclaringClass().getName() + "." + field.getName());
            }
            StatefulCollectionValueSnapshot snapshot =
                    (StatefulCollectionValueSnapshot) opaqueIndex.next(opaqueValues);
            Object state = snapshot.state() == null
                    ? null
                    : GenericFieldCapturer.deepCopySnapshotValue(snapshot.state());
            value.restoreRewindStateValue(state);
        }

        @Override
        public boolean capturesFinalFields() {
            return true;
        }

        @Override
        public boolean requiresExistingTargetValue() {
            return true;
        }
    }

    private static final class CollectionCodec implements RewindCodec {
        private final Class<?> declaredType;
        private final Class<?> elementType;

        private CollectionCodec(Class<?> declaredType, Class<?> elementType) {
            this.declaredType = declaredType;
            this.elementType = elementType;
        }

        @Override
        public void capture(
                Field field,
                Object target,
                RewindStateBuffer scalarData,
                List<Object> opaqueValues,
                RewindCaptureContext context) {

            Collection<?> collection = (Collection<?>) get(field, target);
            scalarData.writeInt(collection == null ? -1 : collection.size());
            if (collection == null) {
                return;
            }
            for (Object element : collection) {
                writeCollectionValue(elementType, element, scalarData, opaqueValues, context);
            }
        }

        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            capture(field, target, scalarData, opaqueValues, RewindCaptureContext.none());
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(
                Field field,
                Object target,
                RewindStateBuffer.Reader scalarData,
                Object[] opaqueValues,
                OpaqueIndex opaqueIndex,
                RewindCaptureContext context) {

            int size = scalarData.readInt();
            if (size < 0) {
                set(field, target, null);
                return;
            }
            Collection<Object> restored;
            if (isFinal(field)) {
                restored = (Collection<Object>) requireExistingValue(field, target);
                restored.clear();
            } else {
                restored = mutableCollectionFor(declaredType);
                set(field, target, restored);
            }
            for (int i = 0; i < size; i++) {
                restored.add(readCollectionValue(elementType, scalarData, opaqueValues, opaqueIndex, context));
            }
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            restore(field, target, scalarData, opaqueValues, opaqueIndex, RewindCaptureContext.none());
        }

        @Override
        public boolean capturesFinalFields() {
            return true;
        }

        @Override
        public boolean requiresExistingTargetValue() {
            return true;
        }

        @Override
        public boolean requiresIdentityTable() {
            return collectionValueRequiresIdentityTable(elementType);
        }
    }

    private static final class MapCodec implements RewindCodec {
        private final Class<?> declaredType;
        private final Class<?> keyType;
        private final Class<?> valueType;

        private MapCodec(Class<?> declaredType, Class<?> keyType, Class<?> valueType) {
            this.declaredType = declaredType;
            this.keyType = keyType;
            this.valueType = valueType;
        }

        @Override
        public void capture(
                Field field,
                Object target,
                RewindStateBuffer scalarData,
                List<Object> opaqueValues,
                RewindCaptureContext context) {

            Map<?, ?> map = (Map<?, ?>) get(field, target);
            scalarData.writeInt(map == null ? -1 : map.size());
            if (map == null) {
                return;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                writeCollectionValue(keyType, entry.getKey(), scalarData, opaqueValues, context);
                writeCollectionValue(valueType, entry.getValue(), scalarData, opaqueValues, context);
            }
        }

        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            capture(field, target, scalarData, opaqueValues, RewindCaptureContext.none());
        }

        @Override
        @SuppressWarnings("unchecked")
        public void restore(
                Field field,
                Object target,
                RewindStateBuffer.Reader scalarData,
                Object[] opaqueValues,
                OpaqueIndex opaqueIndex,
                RewindCaptureContext context) {

            int size = scalarData.readInt();
            if (size < 0) {
                set(field, target, null);
                return;
            }
            Map<Object, Object> restored;
            if (isFinal(field)) {
                restored = (Map<Object, Object>) requireExistingValue(field, target);
                restored.clear();
            } else {
                restored = mutableMapFor(declaredType);
                set(field, target, restored);
            }
            for (int i = 0; i < size; i++) {
                Object key = readCollectionValue(keyType, scalarData, opaqueValues, opaqueIndex, context);
                Object value = readCollectionValue(valueType, scalarData, opaqueValues, opaqueIndex, context);
                restored.put(key, value);
            }
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            restore(field, target, scalarData, opaqueValues, opaqueIndex, RewindCaptureContext.none());
        }

        @Override
        public boolean capturesFinalFields() {
            return true;
        }

        @Override
        public boolean requiresExistingTargetValue() {
            return true;
        }

        @Override
        public boolean requiresIdentityTable() {
            return collectionValueRequiresIdentityTable(keyType)
                    || collectionValueRequiresIdentityTable(valueType);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> mutableMapFor(Class<?> declaredType) {
        if (IdentityHashMap.class.isAssignableFrom(declaredType)) {
            return new IdentityHashMap<>();
        }
        if (SortedMap.class.isAssignableFrom(declaredType)) {
            return new TreeMap<>();
        }
        if (declaredType.isAssignableFrom(LinkedHashMap.class)) {
            return new LinkedHashMap<>();
        }
        try {
            Object value = declaredType.getDeclaredConstructor().newInstance();
            if (value instanceof Map<?, ?> map) {
                return (Map<Object, Object>) map;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall back to insertion-ordered map when the field type can accept it.
        }
        throw new IllegalStateException("Cannot create rewind map for declared type " + declaredType.getName());
    }

    private static final class SubpixelMotionStateCodec implements RewindCodec {
        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            SubpixelMotion.State state = (SubpixelMotion.State) get(field, target);
            scalarData.writeBoolean(state != null);
            if (state == null) {
                return;
            }
            scalarData.writeInt(state.x);
            scalarData.writeInt(state.y);
            scalarData.writeInt(state.xSub);
            scalarData.writeInt(state.ySub);
            scalarData.writeInt(state.xVel);
            scalarData.writeInt(state.yVel);
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            if (!scalarData.readBoolean()) {
                set(field, target, null);
                return;
            }
            SubpixelMotion.State state = (SubpixelMotion.State) requireExistingValue(field, target);
            state.x = scalarData.readInt();
            state.y = scalarData.readInt();
            state.xSub = scalarData.readInt();
            state.ySub = scalarData.readInt();
            state.xVel = scalarData.readInt();
            state.yVel = scalarData.readInt();
        }

        @Override
        public boolean capturesFinalFields() {
            return true;
        }

        @Override
        public boolean requiresExistingTargetValue() {
            return true;
        }
    }

    private static final class ObjectAnimationStateCodec implements RewindCodec {
        private static final Field ANIMATION_SET = declaredField(ObjectAnimationState.class, "animationSet");
        private static final Field ANIM_ID = intField(ObjectAnimationState.class, "animId");
        private static final Field LAST_ANIM_ID = intField(ObjectAnimationState.class, "lastAnimId");
        private static final Field FRAME_INDEX = intField(ObjectAnimationState.class, "frameIndex");
        private static final Field FRAME_TICK = intField(ObjectAnimationState.class, "frameTick");
        private static final Field MAPPING_FRAME = intField(ObjectAnimationState.class, "mappingFrame");
        private static final Field PENDING_SWITCH_ANIM_ID = intField(ObjectAnimationState.class, "pendingSwitchAnimId");

        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            ObjectAnimationState state = (ObjectAnimationState) get(field, target);
            scalarData.writeBoolean(state != null);
            if (state == null) {
                return;
            }
            if (!isFinal(field)) {
                // Non-final fields are rebuilt fresh on restore (the reconstructed target may
                // be a different instance, or null). Carry the animationSet reference — the one
                // piece of state the int payload cannot express — as an in-memory opaque value,
                // exactly as GenericFieldCapturer's copyForRewind snapshot preserves it.
                opaqueValues.add(get(ANIMATION_SET, state));
            }
            scalarData.writeInt(getInt(ANIM_ID, state));
            scalarData.writeInt(getInt(LAST_ANIM_ID, state));
            scalarData.writeInt(getInt(FRAME_INDEX, state));
            scalarData.writeInt(getInt(FRAME_TICK, state));
            scalarData.writeInt(getInt(MAPPING_FRAME, state));
            scalarData.writeInt(getInt(PENDING_SWITCH_ANIM_ID, state));
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            if (!scalarData.readBoolean()) {
                set(field, target, null);
                return;
            }
            if (isFinal(field)) {
                // Final fields are restored in place into the reconstructed instance,
                // preserving the same animationSet reference the constructor installed.
                ObjectAnimationState state = (ObjectAnimationState) requireExistingValue(field, target);
                setInt(ANIM_ID, state, scalarData.readInt());
                setInt(LAST_ANIM_ID, state, scalarData.readInt());
                setInt(FRAME_INDEX, state, scalarData.readInt());
                setInt(FRAME_TICK, state, scalarData.readInt());
                setInt(MAPPING_FRAME, state, scalarData.readInt());
                setInt(PENDING_SWITCH_ANIM_ID, state, scalarData.readInt());
                return;
            }
            SpriteAnimationSet animationSet = (SpriteAnimationSet) opaqueIndex.next(opaqueValues);
            int animId = scalarData.readInt();
            int lastAnimId = scalarData.readInt();
            int frameIndex = scalarData.readInt();
            int frameTick = scalarData.readInt();
            int mappingFrame = scalarData.readInt();
            int pendingSwitchAnimId = scalarData.readInt();
            ObjectAnimationState state = new ObjectAnimationState(animationSet, animId, mappingFrame);
            setInt(LAST_ANIM_ID, state, lastAnimId);
            setInt(FRAME_INDEX, state, frameIndex);
            setInt(FRAME_TICK, state, frameTick);
            setInt(PENDING_SWITCH_ANIM_ID, state, pendingSwitchAnimId);
            set(field, target, state);
        }

        @Override
        public boolean capturesFinalFields() {
            return true;
        }

        @Override
        public boolean requiresExistingTargetValue() {
            return true;
        }
    }

    private static final class PlatformBobHelperCodec implements RewindCodec {
        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            PlatformBobHelper helper = (PlatformBobHelper) get(field, target);
            scalarData.writeBoolean(helper != null);
            if (helper != null) {
                scalarData.writeInt(helper.getAngle());
            }
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            if (!scalarData.readBoolean()) {
                set(field, target, null);
                return;
            }
            PlatformBobHelper helper = (PlatformBobHelper) requireExistingValue(field, target);
            helper.restoreAngle(scalarData.readInt());
        }

        @Override
        public boolean capturesFinalFields() {
            return true;
        }
    }

    private static final class AnimationTimerCodec implements RewindCodec {
        private static final Field TIMER = intField(AnimationTimer.class, "timer");
        private static final Field FRAME = intField(AnimationTimer.class, "frame");

        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            AnimationTimer timer = (AnimationTimer) get(field, target);
            scalarData.writeBoolean(timer != null);
            if (timer == null) {
                return;
            }
            scalarData.writeInt(getInt(TIMER, timer));
            scalarData.writeInt(getInt(FRAME, timer));
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            if (!scalarData.readBoolean()) {
                set(field, target, null);
                return;
            }
            AnimationTimer timer = (AnimationTimer) requireExistingValue(field, target);
            setInt(TIMER, timer, scalarData.readInt());
            setInt(FRAME, timer, scalarData.readInt());
        }

        @Override
        public boolean capturesFinalFields() {
            return true;
        }
    }

    private static final class RecordCodec implements RewindCodec {
        private final Class<?> type;
        private final RecordComponent[] components;
        private final Method[] accessors;
        private final Constructor<?> constructor;

        private RecordCodec(Class<?> type) {
            this.type = type;
            this.components = type.getRecordComponents();
            this.accessors = new Method[components.length];
            Class<?>[] componentTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                accessors[i] = components[i].getAccessor();
                accessors[i].setAccessible(true);
                componentTypes[i] = components[i].getType();
            }
            try {
                this.constructor = type.getDeclaredConstructor(componentTypes);
                this.constructor.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Cannot find canonical record constructor for rewind type "
                        + type.getName(), e);
            }
        }

        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            Object record = get(field, target);
            scalarData.writeBoolean(record != null);
            if (record == null) {
                return;
            }
            captureRecordValue(record, scalarData);
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            if (!scalarData.readBoolean()) {
                set(field, target, null);
                return;
            }
            set(field, target, readRecordValue(scalarData));
        }

        private void captureRecordValue(Object record, RewindStateBuffer scalarData) {
            for (int i = 0; i < components.length; i++) {
                writeRecordComponent(components[i].getType(), readComponent(i, record), scalarData);
            }
        }

        private Object readRecordValue(RewindStateBuffer.Reader scalarData) {
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                values[i] = readRecordComponent(components[i].getType(), scalarData);
            }
            try {
                return constructor.newInstance(values);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Cannot restore rewind record value using " + type.getName(), e);
            }
        }

        private Object readComponent(int index, Object record) {
            try {
                return accessors[index].invoke(record);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Cannot read rewind record component "
                        + type.getName() + "." + components[index].getName(), e);
            }
        }
    }

    private static Field intField(Class<?> owner, String name) {
        return declaredField(owner, name);
    }

    private static Field declaredField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final class OpaqueCodec implements RewindCodec {
        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            opaqueValues.add(get(field, target));
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            set(field, target, opaqueIndex.next(opaqueValues));
        }
    }

    private static final class PlainStateHolderCodec implements RewindCodec {
        private final List<Field> fields;

        private PlainStateHolderCodec(Class<?> type) {
            this.fields = plainStateFields(type);
        }

        @Override
        public void capture(
                Field field,
                Object target,
                RewindStateBuffer scalarData,
                List<Object> opaqueValues,
                RewindCaptureContext context) {

            Object state = get(field, target);
            scalarData.writeBoolean(state != null);
            if (state == null) {
                return;
            }
            capturePlainState(fields, state, scalarData, opaqueValues, context);
        }

        @Override
        public void capture(Field field, Object target, RewindStateBuffer scalarData, List<Object> opaqueValues) {
            capture(field, target, scalarData, opaqueValues, RewindCaptureContext.none());
        }

        @Override
        public void restore(
                Field field,
                Object target,
                RewindStateBuffer.Reader scalarData,
                Object[] opaqueValues,
                OpaqueIndex opaqueIndex,
                RewindCaptureContext context) {

            if (!scalarData.readBoolean()) {
                set(field, target, null);
                return;
            }
            Object state = requireExistingValue(field, target);
            restorePlainState(fields, state, scalarData, opaqueValues, opaqueIndex, context);
        }

        @Override
        public void restore(Field field, Object target, RewindStateBuffer.Reader scalarData, Object[] opaqueValues, OpaqueIndex opaqueIndex) {
            restore(field, target, scalarData, opaqueValues, opaqueIndex, RewindCaptureContext.none());
        }

        @Override
        public boolean capturesFinalFields() {
            return true;
        }

        @Override
        public boolean requiresExistingTargetValue() {
            return true;
        }

        @Override
        public boolean requiresIdentityTable() {
            return fields.stream()
                    .map(RewindCodecs::codecFor)
                    .flatMap(Optional::stream)
                    .anyMatch(RewindCodec::requiresIdentityTable);
        }
    }

    private static void capturePlainState(
            List<Field> fields,
            Object state,
            RewindStateBuffer scalarData,
            List<Object> opaqueValues,
            RewindCaptureContext context) {

        for (Field stateField : fields) {
            RewindCodec codec = codecFor(stateField).orElseThrow();
            codec.capture(stateField, state, scalarData, opaqueValues, context);
        }
    }

    private static void restorePlainState(
            List<Field> fields,
            Object state,
            RewindStateBuffer.Reader scalarData,
            Object[] opaqueValues,
            RewindCodec.OpaqueIndex opaqueIndex,
            RewindCaptureContext context) {

        for (Field stateField : fields) {
            RewindCodec codec = codecFor(stateField).orElseThrow();
            codec.restore(stateField, state, scalarData, opaqueValues, opaqueIndex, context);
        }
    }

    private static void writeCollectionValue(
            Class<?> type,
            Object value,
            RewindStateBuffer scalarData,
            List<Object> opaqueValues,
            RewindCaptureContext context) {

        scalarData.writeBoolean(value != null);
        if (value == null) {
            return;
        }
        if (type == String.class) {
            opaqueValues.add(value);
        } else if (type.isEnum()) {
            scalarData.writeInt(((Enum<?>) value).ordinal());
        } else if (isPlayerReferenceType(type)) {
            scalarData.writeInt(encodePlayerRef((PlayableEntity) value, context).encoded());
        } else if (isObjectReferenceType(type)) {
            writeObjectRefBody(encodeObjectRef((ObjectInstance) value, context), scalarData);
        } else if (type.isArray() && supportedArrayComponent(type.getComponentType())) {
            writeArrayBody(type.getComponentType(), value, scalarData, opaqueValues, context);
        } else if (type == Palette.Color.class) {
            writePaletteColor((Palette.Color) value, scalarData);
        } else if (RewindStateful.class.isAssignableFrom(type)) {
            writeStatefulCollectionValue((RewindStateful<?>) value, opaqueValues);
        } else if (isSupportedConstructiblePlainStateHolder(type)) {
            capturePlainState(plainStateFields(type), value, scalarData, opaqueValues, context);
        } else {
            writeScalar(type, value, scalarData);
        }
    }

    private static Object readCollectionValue(
            Class<?> type,
            RewindStateBuffer.Reader scalarData,
            Object[] opaqueValues,
            RewindCodec.OpaqueIndex opaqueIndex,
            RewindCaptureContext context) {

        if (!scalarData.readBoolean()) {
            return null;
        }
        if (type == String.class) {
            return opaqueIndex.next(opaqueValues);
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[scalarData.readInt()];
        }
        if (isPlayerReferenceType(type)) {
            return resolvePlayerRef(new PlayerRefId(scalarData.readInt()), context, type);
        }
        if (isObjectReferenceType(type)) {
            return resolveObjectRef(readObjectRefBody(scalarData), context, type);
        }
        if (type.isArray() && supportedArrayComponent(type.getComponentType())) {
            return readArrayBody(type.getComponentType(), null, scalarData, opaqueValues, opaqueIndex, context);
        }
        if (type == Palette.Color.class) {
            return readPaletteColor(null, scalarData);
        }
        if (RewindStateful.class.isAssignableFrom(type)) {
            return readStatefulCollectionValue(type, opaqueValues, opaqueIndex);
        }
        if (isSupportedConstructiblePlainStateHolder(type)) {
            Object state = newPlainStateHolder(type);
            restorePlainState(plainStateFields(type), state, scalarData, opaqueValues, opaqueIndex, context);
            return state;
        }
        return readScalar(type, scalarData);
    }

    private static void writeArrayValue(
            Class<?> componentType,
            Object value,
            RewindStateBuffer scalarData,
            List<Object> opaqueValues,
            RewindCaptureContext context) {

        if (!componentType.isPrimitive()) {
            scalarData.writeBoolean(value != null);
            if (value == null) {
                return;
            }
        }
        if (componentType.isEnum()) {
            scalarData.writeInt(((Enum<?>) value).ordinal());
        } else if (isPlayerReferenceType(componentType)) {
            scalarData.writeInt(encodePlayerRef((PlayableEntity) value, context).encoded());
        } else if (isObjectReferenceType(componentType)) {
            writeObjectRefBody(encodeObjectRef((ObjectInstance) value, context), scalarData);
        } else if (componentType == Palette.Color.class) {
            writePaletteColor((Palette.Color) value, scalarData);
        } else if (isSupportedPlainStateHolder(componentType)) {
            capturePlainState(plainStateFields(componentType), value, scalarData, opaqueValues, context);
        } else {
            writeScalar(componentType, value, scalarData);
        }
    }

    private static Object readArrayValue(
            Class<?> componentType,
            Object existing,
            RewindStateBuffer.Reader scalarData,
            Object[] opaqueValues,
            RewindCodec.OpaqueIndex opaqueIndex,
            RewindCaptureContext context) {

        if (!componentType.isPrimitive() && !scalarData.readBoolean()) {
            return null;
        }
        if (componentType.isEnum()) {
            return componentType.getEnumConstants()[scalarData.readInt()];
        }
        if (isPlayerReferenceType(componentType)) {
            return resolvePlayerRef(new PlayerRefId(scalarData.readInt()), context, componentType);
        }
        if (isObjectReferenceType(componentType)) {
            return resolveObjectRef(readObjectRefBody(scalarData), context, componentType);
        }
        if (componentType == Palette.Color.class) {
            return readPaletteColor((Palette.Color) existing, scalarData);
        }
        if (isSupportedPlainStateHolder(componentType)) {
            Object state = existing != null ? existing : newPlainStateHolder(componentType);
            restorePlainState(
                    plainStateFields(componentType),
                    state,
                    scalarData,
                    opaqueValues,
                    opaqueIndex,
                    context);
            return state;
        }
        return readScalar(componentType, scalarData);
    }

    private static void writeArrayBody(
            Class<?> componentType,
            Object array,
            RewindStateBuffer scalarData,
            List<Object> opaqueValues,
            RewindCaptureContext context) {

        scalarData.writeInt(array == null ? -1 : Array.getLength(array));
        if (array == null) {
            return;
        }
        for (int i = 0; i < Array.getLength(array); i++) {
            writeArrayValue(componentType, Array.get(array, i), scalarData, opaqueValues, context);
        }
    }

    private static Object readArrayBody(
            Class<?> componentType,
            Object existingArray,
            RewindStateBuffer.Reader scalarData,
            Object[] opaqueValues,
            RewindCodec.OpaqueIndex opaqueIndex,
            RewindCaptureContext context) {

        int length = scalarData.readInt();
        if (length < 0) {
            return null;
        }
        Object array = existingArray != null && Array.getLength(existingArray) == length
                ? existingArray
                : Array.newInstance(componentType, length);
        for (int i = 0; i < length; i++) {
            Array.set(array, i, readArrayValue(
                    componentType,
                    Array.get(array, i),
                    scalarData,
                    opaqueValues,
                    opaqueIndex,
                    context));
        }
        return array;
    }

    private static void writePaletteColor(Palette.Color color, RewindStateBuffer scalarData) {
        scalarData.writeByte(color.r);
        scalarData.writeByte(color.g);
        scalarData.writeByte(color.b);
    }

    private static Palette.Color readPaletteColor(Palette.Color existing, RewindStateBuffer.Reader scalarData) {
        Palette.Color color = existing != null ? existing : new Palette.Color();
        color.r = scalarData.readByte();
        color.g = scalarData.readByte();
        color.b = scalarData.readByte();
        return color;
    }

    private static Object newPlainStateHolder(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot construct rewind state holder " + type.getName(), e);
        }
    }

    private static void writeStatefulCollectionValue(RewindStateful<?> value, List<Object> opaqueValues) {
        Object state = value.captureRewindStateValue();
        opaqueValues.add(new StatefulCollectionValueSnapshot(
                state == null ? null : state.getClass(),
                GenericFieldCapturer.deepCopySnapshotValue(state)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object readStatefulCollectionValue(
            Class<?> type,
            Object[] opaqueValues,
            RewindCodec.OpaqueIndex opaqueIndex) {

        StatefulCollectionValueSnapshot snapshot =
                (StatefulCollectionValueSnapshot) opaqueIndex.next(opaqueValues);
        Object holder = newStatefulCollectionValue(type);
        Object state = snapshot.state() == null
                ? null
                : GenericFieldCapturer.deepCopySnapshotValue(snapshot.state());
        ((RewindStateful) holder).restoreRewindStateValue(state);
        return holder;
    }

    private static Object newStatefulCollectionValue(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot construct rewind stateful value " + type.getName(), e);
        }
    }

    private record StatefulCollectionValueSnapshot(Class<?> stateType, Object state) {
        private StatefulCollectionValueSnapshot {
            state = GenericFieldCapturer.deepCopySnapshotValue(state);
        }
    }

    private static void writeRecordComponent(Class<?> type, Object value, RewindStateBuffer scalarData) {
        if (!type.isPrimitive()) {
            scalarData.writeBoolean(value != null);
            if (value == null) {
                return;
            }
        }
        if (type == String.class) {
            writeString(value, scalarData);
        } else if (type.isEnum()) {
            scalarData.writeInt(((Enum<?>) value).ordinal());
        } else if (type.isRecord()) {
            nestedRecordCodec(type).captureRecordValue(value, scalarData);
        } else {
            writeScalar(type, value, scalarData);
        }
    }

    private static RecordCodec nestedRecordCodec(Class<?> type) {
        return NESTED_RECORD_CODEC_CACHE.computeIfAbsent(type, RecordCodec::new);
    }

    private static Object readRecordComponent(Class<?> type, RewindStateBuffer.Reader scalarData) {
        if (!type.isPrimitive() && !scalarData.readBoolean()) {
            return null;
        }
        if (type == String.class) {
            return readString(scalarData);
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[scalarData.readInt()];
        }
        if (type.isRecord()) {
            return nestedRecordCodec(type).readRecordValue(scalarData);
        }
        return readScalar(type, scalarData);
    }

    private static void writeString(Object value, RewindStateBuffer scalarData) {
        byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
        scalarData.writeInt(bytes.length);
        scalarData.writeBytes(bytes);
    }

    private static String readString(RewindStateBuffer.Reader scalarData) {
        int length = scalarData.readInt();
        return new String(scalarData.readBytes(length), StandardCharsets.UTF_8);
    }

    private static PlayerRefId encodePlayerRef(PlayableEntity player, RewindCaptureContext context) {
        if (player == null) {
            return PlayerRefId.nullRef();
        }
        RewindIdentityTable identityTable = context.requireIdentityTable();
        PlayerRefId id = identityTable.encodePlayer(player);
        if (id == null) {
            // The rewind identity space is team-slot based (main player + the
            // current sidekicks). A reference to a player that is NOT in the active
            // team is a dangling reference to a sprite that has left the team —
            // e.g. a throwaway carry-in / rescue Tails (transientCarrySidekick) that
            // flew off-screen and was removed while an object still held a touch /
            // solid-contact reference to it. Such a player cannot be represented in
            // a keyframe (no slot) and could not be resolved on restore anyway, so
            // encode it as a null reference rather than failing the capture. The
            // matching read path (readPlayerRef) decodes nullRef back to null.
            return PlayerRefId.nullRef();
        }
        return id;
    }

    private static Object resolvePlayerRef(PlayerRefId id, RewindCaptureContext context, Class<?> declaredType) {
        PlayableEntity player = context.requireIdentityTable().resolvePlayer(id, true);
        if (player != null && !declaredType.isInstance(player)) {
            throw new IllegalStateException("Resolved player reference " + id + " to "
                    + player.getClass().getName() + " but field requires " + declaredType.getName() + ".");
        }
        return player;
    }

    private static void writeObjectRef(ObjectInstance object, RewindStateBuffer scalarData, RewindCaptureContext context) {
        scalarData.writeBoolean(object != null);
        if (object != null) {
            writeObjectRefBody(encodeObjectRef(object, context), scalarData);
        }
    }

    private static Object readObjectRef(
            RewindStateBuffer.Reader scalarData,
            RewindCaptureContext context,
            Class<?> declaredType) {

        if (!scalarData.readBoolean()) {
            return null;
        }
        return resolveObjectRef(readObjectRefBody(scalarData), context, declaredType);
    }

    private static ObjectRefId encodeObjectRef(ObjectInstance object, RewindCaptureContext context) {
        RewindIdentityTable identityTable = context.requireIdentityTable();
        ObjectRefId id = identityTable.encodeObject(object);
        if (id == null) {
            throw new IllegalStateException("RewindIdentityTable has no registered id for object reference " + object + ".");
        }
        return id;
    }

    private static void writeObjectRefBody(ObjectRefId id, RewindStateBuffer scalarData) {
        scalarData.writeInt(id.kind().ordinal());
        scalarData.writeInt(id.slotIndex());
        scalarData.writeInt(id.generation());
        scalarData.writeInt(id.spawnId());
        scalarData.writeInt(id.dynamicId());
    }

    private static ObjectRefId readObjectRefBody(RewindStateBuffer.Reader scalarData) {
        ObjectRefKind kind = ObjectRefKind.values()[scalarData.readInt()];
        int slotIndex = scalarData.readInt();
        int generation = scalarData.readInt();
        int spawnId = scalarData.readInt();
        int dynamicId = scalarData.readInt();
        return new ObjectRefId(slotIndex, generation, spawnId, dynamicId, kind);
    }

    private static Object resolveObjectRef(ObjectRefId id, RewindCaptureContext context, Class<?> declaredType) {
        ObjectInstance object = context.requireIdentityTable().resolveObject(id, true);
        if (object != null && !declaredType.isInstance(object)) {
            throw new IllegalStateException("Resolved object reference " + id + " to "
                    + object.getClass().getName() + " but field requires " + declaredType.getName() + ".");
        }
        return object;
    }

    private static void writeScalar(Class<?> type, Object value, RewindStateBuffer scalarData) {
        RewindScalarTag tag = RewindScalarTag.forType(type);
        if (tag == null) {
            throw new IllegalArgumentException("Unsupported scalar rewind type: " + type.getName());
        }
        writeScalar(tag, value, scalarData);
    }

    private static void writeScalar(RewindScalarTag tag, Object value, RewindStateBuffer scalarData) {
        switch (tag) {
            case BOOLEAN -> scalarData.writeBoolean((Boolean) value);
            case BYTE -> scalarData.writeByte((Byte) value);
            case CHAR -> scalarData.writeShort((Character) value);
            case SHORT -> scalarData.writeShort((Short) value);
            case INT -> scalarData.writeInt((Integer) value);
            case LONG -> scalarData.writeLong((Long) value);
            case FLOAT -> scalarData.writeFloat((Float) value);
            case DOUBLE -> scalarData.writeDouble((Double) value);
            // A silently unwritten tag would misalign the buffer.
            default -> throw new IllegalStateException("Unhandled scalar tag " + tag);
        }
    }

    private static Object readScalar(Class<?> type, RewindStateBuffer.Reader scalarData) {
        RewindScalarTag tag = RewindScalarTag.forType(type);
        if (tag == null) {
            throw new IllegalArgumentException("Unsupported scalar rewind type: " + type.getName());
        }
        return readScalar(tag, scalarData);
    }

    private static Object readScalar(RewindScalarTag tag, RewindStateBuffer.Reader scalarData) {
        return switch (tag) {
            case BOOLEAN -> scalarData.readBoolean();
            case BYTE -> scalarData.readByte();
            case CHAR -> (char) (scalarData.readShort() & 0xFFFF);
            case SHORT -> scalarData.readShort();
            case INT -> scalarData.readInt();
            case LONG -> scalarData.readLong();
            case FLOAT -> scalarData.readFloat();
            case DOUBLE -> scalarData.readDouble();
        };
    }

    private RewindCodecs() {}
}
