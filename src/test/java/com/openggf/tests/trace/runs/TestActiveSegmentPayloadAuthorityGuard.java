package com.openggf.tests.trace.runs;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.catalog.TraceCatalog;
import com.openggf.trace.replay.runs.ActiveSegmentPayload;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRows;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Architectural ratchet for the one-active-segment payload lease. */
class TestActiveSegmentPayloadAuthorityGuard {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Path TEST_SOURCES = Path.of("src/test/java");
    private static final String SELF_SOURCE =
            "com/openggf/tests/trace/runs/TestActiveSegmentPayloadAuthorityGuard.java";
    private static final String PAYLOAD = ActiveSegmentPayload.class.getName();
    private static final String WALKER = TraceRunReplayWalker.class.getName();
    private static final Set<String> PAYLOAD_ACCESSORS = Set.of(
            "trace", "specialStageRows");
    private static final Set<String> EXACT_CALLER_ALLOWLIST = Set.of(
            "com.openggf.TraceSessionLauncher",
            "com.openggf.tests.trace.runs.AbstractRunChainTest",
            "com.openggf.tests.trace.runs.VisualRunReplayHarness",
            "com.openggf.trace.replay.runs.TestActiveSegmentPayload",
            "com.openggf.trace.TestTraceReaderLifecycle",
            "com.openggf.TestTraceSessionLauncherActivePayloadLifecycle",
            "com.openggf.tests.trace.runs.TestHeadlessRunActivePayloadLifecycle",
            "com.openggf.tests.trace.runs.TestVisualRunActivePayloadLifecycle",
            "com.openggf.tests.trace.runs.TestTraceRunActivePayloadOwnership",
            "com.openggf.tests.trace.runs.TestTraceRunActivePayloadPerformance",
            "com.openggf.tests.trace.runs.TestActiveSegmentPayloadAuthorityGuard");
    private static final Set<String> CONTROLLED_BYTECODE_MUTATIONS = Set.of(
            UnauthorizedDirectCall.class.getName(),
            UnauthorizedMethodReference.class.getName(),
            UnauthorizedOpenHelper.class.getName(),
            UnauthorizedEagerPlan.class.getName(),
            ErasedMultiHelperRelayMutation.class.getName());
    private static final Set<String> EXACT_DEDICATED_FIELD_INSPECTORS = Set.of(
            "com.openggf.trace.replay.runs.TestActiveSegmentPayload",
            "com.openggf.trace.TestTraceReaderLifecycle",
            "com.openggf.TestTraceSessionLauncherActivePayloadLifecycle",
            "com.openggf.tests.trace.runs.TestHeadlessRunActivePayloadLifecycle",
            "com.openggf.tests.trace.runs.TestVisualRunActivePayloadLifecycle",
            "com.openggf.tests.trace.runs.TestTraceRunActivePayloadOwnership",
            "com.openggf.tests.trace.runs.TestTraceRunActivePayloadPerformance",
            "com.openggf.tests.trace.runs.TestActiveSegmentPayloadAuthorityGuard");
    private static final Set<String> RESTRICTED_FIELD_TARGETS =
            Stream.concat(
                    Stream.of(PAYLOAD, WALKER),
                    EXACT_CALLER_ALLOWLIST.stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> EAGER_PLAN_CALLER_ALLOWLIST = Set.of(
            "com.openggf.tests.trace.runs.TestTraceRunDescriptorPlanningPerformance",
            "com.openggf.tests.trace.runs.TestTraceRunActivePayloadPerformance");
    private static final Set<Class<?>> PAYLOAD_GRAPH_TYPES = Set.of(
            ActiveSegmentPayload.class,
            TraceData.class,
            TraceRunSpecialStageRows.class);

    @Test
    void leaseAndWalkerExposeOnlyTheApprovedPublicSurface() {
        assertTrue(Modifier.isFinal(ActiveSegmentPayload.class.getModifiers()),
                "the active payload lease must remain final");

        Constructor<?>[] constructors = ActiveSegmentPayload.class
                .getDeclaredConstructors();
        assertEquals(1, constructors.length,
                "the lease must keep one construction boundary");
        assertFalse(Modifier.isPublic(constructors[0].getModifiers())
                        || Modifier.isProtected(constructors[0].getModifiers()),
                "the lease constructor must not become public or protected");

        List<String> publicMethods = Arrays.stream(
                        ActiveSegmentPayload.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(TestActiveSegmentPayloadAuthorityGuard::methodSignature)
                .sorted()
                .toList();
        assertEquals(List.of(
                "close():void",
                "descriptor():com.openggf.trace.replay.runs.TraceRunSegmentDescriptor",
                "isClosed():boolean",
                "specialStageRows():com.openggf.trace.replay.runs.TraceRunSpecialStageRows",
                "trace():com.openggf.trace.TraceData"), publicMethods,
                "the lease public API must remain the exact approved surface");

        List<Method> openFacades = Arrays.stream(
                        TraceRunReplayWalker.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getReturnType()
                        == ActiveSegmentPayload.class)
                .toList();
        assertEquals(1, openFacades.size(),
                "the walker must expose exactly one active-payload facade");
        Method facade = openFacades.getFirst();
        assertEquals("openActiveSegment", facade.getName());
        assertTrue(Modifier.isStatic(facade.getModifiers()));
        assertEquals(List.of(
                        TraceRunSegmentDescriptor.class, int.class),
                Arrays.asList(facade.getParameterTypes()));
        assertEquals(List.of(IOException.class),
                Arrays.asList(facade.getExceptionTypes()));
    }

    @Test
    void compiledPayloadAcquisitionHasOnlyTheExactCallerAllowlist() {
        List<String> violations = unauthorizedPayloadCalls(importProjectClasses(),
                CONTROLLED_BYTECODE_MUTATIONS);

        assertEquals(List.of(), violations,
                "active payload access must stay at the exact approved owners");
    }

    @Test
    void bytecodeGuardRejectsUnauthorizedDirectCallsMethodReferencesAndHelpers() {
        JavaClasses mutations = new ClassFileImporter().importClasses(
                UnauthorizedDirectCall.class,
                UnauthorizedMethodReference.class,
                UnauthorizedOpenHelper.class);

        List<String> violations = unauthorizedPayloadCalls(mutations, Set.of());

        assertEquals(3, violations.size());
        assertTrue(violations.stream().anyMatch(line -> line.contains(
                UnauthorizedDirectCall.class.getName())
                && line.contains(".trace(")), violations.toString());
        assertTrue(violations.stream().anyMatch(line -> line.contains(
                UnauthorizedMethodReference.class.getName())
                && line.contains(".trace(")), violations.toString());
        assertTrue(violations.stream().anyMatch(line -> line.contains(
                UnauthorizedOpenHelper.class.getName())
                && line.contains(".openActiveSegment(")), violations.toString());
    }

    @Test
    void transitionalEagerApisAndNonBenchmarkPlanCallersAreAbsent() {
        List<String> planViolations = eagerPlanCalls(
                importProjectClasses(), CONTROLLED_BYTECODE_MUTATIONS);
        assertEquals(List.of(), planViolations,
                "only the exact benchmark classes may call the eager reference planner");

        List<String> catalogNestedTypes = Arrays.stream(
                        TraceCatalog.class.getDeclaredClasses())
                .map(Class::getSimpleName)
                .filter(name -> Set.of(
                        "PreparedRunLaunch", "RunSegmentPlanner", "RunPlannerPair")
                        .contains(name))
                .sorted()
                .toList();
        assertEquals(List.of(), catalogNestedTypes,
                "the transitional eager catalog API must stay removed");
        assertTrue(Arrays.stream(TraceCatalog.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("prepareRunLaunch")),
                "the eager catalog launch method must stay removed");
        assertTrue(Arrays.stream(TraceRunReplayWalker.class.getDeclaredMethods())
                .noneMatch(method -> Set.of(
                        "hasHardwareTimingStream", "hardwareTimingSegments")
                        .contains(method.getName())),
                "the eager timing-summary helpers must stay removed");
        assertTrue(Arrays.stream(
                        TraceRunPlaybackCoordinator.class.getConstructors())
                .noneMatch(constructor -> Arrays.stream(
                        constructor.getGenericParameterTypes())
                        .map(Type::getTypeName)
                        .anyMatch(name -> name.contains("SegmentPlan"))),
                "the eager four-argument coordinator constructor must stay removed");
    }

    @Test
    void eagerPlanGuardRejectsAReplayHarnessCaller() {
        JavaClasses mutation = new ClassFileImporter().importClasses(
                UnauthorizedEagerPlan.class);

        List<String> violations = eagerPlanCalls(mutation, Set.of());

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().contains(
                UnauthorizedEagerPlan.class.getName()), violations.toString());
    }

    @Test
    void sourceGuardReportsEachPrimitiveForConstructedNamesExactly() {
        assertAll(
                () -> assertExactSourceViolation(
                        "ClassForNameConcat.java:5 Class.forName resolves active payload "
                                + "owner com.openggf.trace.replay.runs."
                                + "ActiveSegmentPayload",
                        "ClassForNameConcat.java", """
                                class ClassForNameConcat {
                                    Object acquire() throws Exception {
                                        String type = "com.openggf.trace.replay.runs."
                                                + "ActiveSegment";
                                        return Class.forName(type.concat("Payload"));
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "GetDeclaredMethodConcat.java:4 getDeclaredMethod acquires active "
                                + "payload accessor trace on "
                                + "com.openggf.trace.replay.runs.ActiveSegmentPayload",
                        "GetDeclaredMethodConcat.java", """
                                class GetDeclaredMethodConcat {
                                    Object acquire() throws Exception {
                                        String name = "tr".concat("ace");
                                        return ActiveSegmentPayload.class
                                                .getDeclaredMethod(name);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "GetMethodSplitLocals.java:6 getMethod acquires active payload "
                                + "accessor trace on "
                                + "com.openggf.trace.replay.runs.ActiveSegmentPayload",
                        "GetMethodSplitLocals.java", """
                                class GetMethodSplitLocals {
                                    Object acquire() throws Exception {
                                        String prefix = "tr";
                                        String suffix = "ace";
                                        String name = prefix + suffix;
                                        return ActiveSegmentPayload.class.getMethod(name);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "GetDeclaredMethodBuilder.java:6 getDeclaredMethod acquires active "
                                + "payload accessor specialStageRows on "
                                + "com.openggf.trace.replay.runs.ActiveSegmentPayload",
                        "GetDeclaredMethodBuilder.java", """
                                class GetDeclaredMethodBuilder {
                                    Object acquire() throws Exception {
                                        StringBuilder name = new StringBuilder("special");
                                        name.append("Stage");
                                        name.append("Rows");
                                        return ActiveSegmentPayload.class
                                                .getDeclaredMethod(name.toString());
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "FindVirtualJoin.java:5 findVirtual acquires active payload accessor "
                                + "trace on "
                                + "com.openggf.trace.replay.runs.ActiveSegmentPayload",
                        "FindVirtualJoin.java", """
                                class FindVirtualJoin {
                                    Object acquire(MethodHandles.Lookup lookup)
                                            throws Exception {
                                        String name = String.join("", "tr", "ace");
                                        return lookup.findVirtual(
                                                ActiveSegmentPayload.class, name,
                                                MethodType.methodType(TraceData.class));
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "FindStaticPlus.java:5 findStatic acquires active payload accessor "
                                + "openActiveSegment on "
                                + "com.openggf.trace.replay.runs.TraceRunReplayWalker",
                        "FindStaticPlus.java", """
                                class FindStaticPlus {
                                    Object acquire(MethodHandles.Lookup lookup)
                                            throws Exception {
                                        String name = "openActive" + "Segment";
                                        return lookup.findStatic(
                                                TraceRunReplayWalker.class, name,
                                                MethodType.methodType(
                                                        ActiveSegmentPayload.class,
                                                        TraceRunSegmentDescriptor.class,
                                                        int.class));
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "UnknownAccessor.java:3 getMethod uses an unresolved accessor "
                                + "name on com.openggf.trace.replay.runs."
                                + "ActiveSegmentPayload",
                        "UnknownAccessor.java", """
                                class UnknownAccessor {
                                    Object acquire(String name) throws Exception {
                                        return ActiveSegmentPayload.class.getMethod(name);
                                    }
                                }
                                """));
    }

    @Test
    void sourceGuardFailsClosedForRestrictedRuntimeNamesAndEnumeration() {
        assertAll(
                () -> assertExactSourceViolation(
                        "RuntimeDeclaredLocal.java:5 getDeclaredMethod uses an unresolved "
                                + "accessor name on com.openggf.trace.replay.runs."
                                + "ActiveSegmentPayload",
                        "RuntimeDeclaredLocal.java", """
                                class RuntimeDeclaredLocal {
                                    Object acquire(String supplied) throws Exception {
                                        String name = supplied;
                                        Class<?> target = ActiveSegmentPayload.class;
                                        return target.getDeclaredMethod(name);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "RuntimeFindVirtual.java:4 findVirtual uses an unresolved accessor "
                                + "name on com.openggf.trace.replay.runs."
                                + "ActiveSegmentPayload",
                        "RuntimeFindVirtual.java", """
                                class RuntimeFindVirtual {
                                    Object acquire(MethodHandles.Lookup lookup, String name)
                                            throws Exception {
                                        return lookup.findVirtual(ActiveSegmentPayload.class,
                                                name, MethodType.methodType(TraceData.class));
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "RuntimeFindStatic.java:4 findStatic uses an unresolved accessor "
                                + "name on com.openggf.trace.replay.runs."
                                + "TraceRunReplayWalker",
                        "RuntimeFindStatic.java", """
                                class RuntimeFindStatic {
                                    Object acquire(MethodHandles.Lookup lookup, String name)
                                            throws Exception {
                                        return lookup.findStatic(TraceRunReplayWalker.class,
                                                name, MethodType.methodType(Object.class));
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "DeclaredMethodEnumeration.java:4 getDeclaredMethods enumerates "
                                + "active payload accessors on com.openggf.trace.replay."
                                + "runs.ActiveSegmentPayload",
                        "DeclaredMethodEnumeration.java", """
                                class DeclaredMethodEnumeration {
                                    Object acquire(ActiveSegmentPayload payload, String name)
                                            throws Exception {
                                        for (Method method : ActiveSegmentPayload.class
                                                .getDeclaredMethods()) {
                                            if (method.getName().equals(name)) {
                                                return method.invoke(payload);
                                            }
                                        }
                                        return null;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "RuntimeDeclaredField.java:3 getDeclaredField uses an unresolved "
                                + "field name on com.openggf.trace.replay.runs."
                                + "ActiveSegmentPayload",
                        "RuntimeDeclaredField.java", """
                                class RuntimeDeclaredField {
                                    Object acquire(String name) throws Exception {
                                        return ActiveSegmentPayload.class
                                                .getDeclaredField(name);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "RuntimeFindGetter.java:4 findGetter uses an unresolved field name "
                                + "on com.openggf.trace.replay.runs.ActiveSegmentPayload",
                        "RuntimeFindGetter.java", """
                                class RuntimeFindGetter {
                                    Object acquire(MethodHandles.Lookup lookup, String name)
                                            throws Exception {
                                        return lookup.findGetter(ActiveSegmentPayload.class,
                                                name, Object.class);
                                    }
                                }
                                """));
    }

    @Test
    void sourceGuardReportsEachRestrictedFieldEnumerationPrimitiveExactly() {
        assertAll(
                () -> assertExactSourceViolation(
                        "DeclaredFieldsRuntimeName.java:4 getDeclaredFields "
                                + "enumerates active payload fields on "
                                + "com.openggf.trace.replay.runs."
                                + "ActiveSegmentPayload",
                        "DeclaredFieldsRuntimeName.java", """
                                class DeclaredFieldsRuntimeName {
                                    Object acquire(ActiveSegmentPayload payload, String name)
                                            throws Exception {
                                        for (Field field : ActiveSegmentPayload.class
                                                .getDeclaredFields()) {
                                            if (field.getName().equals(name)) {
                                                field.setAccessible(true);
                                                return field.get(payload);
                                            }
                                        }
                                        return null;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "FieldsRuntimeType.java:3 getFields enumerates active payload "
                                + "fields on com.openggf.trace.replay.runs."
                                + "TraceRunReplayWalker",
                        "FieldsRuntimeType.java", """
                                class FieldsRuntimeType {
                                    Object acquire(Class<?> type) throws Exception {
                                        for (Field field : TraceRunReplayWalker.class
                                                .getFields()) {
                                            if (field.getType() == type) {
                                                return field.get(null);
                                            }
                                        }
                                        return null;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "DeclaredFieldsUnreflectGetter.java:4 getDeclaredFields "
                                + "enumerates active payload fields on "
                                + "com.openggf.trace.replay.runs."
                                + "ActiveSegmentPayload",
                        "DeclaredFieldsUnreflectGetter.java", """
                                class DeclaredFieldsUnreflectGetter {
                                    Object acquire(MethodHandles.Lookup lookup, String name)
                                            throws Exception {
                                        Field[] fields = ActiveSegmentPayload.class
                                                .getDeclaredFields();
                                        for (Field field : fields) {
                                            if (field.getName().equals(name)) {
                                                return lookup.unreflectGetter(field);
                                            }
                                        }
                                        return null;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "FieldsUnreflectSetter.java:4 getFields enumerates active payload "
                                + "fields on com.openggf.trace.replay.runs."
                                + "TraceRunReplayWalker",
                        "FieldsUnreflectSetter.java", """
                                class FieldsUnreflectSetter {
                                    Object acquire(MethodHandles.Lookup lookup, Class<?> type)
                                            throws Exception {
                                        Field[] fields = TraceRunReplayWalker.class.getFields();
                                        for (Field field : fields) {
                                            if (field.getType() == type) {
                                                return lookup.unreflectSetter(field);
                                            }
                                        }
                                        return null;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "LauncherDeclaredFields.java:3 getDeclaredFields enumerates "
                                + "active payload fields on com.openggf."
                                + "TraceSessionLauncher",
                        "LauncherDeclaredFields.java", """
                                class LauncherDeclaredFields {
                                    Object acquire(String name) throws Exception {
                                        for (Field field : TraceSessionLauncher.class
                                                .getDeclaredFields()) {
                                            if (field.getName().equals(name)) {
                                                field.setAccessible(true);
                                                return field.get(this);
                                            }
                                        }
                                        return null;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "HarnessDeclaredFields.java:3 getDeclaredFields enumerates "
                                + "active payload fields on com.openggf.tests.trace.runs."
                                + "AbstractRunChainTest",
                        "HarnessDeclaredFields.java", """
                                class HarnessDeclaredFields {
                                    Object acquire(Class<?> type) throws Exception {
                                        Field[] fields = AbstractRunChainTest.class
                                                .getDeclaredFields();
                                        for (Field field : fields) {
                                            if (field.getType() == type) {
                                                return field.get(this);
                                            }
                                        }
                                        return null;
                                    }
                                }
                                """));
    }

    @Test
    void sourceGuardReportsEveryExactOwnerFieldAcquisitionPrimitiveExactly() {
        assertAll(
                () -> assertExactSourceViolation(
                        "OwnerRuntimeDeclaredField.java:3 getDeclaredField uses an "
                                + "unresolved field name on com.openggf."
                                + "TraceSessionLauncher",
                        "OwnerRuntimeDeclaredField.java", """
                                class OwnerRuntimeDeclaredField {
                                    Object acquire(String name) throws Exception {
                                        return TraceSessionLauncher.class.getDeclaredField(name);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OwnerKnownLeaseGet.java:3 getDeclaredField acquires field "
                                + "activeRunPayload on com.openggf.TraceSessionLauncher",
                        "OwnerKnownLeaseGet.java", """
                                class OwnerKnownLeaseGet {
                                    Object acquire(TraceSessionLauncher owner) throws Exception {
                                        Field field = TraceSessionLauncher.class
                                                .getDeclaredField("activeRunPayload");
                                        field.setAccessible(true);
                                        return field.get(owner);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OwnerRuntimeGetField.java:3 getField uses an unresolved field "
                                + "name on com.openggf.tests.trace.runs."
                                + "AbstractRunChainTest",
                        "OwnerRuntimeGetField.java", """
                                class OwnerRuntimeGetField {
                                    Object acquire(String name) throws Exception {
                                        return AbstractRunChainTest.class.getField(name);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OwnerFindGetter.java:3 findGetter acquires field activeRunPayload "
                                + "on com.openggf.TraceSessionLauncher",
                        "OwnerFindGetter.java", """
                                class OwnerFindGetter {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findGetter(TraceSessionLauncher.class,
                                                "activeRunPayload", ActiveSegmentPayload.class);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OwnerFindSetter.java:3 findSetter acquires field "
                                + "activeSegmentPayload on com.openggf.tests.trace.runs."
                                + "AbstractRunChainTest",
                        "OwnerFindSetter.java", """
                                class OwnerFindSetter {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findSetter(AbstractRunChainTest.class,
                                                "activeSegmentPayload",
                                                ActiveSegmentPayload.class);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OwnerFindStaticGetter.java:3 findStaticGetter acquires field "
                                + "runSpecialRows on com.openggf.TraceSessionLauncher",
                        "OwnerFindStaticGetter.java", """
                                class OwnerFindStaticGetter {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findStaticGetter(TraceSessionLauncher.class,
                                                "runSpecialRows",
                                                TraceRunSpecialStageRows.class);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OwnerFindStaticSetter.java:3 findStaticSetter acquires field "
                                + "activeSpecialRows on com.openggf.tests.trace.runs."
                                + "AbstractRunChainTest",
                        "OwnerFindStaticSetter.java", """
                                class OwnerFindStaticSetter {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findStaticSetter(AbstractRunChainTest.class,
                                                "activeSpecialRows",
                                                TraceRunSpecialStageRows.class);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OwnerFindVarHandle.java:3 findVarHandle acquires field "
                                + "activeRunPayload on com.openggf.TraceSessionLauncher",
                        "OwnerFindVarHandle.java", """
                                class OwnerFindVarHandle {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findVarHandle(TraceSessionLauncher.class,
                                                "activeRunPayload", ActiveSegmentPayload.class);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OwnerFindStaticVarHandle.java:3 findStaticVarHandle acquires field "
                                + "activeSegmentPayload on com.openggf.tests.trace.runs."
                                + "AbstractRunChainTest",
                        "OwnerFindStaticVarHandle.java", """
                                class OwnerFindStaticVarHandle {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findStaticVarHandle(
                                                AbstractRunChainTest.class,
                                                "activeSegmentPayload",
                                                ActiveSegmentPayload.class);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OwnerUnreflectGetter.java:3 getDeclaredField acquires field "
                                + "activeRunPayload on com.openggf.TraceSessionLauncher",
                        "OwnerUnreflectGetter.java", """
                                class OwnerUnreflectGetter {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        Field field = TraceSessionLauncher.class
                                                .getDeclaredField("activeRunPayload");
                                        return lookup.unreflectGetter(field);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OwnerUnreflectSetter.java:3 getDeclaredField acquires field "
                                + "activeSegmentPayload on com.openggf.tests.trace.runs."
                                + "AbstractRunChainTest",
                        "OwnerUnreflectSetter.java", """
                                class OwnerUnreflectSetter {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        Field field = AbstractRunChainTest.class
                                                .getDeclaredField("activeSegmentPayload");
                                        return lookup.unreflectSetter(field);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OwnerFieldSet.java:3 getDeclaredField acquires field "
                                + "activeRunPayload on com.openggf.TraceSessionLauncher",
                        "OwnerFieldSet.java", """
                                class OwnerFieldSet {
                                    void mutate(Object owner, Object value) throws Exception {
                                        Field field = TraceSessionLauncher.class
                                                .getDeclaredField("activeRunPayload");
                                        field.setAccessible(true);
                                        field.set(owner, value);
                                    }
                                }
                                """));
    }

    @Test
    void sourceGuardPropagatesTypedReceiversThroughGenericHelpersExactly() {
        assertAll(
                () -> assertExactSourceViolation(
                        "TypedReceiverRuntime.java:3 getDeclaredField uses an "
                                + "unresolved field name on com.openggf."
                                + "TraceSessionLauncher",
                        "TypedReceiverRuntime.java", """
                                class TypedReceiverRuntime {
                                    Object acquire(TraceSessionLauncher target, String name) throws Exception {
                                        return target.getClass().getDeclaredField(name);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OneHelperGraph.java:6 getDeclaredField acquires field "
                                + "activeRunPayload on com.openggf."
                                + "TraceSessionLauncher",
                        "OneHelperGraph.java", """
                                class OneHelperGraph {
                                    Object acquire(TraceSessionLauncher target) throws Exception {
                                        return field(target, "activeRunPayload");
                                    }
                                    private Object field(Object target, String name) throws Exception {
                                        return target.getClass().getDeclaredField(name);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "MultiHelperGraph.java:9 getDeclaredField acquires field "
                                + "activeSpecialRows on com.openggf.tests."
                                + "trace.runs.AbstractRunChainTest",
                        "MultiHelperGraph.java", """
                                class MultiHelperGraph {
                                    Object acquire(AbstractRunChainTest target) throws Exception {
                                        return outer(target, "activeSpecialRows");
                                    }
                                    private Object outer(Object target, String name) throws Exception {
                                        return inner(target, name);
                                    }
                                    private Object inner(Object target, String name) throws Exception {
                                        return target.getClass().getDeclaredField(name);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "HelperRuntimeName.java:6 getDeclaredField uses an "
                                + "unresolved field name on com.openggf."
                                + "TraceSessionLauncher",
                        "HelperRuntimeName.java", """
                                class HelperRuntimeName {
                                    Object acquire(TraceSessionLauncher target, String name) throws Exception {
                                        return field(target, name);
                                    }
                                    private Object field(Object target, String name) throws Exception {
                                        return target.getClass().getDeclaredField(name);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "HelperFindGetter.java:8 findGetter acquires field "
                                + "activeRunPayload on com.openggf."
                                + "TraceSessionLauncher",
                        "HelperFindGetter.java", """
                                class HelperFindGetter {
                                    Object acquire(TraceSessionLauncher target,
                                            MethodHandles.Lookup lookup) throws Exception {
                                        return getter(target, "activeRunPayload", lookup);
                                    }
                                    private Object getter(Object target, String name,
                                            MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findGetter(target.getClass(), name,
                                                ActiveSegmentPayload.class);
                                    }
                                }
                                """));
    }

    @Test
    void sourceGuardAllowsHarmlessAndUnrelatedTypedHelperFields() {
        assertNoSourceViolation(
                "TypedHelperControls.java", """
                        class TypedHelperControls {
                            Object harmless(TraceSessionLauncher target) throws Exception {
                                return field(target, "runSpecialTimingRow");
                            }
                            Object unrelated(StringBuilder target, String name) throws Exception {
                                return field(target, name);
                            }
                            private Object field(Object target, String name) throws Exception {
                                return target.getClass().getDeclaredField(name);
                            }
                        }
                        """);
    }

    @Test
    void sourceGuardDoesNotMistakeCollidingHelperNamesForPrimitives() {
        assertAll(
                () -> assertExactSourceViolation(
                        "CollidingGetField.java:6 getDeclaredField acquires field "
                                + "activeRunPayload on com.openggf."
                                + "TraceSessionLauncher",
                        "CollidingGetField.java", """
                                class CollidingGetField {
                                    Object acquire(TraceSessionLauncher target) throws Exception {
                                        return getField(target, "activeRunPayload");
                                    }
                                    private Object getField(Object target, String name) throws Exception {
                                        return target.getClass().getDeclaredField(name);
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "CollidingFindGetter.java:6 findGetter acquires field "
                                + "activeSegmentPayload on com.openggf.tests."
                                + "trace.runs.AbstractRunChainTest",
                        "CollidingFindGetter.java", """
                                class CollidingFindGetter {
                                    Object acquire(AbstractRunChainTest target, MethodHandles.Lookup lookup) throws Exception {
                                        return findGetter(target, "activeSegmentPayload", lookup);
                                    }
                                    private Object findGetter(Object target, String name, MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findGetter(target.getClass(), name, ActiveSegmentPayload.class);
                                    }
                                }
                                """));
    }

    @Test
    void collidingHelperNamesAllowHarmlessFieldsAndUnrelatedOwners() {
        assertNoSourceViolation(
                "CollidingHelperControls.java", """
                        class CollidingHelperControls {
                            Object harmlessField(TraceSessionLauncher target) throws Exception {
                                return getField(target, "runSpecialTimingRow");
                            }
                            Object harmlessGetter(TraceSessionLauncher target,
                                    MethodHandles.Lookup lookup) throws Exception {
                                return findGetter(target, "runSpecialTimingRow", lookup);
                            }
                            Object unrelated(StringBuilder target, String name) throws Exception {
                                return getField(target, name);
                            }
                            private Object getField(Object target, String name) throws Exception {
                                return target.getClass().getDeclaredField(name);
                            }
                            private Object findGetter(Object target, String name,
                                    MethodHandles.Lookup lookup) throws Exception {
                                return lookup.findGetter(target.getClass(), name, int.class);
                            }
                        }
                        """);
    }

    @Test
    void lookupTransformationsCannotEraseAccessorAuthority() {
        String diagnostic = " findVirtual acquires active payload accessor trace on "
                + "com.openggf.trace.replay.runs.ActiveSegmentPayload";
        assertAll(
                () -> assertExactSourceViolation(
                        "PublicLookupAccessor.java:3" + diagnostic,
                        "PublicLookupAccessor.java", """
                                class PublicLookupAccessor {
                                    Object acquire() throws Exception {
                                        return MethodHandles.publicLookup().findVirtual(ActiveSegmentPayload.class, "trace", MethodType.methodType(TraceData.class));
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "PrivateLookupAccessor.java:3" + diagnostic,
                        "PrivateLookupAccessor.java", """
                                class PrivateLookupAccessor {
                                    Object acquire() throws Exception {
                                        return MethodHandles.privateLookupIn(ActiveSegmentPayload.class, MethodHandles.lookup()).findVirtual(ActiveSegmentPayload.class, "trace", MethodType.methodType(TraceData.class));
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "NarrowedLookupAccessor.java:3" + diagnostic,
                        "NarrowedLookupAccessor.java", """
                                class NarrowedLookupAccessor {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.in(ActiveSegmentPayload.class).findVirtual(ActiveSegmentPayload.class, "trace", MethodType.methodType(TraceData.class));
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "DroppedLookupAccessor.java:3" + diagnostic,
                        "DroppedLookupAccessor.java", """
                                class DroppedLookupAccessor {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.dropLookupMode(1).findVirtual(ActiveSegmentPayload.class, "trace", MethodType.methodType(TraceData.class));
                                    }
                                }
                                """));
    }

    @Test
    void transformedLookupControlsRemainNarrow() {
        assertNoSourceViolation(
                "TransformedLookupControls.java", """
                        class TransformedLookupControls {
                            Object unrelated() throws Exception {
                                return MethodHandles.publicLookup().findVirtual(StringBuilder.class,
                                        "append", MethodType.methodType(
                                                StringBuilder.class, String.class));
                            }
                            Object harmless() throws Exception {
                                return MethodHandles.publicLookup().findVirtual(
                                        ActiveSegmentPayload.class, "isClosed",
                                        MethodType.methodType(boolean.class));
                            }
                            Object collidingHelper(ActiveSegmentPayload target) {
                                return findVirtual(target, "isClosed");
                            }
                            private Object findVirtual(Object target, String name) {
                                return name;
                            }
                        }
                        """);
    }

    @Test
    void standardClassProducersAndQualifiedAliasesCannotEraseAuthority() {
        String accessorDiagnostic =
                " getDeclaredMethod acquires active payload accessor trace on "
                        + PAYLOAD;
        String lookupDiagnostic =
                " findVirtual acquires active payload accessor trace on " + PAYLOAD;
        assertAll(
                () -> assertExactSourceViolations(
                        List.of(
                                "StaticImportedForName.java:5" + accessorDiagnostic,
                                "StaticImportedForName.java:5 Class.forName resolves "
                                        + "active payload owner " + PAYLOAD),
                        "StaticImportedForName.java", """
                                import static java.lang.Class.forName;
                                class StaticImportedForName {
                                    private static final String ACTIVE_PAYLOAD_FQCN = "com.openggf.trace.replay.runs.ActiveSegmentPayload";
                                    Object acquire() throws Exception {
                                        return forName(ACTIVE_PAYLOAD_FQCN).getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "ClassLoaderAccessor.java:4" + accessorDiagnostic,
                        "ClassLoaderAccessor.java", """
                                class ClassLoaderAccessor {
                                    private static final String ACTIVE_PAYLOAD_FQCN = "com.openggf.trace.replay.runs.ActiveSegmentPayload";
                                    Object acquire(ClassLoader loader) throws Exception {
                                        return loader.loadClass(ACTIVE_PAYLOAD_FQCN).getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "AsSubclassAccessor.java:3" + accessorDiagnostic,
                        "AsSubclassAccessor.java", """
                                class AsSubclassAccessor {
                                    Object acquire() throws Exception {
                                        return ActiveSegmentPayload.class.asSubclass(Object.class).getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "InstanceClassAlias.java:3" + lookupDiagnostic,
                        "InstanceClassAlias.java", """
                                class InstanceClassAlias {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findVirtual(this.type, "trace", MethodType.methodType(TraceData.class));
                                    }
                                    private final Class<?> type = ActiveSegmentPayload.class;
                                }
                                """),
                () -> assertExactSourceViolation(
                        "OwnerClassAlias.java:6" + lookupDiagnostic,
                        "OwnerClassAlias.java", """
                                class Owner {
                                    static final Class<?> TYPE = ActiveSegmentPayload.class;
                                }
                                class OwnerClassAlias {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findVirtual(Owner.TYPE, "trace", MethodType.methodType(TraceData.class));
                                    }
                                }
                                """));
    }

    @Test
    void standardClassProducerAndQualifiedAliasControlsRemainNarrow() {
        assertNoSourceViolation(
                "ClassProducerControls.java", """
                        import static java.lang.Class.forName;
                        class HarmlessOwner {
                            static final Class<?> TYPE = StringBuilder.class;
                        }
                        class ClassProducerControls {
                            private static final String ACTIVE_PAYLOAD_FQCN = "com.openggf.trace.replay.runs.ActiveSegmentPayload";
                            private final Class<?> type = StringBuilder.class;
                            static final Class<?> TYPE = StringBuilder.class;
                            Object inspect(ClassLoader loader, MethodHandles.Lookup lookup)
                                    throws Exception {
                                forName("java.lang.String").getDeclaredMethod("trace");
                                forName(ACTIVE_PAYLOAD_FQCN).getDeclaredMethod("trace");
                                loader.loadClass("java.lang.String").getDeclaredMethod("trace");
                                StringBuilder.class.asSubclass(Object.class)
                                        .getDeclaredMethod("trace");
                                lookup.findVirtual(this.type, "trace",
                                        MethodType.methodType(Object.class));
                                lookup.findVirtual(ClassProducerControls.TYPE, "trace",
                                        MethodType.methodType(Object.class));
                                return lookup.findVirtual(HarmlessOwner.TYPE, "trace",
                                        MethodType.methodType(Object.class));
                            }
                            Class<?> forName(String ignored) {
                                return StringBuilder.class;
                            }
                        }
                        """);
    }

    @Test
    void forwardFieldsLoaderVariantsAndNestHostsCannotEraseAuthority() {
        String accessorDiagnostic =
                " getDeclaredMethod acquires active payload accessor trace on "
                        + PAYLOAD;
        String lookupDiagnostic =
                " findVirtual acquires active payload accessor trace on " + PAYLOAD;
        assertAll(
                () -> assertExactSourceViolation(
                        "BareForwardClassAlias.java:3" + lookupDiagnostic,
                        "BareForwardClassAlias.java", """
                                class BareForwardClassAlias {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findVirtual(type, "trace", MethodType.methodType(TraceData.class));
                                    }
                                    private final Class<?> type = ActiveSegmentPayload.class;
                                }
                                """),
                () -> assertExactSourceViolation(
                        "ContextClassLoaderAccessor.java:4" + accessorDiagnostic,
                        "ContextClassLoaderAccessor.java", """
                                class ContextClassLoaderAccessor {
                                    private static final String ACTIVE_PAYLOAD_FQCN = "com.openggf.trace.replay.runs.ActiveSegmentPayload";
                                    Object acquire() throws Exception {
                                        return Thread.currentThread().getContextClassLoader().loadClass(ACTIVE_PAYLOAD_FQCN).getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "PlatformClassLoaderAccessor.java:4" + accessorDiagnostic,
                        "PlatformClassLoaderAccessor.java", """
                                class PlatformClassLoaderAccessor {
                                    private static final String ACTIVE_PAYLOAD_FQCN = "com.openggf.trace.replay.runs.ActiveSegmentPayload";
                                    Object acquire() throws Exception {
                                        return ClassLoader.getPlatformClassLoader().loadClass(ACTIVE_PAYLOAD_FQCN).getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "UrlClassLoaderAccessor.java:4" + accessorDiagnostic,
                        "UrlClassLoaderAccessor.java", """
                                class UrlClassLoaderAccessor {
                                    private static final String ACTIVE_PAYLOAD_FQCN = "com.openggf.trace.replay.runs.ActiveSegmentPayload";
                                    Object acquire(URLClassLoader loader) throws Exception {
                                        return loader.loadClass(ACTIVE_PAYLOAD_FQCN).getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "NestHostAccessor.java:3" + accessorDiagnostic,
                        "NestHostAccessor.java", """
                                class NestHostAccessor {
                                    Object acquire() throws Exception {
                                        return ActiveSegmentPayload.class.getNestHost().getDeclaredMethod("trace");
                                    }
                                }
                                """));
    }

    @Test
    void forwardFieldLoaderAndNestHostControlsRemainNarrow() {
        assertNoSourceViolation(
                "ClassProducerEdgeControls.java", """
                        class ClassProducerEdgeControls {
                            private static final String ACTIVE_PAYLOAD_FQCN = "com.openggf.trace.replay.runs.ActiveSegmentPayload";
                            private final Class<?> type = ActiveSegmentPayload.class;
                            Object inspect(Class<?> type, MethodHandles.Lookup lookup,
                                    URLClassLoader loader) throws Exception {
                                lookup.findVirtual(type, "trace",
                                        MethodType.methodType(Object.class));
                                Class<?> localType = StringBuilder.class;
                                lookup.findVirtual(localType, "trace",
                                        MethodType.methodType(Object.class));
                                Thread.currentThread().getContextClassLoader()
                                        .loadClass("java.lang.String")
                                        .getDeclaredMethod("trace");
                                ClassLoader.getPlatformClassLoader()
                                        .loadClass("java.lang.String")
                                        .getDeclaredMethod("trace");
                                loader.loadClass("java.lang.String")
                                        .getDeclaredMethod("trace");
                                StringBuilder.class.getNestHost()
                                        .getDeclaredMethod("trace");
                                loadClass(ACTIVE_PAYLOAD_FQCN)
                                        .getDeclaredMethod("trace");
                                return getNestHost(ActiveSegmentPayload.class)
                                        .getDeclaredMethod("trace");
                            }
                            Class<?> loadClass(String ignored) {
                                return StringBuilder.class;
                            }
                            Class<?> getNestHost(Class<?> ignored) {
                                return StringBuilder.class;
                            }
                        }
                        """);
    }

    @Test
    void sameSourceHelperReturnsCannotEraseAuthority() {
        String lookupDiagnostic =
                " findVirtual acquires active payload accessor trace on " + PAYLOAD;
        String accessorDiagnostic =
                " getDeclaredMethod acquires active payload accessor trace on "
                        + PAYLOAD;
        assertAll(
                () -> assertExactSourceViolation(
                        "HelperClassAccessor.java:3" + lookupDiagnostic,
                        "HelperClassAccessor.java", """
                                class HelperClassAccessor {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findVirtual(payloadType(), "trace", MethodType.methodType(TraceData.class));
                                    }
                                    Class<?> payloadType() {
                                        return ActiveSegmentPayload.class;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "IdentityHelperAccessor.java:3" + lookupDiagnostic,
                        "IdentityHelperAccessor.java", """
                                class IdentityHelperAccessor {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findVirtual(identity(ActiveSegmentPayload.class), "trace", MethodType.methodType(TraceData.class));
                                    }
                                    Class<?> identity(Class<?> type) {
                                        return type;
                                    }
                                }
                                """),
                () -> assertExactSourceViolations(
                        List.of(
                                "HelperForNameAccessor.java:3" + accessorDiagnostic,
                                "HelperForNameAccessor.java:3 Class.forName resolves "
                                        + "active payload owner " + PAYLOAD),
                        "HelperForNameAccessor.java", """
                                class HelperForNameAccessor {
                                    Object acquire() throws Exception {
                                        return Class.forName(payloadName()).getDeclaredMethod("trace");
                                    }
                                    String payloadName() {
                                        return "com.openggf.trace.replay.runs.ActiveSegmentPayload";
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "MultiHelperAccessor.java:3" + lookupDiagnostic,
                        "MultiHelperAccessor.java", """
                                class MultiHelperAccessor {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findVirtual(payloadType(), "trace", MethodType.methodType(TraceData.class));
                                    }
                                    Class<?> payloadType() {
                                        return payloadAlias();
                                    }
                                    Class<?> payloadAlias() {
                                        return ActiveSegmentPayload.class;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "MultipleReturnAccessor.java:3" + lookupDiagnostic,
                        "MultipleReturnAccessor.java", """
                                class MultipleReturnAccessor {
                                    Object acquire(MethodHandles.Lookup lookup, boolean restricted) throws Exception {
                                        return lookup.findVirtual(maybePayload(restricted), "trace", MethodType.methodType(TraceData.class));
                                    }
                                    Class<?> maybePayload(boolean restricted) {
                                        if (restricted) return StringBuilder.class;
                                        return ActiveSegmentPayload.class;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "HelperSideEffectAccessor.java:6" + accessorDiagnostic,
                        "HelperSideEffectAccessor.java", """
                                class HelperSideEffectAccessor {
                                    Object acquire() throws Exception {
                                        return inspect(ActiveSegmentPayload.class);
                                    }
                                    Class<?> inspect(Class<?> type) throws Exception {
                                        type.getDeclaredMethod("trace");
                                        return StringBuilder.class;
                                    }
                                }
                                """));
    }

    @Test
    void sameSourceHelperReturnControlsRemainBoundedAndNarrow() {
        assertNoSourceViolation(
                "HelperReturnControls.java", """
                        class HelperReturnControls {
                            Object inspect(MethodHandles.Lookup lookup, boolean first)
                                    throws Exception {
                                lookup.findVirtual(cycleA(), "trace",
                                        MethodType.methodType(Object.class));
                                lookup.findVirtual(overloaded("safe"), "trace",
                                        MethodType.methodType(Object.class));
                                lookup.findVirtual(unrelated(), "trace",
                                        MethodType.methodType(Object.class));
                                lookup.findVirtual(identity(StringBuilder.class), "trace",
                                        MethodType.methodType(Object.class));
                                lookup.findVirtual(maybeUnrelated(first), "trace",
                                        MethodType.methodType(Object.class));
                                return Class.forName(unrelatedName())
                                        .getDeclaredMethod("trace");
                            }
                            Class<?> cycleA() {
                                return cycleB();
                            }
                            Class<?> cycleB() {
                                return cycleA();
                            }
                            Class<?> overloaded(String ignored) {
                                return StringBuilder.class;
                            }
                            Class<?> overloaded(int ignored) {
                                return ActiveSegmentPayload.class;
                            }
                            Class<?> unrelated() {
                                return StringBuilder.class;
                            }
                            Class<?> identity(Class<?> type) {
                                return type;
                            }
                            Class<?> maybeUnrelated(boolean first) {
                                if (first) return StringBuilder.class;
                                return String.class;
                            }
                            String unrelatedName() {
                                return "java.lang.String";
                            }
                        }
                        """);
    }

    @Test
    void helperBranchesCannotDiscardRestrictedReturnAlternatives() {
        String lookupDiagnostic =
                " findVirtual acquires active payload accessor trace on " + PAYLOAD;
        String accessorDiagnostic =
                " getDeclaredMethod acquires active payload accessor trace on "
                        + PAYLOAD;
        assertAll(
                () -> assertExactSourceViolation(
                        "UnknownTernaryHelperAccessor.java:3" + lookupDiagnostic,
                        "UnknownTernaryHelperAccessor.java", """
                                class UnknownTernaryHelperAccessor {
                                    Object acquire(MethodHandles.Lookup lookup, boolean restricted, Class<?> runtimeType) throws Exception {
                                        return lookup.findVirtual(payloadType(restricted, runtimeType), "trace", MethodType.methodType(TraceData.class));
                                    }
                                    Class<?> payloadType(boolean restricted, Class<?> runtimeType) {
                                        return restricted ? ActiveSegmentPayload.class : runtimeType;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "TernaryHelperAccessor.java:3" + lookupDiagnostic,
                        "TernaryHelperAccessor.java", """
                                class TernaryHelperAccessor {
                                    Object acquire(MethodHandles.Lookup lookup, boolean restricted) throws Exception {
                                        return lookup.findVirtual(payloadType(restricted), "trace", MethodType.methodType(TraceData.class));
                                    }
                                    Class<?> payloadType(boolean restricted) {
                                        return restricted ? ActiveSegmentPayload.class : StringBuilder.class;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "IfAssignedHelperAccessor.java:3" + lookupDiagnostic,
                        "IfAssignedHelperAccessor.java", """
                                class IfAssignedHelperAccessor {
                                    Object acquire(MethodHandles.Lookup lookup, boolean restricted) throws Exception {
                                        return lookup.findVirtual(payloadType(restricted), "trace", MethodType.methodType(TraceData.class));
                                    }
                                    Class<?> payloadType(boolean restricted) {
                                        Class<?> result;
                                        if (restricted) result = ActiveSegmentPayload.class;
                                        else result = StringBuilder.class;
                                        return result;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "SwitchExpressionHelperAccessor.java:3" + lookupDiagnostic,
                        "SwitchExpressionHelperAccessor.java", """
                                class SwitchExpressionHelperAccessor {
                                    Object acquire(MethodHandles.Lookup lookup, int selector) throws Exception {
                                        return lookup.findVirtual(payloadType(selector), "trace", MethodType.methodType(TraceData.class));
                                    }
                                    Class<?> payloadType(int selector) {
                                        return switch (selector) {
                                            case 0 -> ActiveSegmentPayload.class;
                                            default -> StringBuilder.class;
                                        };
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "SwitchStatementHelperAccessor.java:3" + lookupDiagnostic,
                        "SwitchStatementHelperAccessor.java", """
                                class SwitchStatementHelperAccessor {
                                    Object acquire(MethodHandles.Lookup lookup, int selector) throws Exception {
                                        return lookup.findVirtual(payloadType(selector), "trace", MethodType.methodType(TraceData.class));
                                    }
                                    Class<?> payloadType(int selector) {
                                        Class<?> result;
                                        switch (selector) {
                                            case 0 -> result = ActiveSegmentPayload.class;
                                            default -> result = StringBuilder.class;
                                        }
                                        return result;
                                    }
                                }
                                """),
                () -> assertExactSourceViolations(
                        List.of(
                                "BuilderNameHelperAccessor.java:3"
                                        + accessorDiagnostic,
                                "BuilderNameHelperAccessor.java:3 Class.forName "
                                        + "resolves active payload owner " + PAYLOAD),
                        "BuilderNameHelperAccessor.java", """
                                class BuilderNameHelperAccessor {
                                    Object acquire() throws Exception {
                                        return Class.forName(payloadName()).getDeclaredMethod("trace");
                                    }
                                    String payloadName() {
                                        StringBuilder name = new StringBuilder("com.openggf.trace.replay.runs.ActiveSegment");
                                        name.append("Payload");
                                        return name.toString();
                                    }
                                }
                                """));
    }

    @Test
    void harmlessHelperBranchesRemainUnrestricted() {
        assertNoSourceViolation(
                "HarmlessHelperBranches.java", """
                        class HarmlessHelperBranches {
                            Object inspect(MethodHandles.Lookup lookup, boolean first,
                                    int selector) throws Exception {
                                lookup.findVirtual(ternary(first), "trace",
                                        MethodType.methodType(Object.class));
                                lookup.findVirtual(assigned(first), "trace",
                                        MethodType.methodType(Object.class));
                                lookup.findVirtual(switched(selector), "trace",
                                        MethodType.methodType(Object.class));
                                return Class.forName(name()).getDeclaredMethod("trace");
                            }
                            Class<?> ternary(boolean first) {
                                return first ? String.class : StringBuilder.class;
                            }
                            Class<?> assigned(boolean first) {
                                Class<?> result;
                                if (first) result = String.class;
                                else result = StringBuilder.class;
                                return result;
                            }
                            Class<?> switched(int selector) {
                                return switch (selector) {
                                    case 0 -> String.class;
                                    default -> StringBuilder.class;
                                };
                            }
                            String name() {
                                StringBuilder name = new StringBuilder("java.lang.");
                                name.append("String");
                                return name.toString();
                            }
                        }
                        """);
    }

    @Test
    void incompatibleHelpersCannotMaskStandardClassProducers() {
        String accessorDiagnostic =
                " getDeclaredMethod acquires active payload accessor trace on "
                        + PAYLOAD;
        assertAll(
                () -> assertExactSourceViolation(
                        "InheritedLoadClassAccessor.java:3" + accessorDiagnostic,
                        "InheritedLoadClassAccessor.java", """
                                class InheritedLoadClassAccessor extends ClassLoader {
                                    Object acquire() throws Exception {
                                        return loadClass("com.openggf.trace.replay.runs.ActiveSegmentPayload").getDeclaredMethod("trace");
                                    }
                                    Class<?> loadClass(int ignored) {
                                        return StringBuilder.class;
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "LookupFindClassAccessor.java:3" + accessorDiagnostic,
                        "LookupFindClassAccessor.java", """
                                class LookupFindClassAccessor {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.findClass("com.openggf.trace.replay.runs.ActiveSegmentPayload").getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "EnsureInitializedAccessor.java:3" + accessorDiagnostic,
                        "EnsureInitializedAccessor.java", """
                                class EnsureInitializedAccessor {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.ensureInitialized(ActiveSegmentPayload.class).getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "AccessClassAccessor.java:3" + accessorDiagnostic,
                        "AccessClassAccessor.java", """
                                class AccessClassAccessor {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.accessClass(ActiveSegmentPayload.class).getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "ComponentTypeAccessor.java:3" + accessorDiagnostic,
                        "ComponentTypeAccessor.java", """
                                class ComponentTypeAccessor {
                                    Object acquire() throws Exception {
                                        return ActiveSegmentPayload[].class.getComponentType().getDeclaredMethod("trace");
                                    }
                                }
                                """));
    }

    @Test
    void unrelatedClassProducersAndCompatibleHelpersRemainUnrestricted() {
        assertNoSourceViolation(
                "HarmlessClassProducers.java", """
                        class HarmlessClassProducers extends ClassLoader {
                            Object inspect(MethodHandles.Lookup lookup) throws Exception {
                                loadClass("java.lang.String").getDeclaredMethod("trace");
                                lookup.findClass("java.lang.String").getDeclaredMethod("trace");
                                lookup.ensureInitialized(StringBuilder.class)
                                        .getDeclaredMethod("trace");
                                lookup.accessClass(String.class).getDeclaredMethod("trace");
                                StringBuilder[].class.getComponentType()
                                        .getDeclaredMethod("trace");
                                return loadClass(7).getDeclaredMethod("trace");
                            }
                            Class<?> loadClass(int ignored) {
                                return StringBuilder.class;
                            }
                        }
                        """);
    }

    @Test
    void switchWithoutDefaultPreservesReachableRestrictedBaseline() {
        assertExactSourceViolation(
                "SwitchBaselineAccessor.java:3 findVirtual acquires active "
                        + "payload accessor trace on " + PAYLOAD,
                "SwitchBaselineAccessor.java", """
                        class SwitchBaselineAccessor {
                            Object acquire(MethodHandles.Lookup lookup, int selector) throws Exception {
                                return lookup.findVirtual(payloadType(selector), "trace", MethodType.methodType(TraceData.class));
                            }
                            Class<?> payloadType(int selector) {
                                Class<?> result = ActiveSegmentPayload.class;
                                switch (selector) {
                                    case 0 -> result = StringBuilder.class;
                                }
                                return result;
                            }
                        }
                        """);
    }

    @Test
    void harmlessAndDefaultExhaustiveSwitchesRemainUnrestricted() {
        assertNoSourceViolation(
                "HarmlessSwitchBaselines.java", """
                        class HarmlessSwitchBaselines {
                            Object inspect(MethodHandles.Lookup lookup, int selector)
                                    throws Exception {
                                lookup.findVirtual(harmlessBaseline(selector), "trace",
                                        MethodType.methodType(Object.class));
                                return lookup.findVirtual(exhaustive(selector), "trace",
                                        MethodType.methodType(Object.class));
                            }
                            Class<?> harmlessBaseline(int selector) {
                                Class<?> result = StringBuilder.class;
                                switch (selector) {
                                    case 0 -> result = String.class;
                                }
                                return result;
                            }
                            Class<?> exhaustive(int selector) {
                                Class<?> result = ActiveSegmentPayload.class;
                                switch (selector) {
                                    case 0 -> result = String.class;
                                    default -> result = StringBuilder.class;
                                }
                                return result;
                            }
                        }
                        """);
    }

    @Test
    void lookupTargetClassProducersAndBindCannotEraseAuthority() {
        String accessorDiagnostic =
                " getDeclaredMethod acquires active payload accessor trace on "
                        + PAYLOAD;
        assertAll(
                () -> assertExactSourceViolation(
                        "ComponentTypeSymmetryAccessor.java:3"
                                + accessorDiagnostic,
                        "ComponentTypeSymmetryAccessor.java", """
                                class ComponentTypeSymmetryAccessor {
                                    Object acquire() throws Exception {
                                        return ActiveSegmentPayload[].class.componentType().getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "LookupInClassAccessor.java:3" + accessorDiagnostic,
                        "LookupInClassAccessor.java", """
                                class LookupInClassAccessor {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.in(ActiveSegmentPayload.class).lookupClass().getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "PrivateLookupClassAccessor.java:3" + accessorDiagnostic,
                        "PrivateLookupClassAccessor.java", """
                                class PrivateLookupClassAccessor {
                                    Object acquire() throws Exception {
                                        return MethodHandles.privateLookupIn(ActiveSegmentPayload.class, MethodHandles.lookup()).lookupClass().getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "DroppedLookupClassAccessor.java:3" + accessorDiagnostic,
                        "DroppedLookupClassAccessor.java", """
                                class DroppedLookupClassAccessor {
                                    Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                        return lookup.in(ActiveSegmentPayload.class).dropLookupMode(1).lookupClass().getDeclaredMethod("trace");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "LookupBindAccessor.java:3 bind acquires active payload "
                                + "accessor trace on " + PAYLOAD,
                        "LookupBindAccessor.java", """
                                class LookupBindAccessor {
                                    Object acquire(MethodHandles.Lookup lookup, ActiveSegmentPayload payload) throws Exception {
                                        return lookup.bind(payload, "trace", MethodType.methodType(TraceData.class));
                                    }
                                }
                                """));
    }

    @Test
    void unrelatedLookupTargetsNamesAndCarriersRemainUnrestricted() {
        assertNoSourceViolation(
                "HarmlessLookupProvenance.java", """
                        class HarmlessLookupProvenance {
                            Object inspect(MethodHandles.Lookup lookup,
                                    ActiveSegmentPayload payload,
                                    StringBuilder unrelated, Carrier carrier)
                                    throws Exception {
                                StringBuilder[].class.componentType()
                                        .getDeclaredMethod("trace");
                                lookup.in(StringBuilder.class).lookupClass()
                                        .getDeclaredMethod("trace");
                                MethodHandles.privateLookupIn(String.class,
                                        MethodHandles.lookup()).lookupClass()
                                        .getDeclaredMethod("trace");
                                lookup.in(StringBuilder.class).dropLookupMode(1)
                                        .lookupClass().getDeclaredMethod("trace");
                                lookup.bind(unrelated, "trace",
                                        MethodType.methodType(Object.class));
                                lookup.bind(payload, "toString",
                                        MethodType.methodType(String.class));
                                carrier.in(ActiveSegmentPayload.class)
                                        .lookupClass().getDeclaredMethod("trace");
                                carrier.lookup().in(ActiveSegmentPayload.class)
                                        .lookupClass().getDeclaredMethod("trace");
                                carrier.publicLookup().in(ActiveSegmentPayload.class)
                                        .lookupClass().getDeclaredMethod("trace");
                                return carrier.privateLookupIn(
                                        ActiveSegmentPayload.class, carrier)
                                        .lookupClass().getDeclaredMethod("trace");
                            }
                            static class Carrier {
                                Carrier in(Class<?> ignored) {
                                    return this;
                                }
                                Carrier lookup() {
                                    return this;
                                }
                                Carrier publicLookup() {
                                    return this;
                                }
                                Carrier privateLookupIn(
                                        Class<?> ignored, Carrier other) {
                                    return this;
                                }
                                Class<?> lookupClass() {
                                    return StringBuilder.class;
                                }
                            }
                        }
                        """);
    }

    @Test
    void sourceGuardAllowsOnlyProvenHarmlessExactOwnerFields() {
        assertNoSourceViolation(
                "HarmlessOwnerFields.java", """
                        class HarmlessOwnerFields {
                            Object acquire(MethodHandles.Lookup lookup) throws Exception {
                                Field scalar = TraceSessionLauncher.class
                                        .getDeclaredField("runSpecialTimingRow");
                                Field label = AbstractRunChainTest.class
                                        .getDeclaredField("slotProbeRunId");
                                return List.of(scalar, label,
                                        lookup.findGetter(TraceSessionLauncher.class,
                                                "runSpecialTimingRow", int.class),
                                        lookup.findSetter(AbstractRunChainTest.class,
                                                "slotProbeRunId", String.class));
                            }
                        }
                        """);
    }

    @Test
    void onlyExactDedicatedTestsMayInspectOwnerFields() {
        assertAll(
                () -> assertNoSourceViolation(
                        "TestTraceRunActivePayloadPerformance.java", """
                                package com.openggf.tests.trace.runs;
                                class TestTraceRunActivePayloadPerformance {
                                    Object inspect() throws Exception {
                                        return TraceSessionLauncher.class
                                                .getDeclaredField("activeRunPayload");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "TraceSessionLauncher.java:4 getDeclaredField acquires "
                                + "field activeRunPayload on com.openggf."
                                + "TraceSessionLauncher",
                        "TraceSessionLauncher.java", """
                                package com.openggf;
                                class TraceSessionLauncher {
                                    Object inspect() throws Exception {
                                        return TraceSessionLauncher.class
                                                .getDeclaredField("activeRunPayload");
                                    }
                                }
                                """),
                () -> assertExactSourceViolation(
                        "AbstractRunChainTest.java:5 findGetter acquires field "
                                + "activeSegmentPayload on com.openggf.tests."
                                + "trace.runs.AbstractRunChainTest",
                        "AbstractRunChainTest.java", """
                                package com.openggf.tests.trace.runs;
                                class AbstractRunChainTest {
                                    Object inspect(MethodHandles.Lookup lookup)
                                            throws Exception {
                                        return lookup.findGetter(
                                                AbstractRunChainTest.class,
                                                "activeSegmentPayload",
                                                ActiveSegmentPayload.class);
                                    }
                                }
                                """));
    }

    @Test
    void sourceGuardDoesNotFailClosedForUnrelatedReflection() {
        assertNoSourceViolation(
                "UnrelatedReflection.java", """
                        class UnrelatedReflection {
                            Object acquire(Class<?> target, String type, String name)
                                    throws Exception {
                                Class.forName(type);
                                return target.getDeclaredMethod(name);
                            }
                        }
                        """);
        assertNoSourceViolation(
                "UnrelatedFieldEnumeration.java", """
                        class UnrelatedFieldEnumeration {
                            Object acquire(Class<?> target, String name) throws Exception {
                                for (Field field : target.getDeclaredFields()) {
                                    if (field.getName().equals(name)) {
                                        return field.get(null);
                                    }
                                }
                                return null;
                            }
                        }
                        """);
        assertNoSourceViolation(
                "UnrelatedFieldPrimitives.java", """
                        class UnrelatedFieldPrimitives {
                            Object acquire(Class<?> target, String name,
                                    MethodHandles.Lookup lookup, Field field)
                                    throws Exception {
                                target.getField(name);
                                target.getDeclaredField(name);
                                lookup.findGetter(target, name, Object.class);
                                lookup.findStaticGetter(target, name, Object.class);
                                lookup.findSetter(target, name, Object.class);
                                lookup.findStaticSetter(target, name, Object.class);
                                lookup.findVarHandle(target, name, Object.class);
                                lookup.findStaticVarHandle(target, name, Object.class);
                                lookup.unreflectGetter(field);
                                return lookup.unreflectSetter(field);
                            }
                        }
                        """);
    }

    @Test
    void repositorySourcesCannotAcquireTheLeaseReflectively() throws IOException {
        List<String> violations = new ArrayList<>();
        scanSourceTree(MAIN_SOURCES, violations);
        scanSourceTree(TEST_SOURCES, violations);

        assertEquals(List.of(), violations,
                "reflection and method handles must not bypass payload authority");
    }

    @Test
    void allowlistedOwnersCannotPublishPayloadRelayApis() {
        List<String> violations = new ArrayList<>();
        for (String ownerName : EXACT_CALLER_ALLOWLIST) {
            Class<?> owner = loadClass(ownerName);
            if (owner != null) {
                inspectRelaySurface(owner, violations);
            }
        }

        assertEquals(List.of(), violations,
                "approved callers may consume a lease but cannot relay its graph");
    }

    @Test
    void relayGuardRejectsEveryNestedPayloadGraphTypeShapeExactly() {
        assertAll(
                () -> assertExactRelayViolation(
                        LeaseRelayMutation.class, "method payload"),
                () -> assertExactRelayViolation(
                        TraceRelayMutation.class, "method trace"),
                () -> assertExactRelayViolation(
                        SpecialRowsRelayMutation.class, "method rows"),
                () -> assertExactRelayViolation(
                        ClassArrayRelayMutation.class, "field payloads"),
                () -> assertExactRelayViolation(
                        GenericArrayRelayMutation.class, "method traces"),
                () -> assertExactRelayViolation(
                        UpperWildcardRelayMutation.class, "method traces"),
                () -> assertExactRelayViolation(
                        LowerWildcardRelayMutation.class, "method traces"),
                () -> assertExactRelayViolation(
                        TypeVariableRelayMutation.class, "method rows"),
                () -> assertExactRelayViolation(
                        ErasedMultiHelperRelayMutation.class, "method relay"),
                () -> assertExactRelayViolation(
                        ErasedLeaseFieldRelayMutation.class, "method relay"),
                () -> assertExactRelayViolation(
                        ErasedRowsFieldRelayMutation.class, "method relay"),
                () -> assertExactRelayViolation(
                        ErasedWrapperFieldRelayMutation.class, "method relay"),
                () -> assertNoRelayViolation(UnrelatedFieldRelayControl.class));
    }

    private static JavaClasses importProjectClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeArchives())
                .importPackages("com.openggf");
    }

    private static List<String> unauthorizedPayloadCalls(
            JavaClasses classes, Set<String> ignoredOrigins) {
        return classes.stream()
                .flatMap(owner -> owner.getAccessesFromSelf().stream())
                .filter(TestActiveSegmentPayloadAuthorityGuard::targetsAcquisition)
                .filter(call -> !EXACT_CALLER_ALLOWLIST.contains(
                        call.getOriginOwner().getName()))
                .filter(call -> !ignoredOrigins.contains(
                        call.getOriginOwner().getName()))
                .map(JavaAccess::getDescription)
                .sorted()
                .distinct()
                .toList();
    }

    private static List<String> eagerPlanCalls(
            JavaClasses classes, Set<String> ignoredOrigins) {
        return classes.stream()
                .flatMap(owner -> owner.getAccessesFromSelf().stream())
                .filter(access -> access instanceof JavaMethodCall
                        || access instanceof JavaMethodReference)
                .filter(access -> WALKER.equals(access.getTargetOwner().getName()))
                .filter(access -> "plan".equals(access.getTarget().getName()))
                .filter(access -> !EAGER_PLAN_CALLER_ALLOWLIST.contains(
                        access.getOriginOwner().getName()))
                .filter(access -> !ignoredOrigins.contains(
                        access.getOriginOwner().getName()))
                .map(JavaAccess::getDescription)
                .sorted()
                .distinct()
                .toList();
    }

    private static boolean targetsAcquisition(JavaAccess<?> call) {
        if (!(call instanceof JavaMethodCall)
                && !(call instanceof JavaMethodReference)) {
            return false;
        }
        String owner = call.getTargetOwner().getName();
        String method = call.getTarget().getName();
        return (PAYLOAD.equals(owner) && PAYLOAD_ACCESSORS.contains(method))
                || (WALKER.equals(owner) && "openActiveSegment".equals(method));
    }

    private static void scanSourceTree(
            Path root, List<String> violations) throws IOException {
        try (Stream<Path> sources = Files.walk(root)) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String relative = root.relativize(source).toString()
                        .replace(source.getFileSystem().getSeparator(), "/");
                if (root.equals(TEST_SOURCES) && SELF_SOURCE.equals(relative)) {
                    continue;
                }
                violations.addAll(scanReflectiveAcquisition(
                        source.toString(), Files.readString(source)));
            }
        }
    }

    private static List<String> scanReflectiveAcquisition(
            String fileName, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null,
                "the authority source guard requires a JDK compiler");
        List<String> violations = new ArrayList<>();
        JavacTask task = (JavacTask) compiler.getTask(
                null, null, null, List.of("-proc:none"), null,
                List.of(new StringSourceFile(fileName, source)));
        try {
            Iterable<? extends CompilationUnitTree> units = task.parse();
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : units) {
                new ReflectiveAcquisitionScanner(
                        fileName, unit, positions, violations)
                        .scan(unit, null);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "could not parse authority source " + fileName, failure);
        }
        return List.copyOf(violations);
    }

    private static final class ReflectiveAcquisitionScanner
            extends TreePathScanner<Void, Void> {
        private static final int MAX_HELPER_RETURN_DEPTH = 32;
        private final String fileName;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final List<String> violations;
        private final Map<SourceMethodKey, List<SourceMethod>> sourceMethods =
                new HashMap<>();
        private final Deque<Map<String, KnownValue>> scopes = new ArrayDeque<>();
        private final Deque<String> sourceOrigins = new ArrayDeque<>();
        private final Set<MethodTree> activeHelperMethods = new HashSet<>();
        private final Set<MethodTree> activeHelperReturnMethods = new HashSet<>();
        private final Map<String, Map<String, KnownValue>> sourceFields =
                new HashMap<>();
        private final Map<String, Map<String, ExpressionTree>>
                sourceFieldInitializers = new HashMap<>();
        private final Set<String> activeFieldAliases = new HashSet<>();
        private final boolean staticClassForNameImported;
        private int methodDepth;

        private ReflectiveAcquisitionScanner(
                String fileName,
                CompilationUnitTree unit,
                SourcePositions positions,
                List<String> violations) {
            this.fileName = fileName;
            this.unit = unit;
            this.positions = positions;
            this.violations = violations;
            this.staticClassForNameImported = unit.getImports().stream()
                    .filter(importTree -> importTree.isStatic())
                    .map(importTree -> importTree.getQualifiedIdentifier().toString())
                    .anyMatch(imported -> imported.equals("java.lang.Class.forName")
                            || imported.equals("java.lang.Class.*"));
            scopes.push(new HashMap<>());
            collectSourceMethods();
        }

        private void collectSourceMethods() {
            new TreeScanner<Void, Void>() {
                private final Deque<String> owners = new ArrayDeque<>();

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    String owner = sourceOwner(owners, node);
                    for (Tree member : node.getMembers()) {
                        if (member instanceof VariableTree variable
                                && variable.getInitializer() != null) {
                            sourceFieldInitializers.computeIfAbsent(
                                    owner, ignored -> new HashMap<>())
                                    .put(variable.getName().toString(),
                                            variable.getInitializer());
                        }
                    }
                    owners.push(owner);
                    try {
                        return super.visitClass(node, unused);
                    } finally {
                        owners.pop();
                    }
                }

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    if (!owners.isEmpty()) {
                        SourceMethodKey key = new SourceMethodKey(
                                node.getName().toString(),
                                node.getParameters().size());
                        sourceMethods.computeIfAbsent(
                                key, ignored -> new ArrayList<>())
                                .add(new SourceMethod(owners.peek(), node));
                    }
                    return super.visitMethod(node, unused);
                }
            }.scan(unit, null);
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            String sourceOrigin = sourceOwner(sourceOrigins, node);
            sourceOrigins.push(sourceOrigin);
            scopes.push(new HashMap<>());
            try {
                return super.visitClass(node, unused);
            } finally {
                scopes.pop();
                sourceOrigins.pop();
            }
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            scopes.push(new HashMap<>());
            methodDepth++;
            try {
                for (VariableTree parameter : node.getParameters()) {
                    KnownValue type = typedTarget(parameter.getType());
                    scopes.peek().put(parameter.getName().toString(),
                            type != null ? type : KnownValue.unknown());
                }
                return super.visitMethod(node, unused);
            } finally {
                methodDepth--;
                scopes.pop();
            }
        }

        @Override
        public Void visitBlock(BlockTree node, Void unused) {
            scopes.push(new HashMap<>());
            try {
                return super.visitBlock(node, unused);
            } finally {
                scopes.pop();
            }
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            KnownValue value = evaluate(node.getInitializer());
            if (value == null) {
                value = typedTarget(node.getType());
            }
            value = value != null ? value : KnownValue.unknown();
            scopes.peek().put(node.getName().toString(), value);
            if (methodDepth == 0 && !sourceOrigins.isEmpty()) {
                sourceFields.computeIfAbsent(
                        sourceOrigins.peek(), ignored -> new HashMap<>())
                        .put(node.getName().toString(), value);
            }
            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitAssignment(AssignmentTree node, Void unused) {
            if (node.getVariable() instanceof IdentifierTree identifier) {
                KnownValue value = evaluate(node.getExpression());
                bind(identifier.getName().toString(),
                        value != null ? value : KnownValue.unknown());
            }
            return super.visitAssignment(node, unused);
        }

        @Override
        public Void visitMethodInvocation(
                MethodInvocationTree node, Void unused) {
            recordBuilderMutation(node);
            recordAcquisition(node);
            return super.visitMethodInvocation(node, unused);
        }

        private void recordBuilderMutation(MethodInvocationTree invocation) {
            if (!"append".equals(invokedName(invocation))
                    || !(receiver(invocation) instanceof IdentifierTree identifier)) {
                return;
            }
            KnownValue appended = evaluate(invocation);
            if (appended != null && appended.builder()) {
                bind(identifier.getName().toString(), appended);
            }
        }

        private void recordAcquisition(MethodInvocationTree invocation) {
            String primitive = invokedName(invocation);
            List<? extends ExpressionTree> arguments = invocation.getArguments();
            if (isSameSourceHelperCall(invocation)) {
                recordSameSourceHelperCall(invocation);
                return;
            }
            if (isClassForName(invocation)
                    && !arguments.isEmpty()) {
                String target = normalizeTargetName(
                        text(evaluate(arguments.getFirst())));
                if (isRestrictedTarget(target)) {
                    report(invocation, "Class.forName resolves active payload owner "
                            + target);
                }
                return;
            }
            if (("getMethods".equals(primitive)
                    || "getDeclaredMethods".equals(primitive))
                    && arguments.isEmpty()
                    && isClassReceiver(receiver(invocation))) {
                String target = targetName(evaluate(receiver(invocation)));
                if (isRestrictedTarget(target)) {
                    report(invocation, primitive
                            + " enumerates active payload accessors on " + target);
                }
                return;
            }
            if (("getFields".equals(primitive)
                    || "getDeclaredFields".equals(primitive))
                    && arguments.isEmpty()
                    && isClassReceiver(receiver(invocation))) {
                String target = targetName(evaluate(receiver(invocation)));
                if (isRestrictedFieldTarget(target)) {
                    report(invocation, primitive
                            + " enumerates active payload fields on " + target);
                }
                return;
            }
            if (("getMethod".equals(primitive)
                    || "getDeclaredMethod".equals(primitive))
                    && !arguments.isEmpty()
                    && isClassReceiver(receiver(invocation))) {
                recordAccessorAcquisition(invocation, primitive,
                        targetName(evaluate(receiver(invocation))),
                        text(evaluate(arguments.getFirst())));
                return;
            }
            if (("getField".equals(primitive)
                    || "getDeclaredField".equals(primitive))
                    && !arguments.isEmpty()
                    && isClassReceiver(receiver(invocation))) {
                recordFieldAcquisition(invocation, primitive,
                        targetName(evaluate(receiver(invocation))),
                        text(evaluate(arguments.getFirst())));
                return;
            }
            if (Set.of("findVirtual", "findStatic", "findSpecial")
                    .contains(primitive)
                    && arguments.size() >= 2) {
                recordAccessorAcquisition(invocation, primitive,
                        targetName(evaluate(arguments.get(0))),
                        text(evaluate(arguments.get(1))));
                return;
            }
            if ("bind".equals(primitive)
                    && arguments.size() >= 2
                    && isLookupReceiver(receiver(invocation))) {
                recordAccessorAcquisition(invocation, primitive,
                        targetName(evaluate(arguments.get(0))),
                        text(evaluate(arguments.get(1))));
                return;
            }
            if (Set.of(
                    "findGetter", "findSetter",
                    "findStaticGetter", "findStaticSetter",
                    "findVarHandle", "findStaticVarHandle")
                    .contains(primitive)
                    && arguments.size() >= 2) {
                recordFieldAcquisition(invocation, primitive,
                        targetName(evaluate(arguments.get(0))),
                        text(evaluate(arguments.get(1))));
                return;
            }
            recordSameSourceHelperCall(invocation);
        }

        private boolean isClassReceiver(ExpressionTree receiver) {
            KnownValue value = evaluate(receiver);
            return value != null && value.classObject();
        }

        private boolean isLookupReceiver(ExpressionTree receiver) {
            KnownValue value = evaluate(receiver);
            return value != null && value.lookup();
        }

        private boolean isSameSourceHelperCall(
                MethodInvocationTree invocation) {
            List<KnownValue> arguments = invocation.getArguments().stream()
                    .map(this::evaluate)
                    .map(value -> value != null ? value : KnownValue.unknown())
                    .toList();
            return !resolvedSourceMethods(invocation, arguments).isEmpty();
        }

        private void recordSameSourceHelperCall(
                MethodInvocationTree invocation) {
            List<? extends ExpressionTree> arguments = invocation.getArguments();
            List<KnownValue> values = arguments.stream()
                    .map(this::evaluate)
                    .map(value -> value != null ? value : KnownValue.unknown())
                    .toList();
            if (values.stream().map(TestActiveSegmentPayloadAuthorityGuard::targetName)
                    .noneMatch(target -> isRestrictedFieldTarget(target)
                            || isRestrictedTarget(target))) {
                return;
            }
            for (SourceMethod sourceMethod : resolvedSourceMethods(
                    invocation, values)) {
                MethodTree method = sourceMethod.method();
                if (!activeHelperMethods.add(method)) {
                    continue;
                }
                Map<String, KnownValue> bindings = new HashMap<>();
                for (int index = 0; index < values.size(); index++) {
                    KnownValue value = values.get(index);
                    if (value != null) {
                        bindings.put(method.getParameters().get(index)
                                .getName().toString(), value);
                    }
                }
                scopes.push(bindings);
                boolean pushedOwner = !sourceMethod.owner().equals(
                        sourceOrigins.peek());
                if (pushedOwner) {
                    sourceOrigins.push(sourceMethod.owner());
                }
                try {
                    scan(method.getBody(), null);
                } finally {
                    if (pushedOwner) {
                        sourceOrigins.pop();
                    }
                    scopes.pop();
                    activeHelperMethods.remove(method);
                }
            }
        }

        private boolean matchesHelperOwner(
                MethodInvocationTree invocation, String owner) {
            ExpressionTree helperReceiver = receiver(invocation);
            if (helperReceiver == null || "this".equals(helperReceiver.toString())) {
                return owner.equals(sourceOrigins.peek());
            }
            int lastDot = owner.lastIndexOf('.');
            String simpleOwner = lastDot < 0
                    ? owner : owner.substring(lastDot + 1);
            return simpleOwner.equals(helperReceiver.toString())
                    || owner.equals(helperReceiver.toString());
        }

        private void recordAccessorAcquisition(
                MethodInvocationTree invocation,
                String primitive,
                String target,
                String accessor) {
            if (!isRestrictedTarget(target)) {
                return;
            }
            if (accessor == null) {
                report(invocation, primitive
                        + " uses an unresolved accessor name on " + target);
            } else if (isAcquisition(target, accessor)) {
                report(invocation, primitive + " acquires active payload accessor "
                        + accessor + " on " + target);
            }
        }

        private void recordFieldAcquisition(
                MethodInvocationTree invocation,
                String primitive,
                String target,
                String fieldName) {
            if (!isRestrictedFieldTarget(target)) {
                return;
            }
            String sourceOrigin = sourceOrigins.peek();
            if (sourceOrigin != null
                    && EXACT_DEDICATED_FIELD_INSPECTORS.contains(sourceOrigin)) {
                return;
            }
            if (fieldName == null) {
                report(invocation, primitive
                        + " uses an unresolved field name on " + target);
            } else if (!isProvenNonGraphField(target, fieldName)) {
                report(invocation, primitive + " acquires field "
                        + fieldName + " on " + target);
            }
        }

        private KnownValue evaluate(ExpressionTree expression) {
            if (expression == null) {
                return null;
            }
            if (expression instanceof LiteralTree literal
                    && literal.getValue() instanceof String value) {
                return KnownValue.string(value);
            }
            if (expression instanceof ParenthesizedTree parenthesized) {
                return evaluate(parenthesized.getExpression());
            }
            if (expression instanceof TypeCastTree cast) {
                KnownValue value = evaluate(cast.getExpression());
                return value != null ? value : typedTarget(cast.getType());
            }
            if (expression instanceof ConditionalExpressionTree conditional) {
                return mergeKnownValues(List.of(
                        knownOrUnknown(evaluate(conditional.getTrueExpression())),
                        knownOrUnknown(evaluate(conditional.getFalseExpression()))));
            }
            if (expression instanceof SwitchExpressionTree switchExpression) {
                return evaluateSwitchExpression(switchExpression);
            }
            if (expression instanceof IdentifierTree identifier) {
                String name = identifier.getName().toString();
                KnownValue lexicalValue = lookup(name);
                if (lexicalValue != null) {
                    return lexicalValue;
                }
                return sourceOrigins.isEmpty() ? null
                        : lookupSourceField(sourceOrigins.peek(), name);
            }
            if (expression instanceof AssignmentTree assignment) {
                return evaluate(assignment.getExpression());
            }
            if (expression instanceof BinaryTree binary
                    && binary.getKind() == Tree.Kind.PLUS) {
                String left = text(evaluate(binary.getLeftOperand()));
                String right = text(evaluate(binary.getRightOperand()));
                return left != null && right != null
                        ? KnownValue.string(left + right) : null;
            }
            if (expression instanceof MemberSelectTree selection) {
                if ("class".contentEquals(selection.getIdentifier())) {
                    return classLiteralValue(selection.getExpression().toString());
                }
                KnownValue qualifiedAlias = lookupQualifiedAlias(selection);
                if (qualifiedAlias != null) {
                    return qualifiedAlias;
                }
            }
            if (expression instanceof NewClassTree newClass
                    && newClass.getIdentifier().toString()
                    .endsWith("StringBuilder")) {
                String initial = newClass.getArguments().isEmpty()
                        ? "" : text(evaluate(newClass.getArguments().getFirst()));
                return initial == null ? null : KnownValue.builder(initial);
            }
            if (expression instanceof MethodInvocationTree invocation) {
                return evaluateInvocation(invocation);
            }
            return null;
        }

        private KnownValue evaluateInvocation(MethodInvocationTree invocation) {
            String method = invokedName(invocation);
            List<? extends ExpressionTree> arguments = invocation.getArguments();
            if (isSameSourceHelperCall(invocation)) {
                return evaluateSameSourceHelperReturn(invocation);
            }
            KnownValue receiverValue = evaluate(receiver(invocation));
            if ("concat".equals(method) && arguments.size() == 1) {
                String prefix = text(receiverValue);
                String suffix = text(evaluate(arguments.getFirst()));
                return prefix != null && suffix != null
                        ? KnownValue.string(prefix + suffix) : null;
            }
            if (isStringJoin(invocation) && arguments.size() >= 2) {
                String delimiter = text(evaluate(arguments.getFirst()));
                if (delimiter == null) {
                    return null;
                }
                List<String> parts = new ArrayList<>();
                for (ExpressionTree argument : arguments.subList(
                        1, arguments.size())) {
                    String part = text(evaluate(argument));
                    if (part == null) {
                        return null;
                    }
                    parts.add(part);
                }
                return KnownValue.string(String.join(delimiter, parts));
            }
            if ("append".equals(method) && arguments.size() == 1
                    && receiverValue != null && receiverValue.builder()) {
                String suffix = text(evaluate(arguments.getFirst()));
                return suffix == null ? null
                        : KnownValue.builder(receiverValue.text() + suffix);
            }
            if ("toString".equals(method) && arguments.isEmpty()
                    && receiverValue != null && receiverValue.builder()) {
                return KnownValue.string(receiverValue.text());
            }
            if ("getClass".equals(method) && arguments.isEmpty()
                    && targetName(receiverValue) != null) {
                return KnownValue.classTarget(targetName(receiverValue));
            }
            if ("loadClass".equals(method) && arguments.size() == 1) {
                return KnownValue.classTarget(normalizeTargetName(
                        text(evaluate(arguments.getFirst()))));
            }
            if ("findClass".equals(method) && arguments.size() == 1) {
                if (receiverValue == null || !receiverValue.lookup()) {
                    return null;
                }
                return KnownValue.classTarget(normalizeTargetName(
                        text(evaluate(arguments.getFirst()))));
            }
            if (("ensureInitialized".equals(method)
                    || "accessClass".equals(method))
                    && arguments.size() == 1) {
                if (receiverValue == null || !receiverValue.lookup()) {
                    return null;
                }
                KnownValue argument = evaluate(arguments.getFirst());
                return argument != null && argument.classObject()
                        ? argument : null;
            }
            if (("getComponentType".equals(method)
                    || "componentType".equals(method))
                    && arguments.isEmpty()
                    && receiverValue != null && receiverValue.classObject()
                    && targetName(receiverValue) != null
                    && targetName(receiverValue).endsWith("[]")) {
                return KnownValue.classTarget(targetName(receiverValue)
                        .substring(0, targetName(receiverValue).length() - 2));
            }
            if ("privateLookupIn".equals(method) && arguments.size() >= 2
                    && isMethodHandlesReceiver(receiver(invocation))) {
                KnownValue target = evaluate(arguments.getFirst());
                return target != null && target.classObject()
                        ? KnownValue.lookup(targetName(target))
                        : KnownValue.lookup(null);
            }
            if (("lookup".equals(method) || "publicLookup".equals(method))
                    && arguments.isEmpty()
                    && isMethodHandlesReceiver(receiver(invocation))) {
                return KnownValue.lookup(null);
            }
            if ("in".equals(method) && arguments.size() == 1
                    && receiverValue != null && receiverValue.lookup()) {
                KnownValue target = evaluate(arguments.getFirst());
                return target != null && target.classObject()
                        ? KnownValue.lookup(targetName(target))
                        : KnownValue.lookup(null);
            }
            if ("dropLookupMode".equals(method) && arguments.size() == 1
                    && receiverValue != null && receiverValue.lookup()) {
                return receiverValue;
            }
            if ("lookupClass".equals(method) && arguments.isEmpty()
                    && receiverValue != null && receiverValue.lookup()) {
                return KnownValue.classTarget(receiverValue.lookupTargetName());
            }
            if ("asSubclass".equals(method) && arguments.size() == 1
                    && receiverValue != null && receiverValue.classObject()) {
                return receiverValue;
            }
            if ("getNestHost".equals(method) && arguments.isEmpty()
                    && receiverValue != null && receiverValue.classObject()) {
                return receiverValue;
            }
            if (isClassForName(invocation) && !arguments.isEmpty()) {
                return KnownValue.classTarget(normalizeTargetName(
                        text(evaluate(arguments.getFirst()))));
            }
            return null;
        }

        private boolean isMethodHandlesReceiver(ExpressionTree receiver) {
            return receiver != null && Set.of(
                    "MethodHandles",
                    "java.lang.invoke.MethodHandles")
                    .contains(receiver.toString());
        }

        private KnownValue classLiteralValue(String typeName) {
            String normalized = normalizeTargetName(typeName);
            if (normalized != null) {
                return KnownValue.classTarget(normalized);
            }
            if (!typeName.endsWith("[]")) {
                return null;
            }
            String component = normalizeTargetName(
                    typeName.substring(0, typeName.length() - 2));
            return component == null ? null
                    : KnownValue.classTarget(component + "[]");
        }

        private KnownValue evaluateSwitchExpression(
                SwitchExpressionTree switchExpression) {
            List<KnownValue> alternatives = new ArrayList<>();
            for (var switchCase : switchExpression.getCases()) {
                Tree body = switchCase.getBody();
                if (body instanceof ExpressionTree expression) {
                    alternatives.add(knownOrUnknown(evaluate(expression)));
                    continue;
                }
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitYield(YieldTree node, Void unused) {
                        alternatives.add(knownOrUnknown(
                                evaluate(node.getValue())));
                        return null;
                    }

                    @Override
                    public Void visitClass(ClassTree node, Void unused) {
                        return null;
                    }
                }.scan(body != null ? body : switchCase, null);
            }
            return mergeKnownValues(alternatives);
        }

        private KnownValue evaluateSameSourceHelperReturn(
                MethodInvocationTree invocation) {
            if (activeHelperReturnMethods.size() >= MAX_HELPER_RETURN_DEPTH) {
                return KnownValue.unknown();
            }
            List<KnownValue> arguments = invocation.getArguments().stream()
                    .map(this::evaluate)
                    .map(value -> value != null ? value : KnownValue.unknown())
                    .toList();
            List<KnownValue> returns = new ArrayList<>();
            for (SourceMethod sourceMethod : resolvedSourceMethods(
                    invocation, arguments)) {
                MethodTree method = sourceMethod.method();
                if (!activeHelperReturnMethods.add(method)) {
                    returns.add(KnownValue.unknown());
                    continue;
                }
                Map<String, KnownValue> bindings = new HashMap<>();
                for (int index = 0; index < arguments.size(); index++) {
                    bindings.put(method.getParameters().get(index)
                            .getName().toString(), arguments.get(index));
                }
                scopes.push(bindings);
                boolean pushedOwner = !sourceMethod.owner().equals(
                        sourceOrigins.peek());
                if (pushedOwner) {
                    sourceOrigins.push(sourceMethod.owner());
                }
                try {
                    collectHelperReturns(method.getBody(), returns);
                } finally {
                    if (pushedOwner) {
                        sourceOrigins.pop();
                    }
                    scopes.pop();
                    activeHelperReturnMethods.remove(method);
                }
            }
            return mergeHelperReturns(returns);
        }

        private void collectHelperReturns(
                BlockTree body, List<KnownValue> returns) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitBlock(BlockTree node, Void unused) {
                    scopes.push(new HashMap<>());
                    try {
                        return super.visitBlock(node, unused);
                    } finally {
                        scopes.pop();
                    }
                }

                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    KnownValue value = evaluate(node.getInitializer());
                    if (value == null) {
                        value = typedTarget(node.getType());
                    }
                    scopes.peek().put(node.getName().toString(),
                            value != null ? value : KnownValue.unknown());
                    return null;
                }

                @Override
                public Void visitAssignment(AssignmentTree node, Void unused) {
                    if (node.getVariable() instanceof IdentifierTree identifier) {
                        KnownValue value = evaluate(node.getExpression());
                        bind(identifier.getName().toString(), value != null
                                ? value : KnownValue.unknown());
                    }
                    return null;
                }

                @Override
                public Void visitMethodInvocation(
                        MethodInvocationTree node, Void unused) {
                    recordBuilderMutation(node);
                    return super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitIf(IfTree node, Void unused) {
                    scan(node.getCondition(), unused);
                    List<Map<String, KnownValue>> baseline = snapshotScopes();
                    restoreScopes(baseline);
                    scan(node.getThenStatement(), unused);
                    List<Map<String, KnownValue>> thenState = snapshotScopes();
                    restoreScopes(baseline);
                    if (node.getElseStatement() != null) {
                        scan(node.getElseStatement(), unused);
                    }
                    List<Map<String, KnownValue>> elseState = snapshotScopes();
                    restoreScopes(mergeScopeStates(thenState, elseState));
                    return null;
                }

                @Override
                public Void visitSwitch(SwitchTree node, Void unused) {
                    scan(node.getExpression(), unused);
                    List<Map<String, KnownValue>> baseline = snapshotScopes();
                    List<List<Map<String, KnownValue>>> branchStates =
                            new ArrayList<>();
                    for (var switchCase : node.getCases()) {
                        restoreScopes(baseline);
                        Tree caseBody = switchCase.getBody();
                        if (caseBody != null) {
                            scan(caseBody, unused);
                        } else {
                            scan(switchCase.getStatements(), unused);
                        }
                        branchStates.add(snapshotScopes());
                    }
                    boolean hasDefault = node.getCases().stream()
                            .flatMap(switchCase -> switchCase.getLabels().stream())
                            .anyMatch(label -> label.getKind()
                                    == Tree.Kind.DEFAULT_CASE_LABEL);
                    if (!hasDefault) {
                        branchStates.add(baseline);
                    }
                    if (branchStates.isEmpty()) {
                        restoreScopes(baseline);
                    } else {
                        restoreScopes(mergeScopeStates(branchStates));
                    }
                    return null;
                }

                @Override
                public Void visitReturn(ReturnTree node, Void unused) {
                    KnownValue value = evaluate(node.getExpression());
                    returns.add(value != null ? value : KnownValue.unknown());
                    return null;
                }

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    return null;
                }
            }.scan(body, null);
        }

        private KnownValue mergeHelperReturns(List<KnownValue> returns) {
            return mergeKnownValues(returns);
        }

        private KnownValue mergeKnownValues(List<KnownValue> values) {
            List<KnownValue> knownValues = values.stream()
                    .map(this::knownOrUnknown)
                    .toList();
            if (knownValues.isEmpty()) {
                return KnownValue.unknown();
            }
            KnownValue first = knownValues.getFirst();
            if (knownValues.stream().allMatch(first::equals)) {
                return first;
            }
            return knownValues.stream()
                    .filter(this::isSensitiveHelperValue)
                    .max((left, right) -> Integer.compare(
                            helperValueRank(left), helperValueRank(right)))
                    .orElseGet(KnownValue::unknown);
        }

        private KnownValue knownOrUnknown(KnownValue value) {
            return value != null ? value : KnownValue.unknown();
        }

        private List<Map<String, KnownValue>> snapshotScopes() {
            List<Map<String, KnownValue>> snapshot = new ArrayList<>();
            scopes.forEach(scope -> snapshot.add(new HashMap<>(scope)));
            return snapshot;
        }

        private void restoreScopes(List<Map<String, KnownValue>> state) {
            scopes.clear();
            state.forEach(scope -> scopes.addLast(new HashMap<>(scope)));
        }

        @SafeVarargs
        private final List<Map<String, KnownValue>> mergeScopeStates(
                List<Map<String, KnownValue>>... states) {
            return mergeScopeStates(List.of(states));
        }

        private List<Map<String, KnownValue>> mergeScopeStates(
                List<List<Map<String, KnownValue>>> states) {
            if (states.isEmpty()) {
                return snapshotScopes();
            }
            List<Map<String, KnownValue>> merged = new ArrayList<>();
            for (int scopeIndex = 0;
                    scopeIndex < states.getFirst().size(); scopeIndex++) {
                Set<String> names = new HashSet<>();
                for (List<Map<String, KnownValue>> state : states) {
                    names.addAll(state.get(scopeIndex).keySet());
                }
                Map<String, KnownValue> mergedScope = new HashMap<>();
                int currentScopeIndex = scopeIndex;
                for (String name : names) {
                    List<KnownValue> alternatives = states.stream()
                            .map(state -> state.get(currentScopeIndex)
                                    .getOrDefault(name, KnownValue.unknown()))
                            .toList();
                    mergedScope.put(name, mergeKnownValues(alternatives));
                }
                merged.add(mergedScope);
            }
            return merged;
        }

        private boolean isSensitiveHelperValue(KnownValue value) {
            return helperValueRank(value) > 0;
        }

        private int helperValueRank(KnownValue value) {
            String target = targetName(value);
            if (PAYLOAD.equals(target)) {
                return 50;
            }
            if (WALKER.equals(target)) {
                return 40;
            }
            if (isRestrictedFieldTarget(target)) {
                return 30;
            }
            String lookupTarget = value.lookupTargetName();
            if (PAYLOAD.equals(lookupTarget)) {
                return 45;
            }
            if (WALKER.equals(lookupTarget)) {
                return 35;
            }
            if (isRestrictedFieldTarget(lookupTarget)) {
                return 25;
            }
            String valueText = text(value);
            String textTarget = normalizeTargetName(valueText);
            if (PAYLOAD.equals(textTarget)) {
                return 25;
            }
            if (WALKER.equals(textTarget)) {
                return 20;
            }
            if (isRestrictedFieldTarget(textTarget)) {
                return 15;
            }
            if (valueText != null && (PAYLOAD_ACCESSORS.contains(valueText)
                    || "openActiveSegment".equals(valueText))) {
                return 10;
            }
            return 0;
        }

        private List<SourceMethod> resolvedSourceMethods(
                MethodInvocationTree invocation, List<KnownValue> arguments) {
            SourceMethodKey key = new SourceMethodKey(
                    invokedName(invocation), invocation.getArguments().size());
            List<SourceMethod> candidates = sourceMethods
                    .getOrDefault(key, List.of()).stream()
                    .filter(method -> method.method().getBody() != null
                            && matchesHelperOwner(invocation, method.owner()))
                    .toList();
            if (candidates.isEmpty()) {
                return candidates;
            }
            Map<SourceMethod, Integer> scores = new HashMap<>();
            int bestScore = Integer.MIN_VALUE;
            for (SourceMethod candidate : candidates) {
                int score = sourceMethodMatchScore(
                        invocation, arguments, candidate.method());
                scores.put(candidate, score);
                bestScore = Math.max(bestScore, score);
            }
            if (bestScore < 0) {
                return List.of();
            }
            int selectedScore = bestScore;
            return candidates.stream()
                    .filter(candidate -> scores.get(candidate) == selectedScore)
                    .toList();
        }

        private int sourceMethodMatchScore(
                MethodInvocationTree invocation,
                List<KnownValue> arguments,
                MethodTree method) {
            int score = 0;
            for (int index = 0; index < arguments.size(); index++) {
                int argumentScore = argumentMatchScore(
                        invocation.getArguments().get(index),
                        arguments.get(index),
                        method.getParameters().get(index).getType().toString());
                if (argumentScore < 0) {
                    return -1;
                }
                score += argumentScore;
            }
            return score;
        }

        private int argumentMatchScore(
                ExpressionTree expression,
                KnownValue value,
                String declaredType) {
            String type = simpleRawType(declaredType);
            if (value.classObject()) {
                return "Class".equals(type) ? 4
                        : "Object".equals(type) ? 1 : -1;
            }
            if (text(value) != null) {
                return "String".equals(type) ? 4
                        : "CharSequence".equals(type) ? 3
                        : "Object".equals(type) ? 1 : -1;
            }
            if (expression instanceof LiteralTree literal) {
                Object literalValue = literal.getValue();
                if (literalValue instanceof Boolean) {
                    return Set.of("boolean", "Boolean").contains(type)
                            ? 4 : "Object".equals(type) ? 1 : -1;
                }
                if (literalValue instanceof Number) {
                    return Set.of(
                            "byte", "short", "int", "long", "float", "double",
                            "Byte", "Short", "Integer", "Long", "Float", "Double")
                            .contains(type) ? 4
                            : "Number".equals(type) ? 2
                            : "Object".equals(type) ? 1 : -1;
                }
            }
            String target = targetName(value);
            if (target != null) {
                int lastDot = target.lastIndexOf('.');
                String simpleTarget = lastDot < 0
                        ? target : target.substring(lastDot + 1);
                return simpleTarget.equals(type) ? 4
                        : "Object".equals(type) ? 1 : -1;
            }
            return 0;
        }

        private String simpleRawType(String declaredType) {
            int genericStart = declaredType.indexOf('<');
            String rawType = genericStart < 0
                    ? declaredType : declaredType.substring(0, genericStart);
            int lastDot = rawType.lastIndexOf('.');
            return lastDot < 0 ? rawType : rawType.substring(lastDot + 1);
        }

        private boolean isStringJoin(MethodInvocationTree invocation) {
            ExpressionTree receiver = receiver(invocation);
            return "join".equals(invokedName(invocation))
                    && receiver != null
                    && Set.of("String", "java.lang.String")
                    .contains(receiver.toString());
        }

        private boolean isClassForName(MethodInvocationTree invocation) {
            ExpressionTree receiver = receiver(invocation);
            return "forName".equals(invokedName(invocation))
                    && ((receiver != null
                    && Set.of("Class", "java.lang.Class")
                    .contains(receiver.toString()))
                    || (receiver == null && staticClassForNameImported));
        }

        private void report(Tree tree, String message) {
            long position = positions.getStartPosition(unit, tree);
            long line = unit.getLineMap().getLineNumber(position);
            String diagnostic = fileName + ":" + line + " " + message;
            if (!violations.contains(diagnostic)) {
                violations.add(diagnostic);
            }
        }

        private KnownValue typedTarget(Tree type) {
            if (type == null) {
                return null;
            }
            if (Set.of(
                    "MethodHandles.Lookup",
                    "java.lang.invoke.MethodHandles.Lookup",
                    "Lookup").contains(type.toString())) {
                return KnownValue.lookup(null);
            }
            return KnownValue.target(normalizeTargetName(type.toString()));
        }

        private KnownValue lookupQualifiedAlias(MemberSelectTree selection) {
            String qualifier = selection.getExpression().toString();
            String owner = matchingSourceOwner(qualifier);
            if (owner == null) {
                return null;
            }
            return lookupSourceField(
                    owner, selection.getIdentifier().toString());
        }

        private KnownValue lookupSourceField(
                String owner, String fieldName) {
            KnownValue bound = sourceFields.getOrDefault(owner, Map.of())
                    .get(fieldName);
            if (bound != null) {
                return bound;
            }
            ExpressionTree initializer = sourceFieldInitializers
                    .getOrDefault(owner, Map.of()).get(fieldName);
            String aliasKey = owner + "#" + fieldName;
            if (initializer == null || !activeFieldAliases.add(aliasKey)) {
                return null;
            }
            try {
                return evaluate(initializer);
            } finally {
                activeFieldAliases.remove(aliasKey);
            }
        }

        private String matchingSourceOwner(String qualifier) {
            if ("this".equals(qualifier) && !sourceOrigins.isEmpty()) {
                return sourceOrigins.peek();
            }
            for (String owner : sourceOrigins) {
                if (matchesOwnerQualifier(owner, qualifier)) {
                    return owner;
                }
            }
            for (String owner : sourceFieldInitializers.keySet()) {
                if (matchesOwnerQualifier(owner, qualifier)) {
                    return owner;
                }
            }
            return null;
        }

        private boolean matchesOwnerQualifier(
                String owner, String qualifier) {
            int lastDot = owner.lastIndexOf('.');
            String simpleOwner = lastDot < 0
                    ? owner : owner.substring(lastDot + 1);
            return qualifier.equals(owner)
                    || qualifier.equals(simpleOwner)
                    || qualifier.equals(simpleOwner.replace('$', '.'));
        }

        private String sourceOwner(
                Deque<String> owners, ClassTree node) {
            String simpleName = node.getSimpleName().toString();
            if (!owners.isEmpty()) {
                return owners.peek() + "$" + simpleName;
            }
            String packageName = unit.getPackageName() == null
                    ? "" : unit.getPackageName().toString();
            return packageName.isEmpty()
                    ? simpleName : packageName + "." + simpleName;
        }

        private KnownValue lookup(String name) {
            for (Map<String, KnownValue> scope : scopes) {
                KnownValue value = scope.get(name);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }

        private void bind(String name, KnownValue value) {
            for (Map<String, KnownValue> scope : scopes) {
                if (scope.containsKey(name)) {
                    scope.put(name, value);
                    return;
                }
            }
            scopes.peek().put(name, value);
        }

        private static String invokedName(MethodInvocationTree invocation) {
            ExpressionTree select = invocation.getMethodSelect();
            if (select instanceof MemberSelectTree member) {
                return member.getIdentifier().toString();
            }
            if (select instanceof IdentifierTree identifier) {
                return identifier.getName().toString();
            }
            return "";
        }

        private static ExpressionTree receiver(
                MethodInvocationTree invocation) {
            return invocation.getMethodSelect() instanceof MemberSelectTree member
                    ? member.getExpression() : null;
        }
    }

    private record SourceMethodKey(String name, int parameterCount) { }

    private record SourceMethod(String owner, MethodTree method) { }

    private record KnownValue(
            String text,
            String targetName,
            String lookupTargetName,
            boolean builder,
            boolean classObject,
            boolean lookup) {
        private static KnownValue string(String value) {
            return value == null ? null
                    : new KnownValue(value, null, null, false, false, false);
        }

        private static KnownValue target(String value) {
            return value == null ? null
                    : new KnownValue(null, value, null, false, false, false);
        }

        private static KnownValue classTarget(String value) {
            return value == null ? null
                    : new KnownValue(null, value, null, false, true, false);
        }

        private static KnownValue builder(String value) {
            return value == null ? null
                    : new KnownValue(value, null, null, true, false, false);
        }

        private static KnownValue lookup(String targetName) {
            return new KnownValue(
                    null, null, targetName, false, false, true);
        }

        private static KnownValue unknown() {
            return new KnownValue(null, null, null, false, false, false);
        }
    }

    private static final class StringSourceFile extends SimpleJavaFileObject {
        private final String source;

        private StringSourceFile(String fileName, String source) {
            super(URI.create("string:///" + fileName.replace('\\', '/')),
                    Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static String text(KnownValue value) {
        return value == null ? null : value.text();
    }

    private static String targetName(KnownValue value) {
        return value == null ? null : value.targetName();
    }

    private static String normalizeTargetName(String candidate) {
        if (candidate == null) {
            return null;
        }
        if (candidate.equals(PAYLOAD)
                || candidate.equals(ActiveSegmentPayload.class.getSimpleName())) {
            return PAYLOAD;
        }
        if (candidate.equals(WALKER)
                || candidate.equals(TraceRunReplayWalker.class.getSimpleName())) {
            return WALKER;
        }
        for (String target : EXACT_CALLER_ALLOWLIST) {
            int lastDot = target.lastIndexOf('.');
            String simpleName = lastDot < 0
                    ? target : target.substring(lastDot + 1);
            if (candidate.equals(target) || candidate.equals(simpleName)) {
                return target;
            }
        }
        return null;
    }

    private static boolean isAcquisition(String target, String accessor) {
        return accessor != null
                && ((PAYLOAD.equals(target)
                && PAYLOAD_ACCESSORS.contains(accessor))
                || (WALKER.equals(target)
                && "openActiveSegment".equals(accessor)));
    }

    private static boolean isRestrictedTarget(String target) {
        return PAYLOAD.equals(target) || WALKER.equals(target);
    }

    private static boolean isRestrictedFieldTarget(String target) {
        return target != null
                && RESTRICTED_FIELD_TARGETS.contains(target);
    }

    private static boolean isProvenNonGraphField(
            String target, String fieldName) {
        Class<?> owner = loadClass(target);
        for (Class<?> current = owner;
             current != null;
             current = current.getSuperclass()) {
            try {
                return !containsPayloadGraph(current
                        .getDeclaredField(fieldName).getGenericType());
            } catch (NoSuchFieldException ignored) {
                // Continue through the declared owner hierarchy.
            }
        }
        return false;
    }

    private static void assertExactSourceViolation(
            String expected, String fileName, String source) {
        List<String> violations = scanReflectiveAcquisition(fileName, source);
        assertEquals(List.of(expected), violations);
    }

    private static void assertExactSourceViolations(
            List<String> expected, String fileName, String source) {
        assertEquals(expected, scanReflectiveAcquisition(fileName, source));
    }

    private static void assertNoSourceViolation(
            String fileName, String source) {
        assertEquals(List.of(), scanReflectiveAcquisition(fileName, source));
    }

    private static void assertExactRelayViolation(
            Class<?> owner, String memberDescription) {
        List<String> violations = new ArrayList<>();
        inspectRelaySurface(owner, violations);
        assertEquals(List.of(owner.getName() + " " + memberDescription),
                violations);
    }

    private static void assertNoRelayViolation(Class<?> owner) {
        List<String> violations = new ArrayList<>();
        inspectRelaySurface(owner, violations);
        assertEquals(List.of(), violations);
    }

    private static void inspectRelaySurface(
            Class<?> owner, List<String> violations) {
        for (Field field : owner.getDeclaredFields()) {
            if (isPublicOrProtected(field.getModifiers())
                    && containsPayloadGraph(field.getGenericType())) {
                violations.add(owner.getName() + " field " + field.getName());
            }
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (isPublicOrProtected(method.getModifiers())
                    && (containsPayloadGraph(method.getGenericReturnType())
                    || isErasedPayloadRelay(owner, method))) {
                violations.add(owner.getName() + " method " + method.getName());
            }
        }
    }

    private static boolean isErasedPayloadRelay(Class<?> owner, Method method) {
        if (method.getReturnType() != Object.class) {
            return false;
        }
        JavaClass importedOwner = new ClassFileImporter()
                .importClasses(owner)
                .get(owner);
        JavaMethod importedMethod = importedOwner.getMethod(
                method.getName(), method.getParameterTypes());
        return reachesRestrictedAccessor(
                importedOwner.getName(), importedMethod, new HashSet<>());
    }

    private static boolean reachesRestrictedAccessor(
            String ownerName,
            JavaMethod method,
            Set<String> inspectedMethods) {
        if (!inspectedMethods.add(method.getFullName())) {
            return false;
        }
        for (JavaFieldAccess access : method.getFieldAccesses()) {
            if (access.getAccessType() == JavaFieldAccess.AccessType.GET
                    && ownerName.equals(access.getTargetOwner().getName())
                    && access.getTarget().resolveMember().isPresent()
                    && containsPayloadGraph(access.getTarget().resolveMember()
                    .orElseThrow().reflect().getGenericType())) {
                return true;
            }
        }
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
            String targetOwner = call.getTargetOwner().getName();
            String targetMethod = call.getTarget().getName();
            if ((PAYLOAD.equals(targetOwner)
                    && PAYLOAD_ACCESSORS.contains(targetMethod))
                    || (WALKER.equals(targetOwner)
                    && "openActiveSegment".equals(targetMethod))) {
                return true;
            }
            if (ownerName.equals(targetOwner)
                    && call.getTarget().resolveMember().isPresent()
                    && reachesRestrictedAccessor(ownerName,
                    call.getTarget().resolveMember().orElseThrow(),
                    inspectedMethods)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPayloadGraph(Type type) {
        return containsPayloadGraph(type, new HashSet<>());
    }

    private static boolean containsPayloadGraph(
            Type type, Set<Type> inspected) {
        if (type == null || !inspected.add(type)) {
            return false;
        }
        if (type instanceof Class<?> typeClass) {
            if (typeClass.isArray()) {
                return containsPayloadGraph(
                        typeClass.getComponentType(), inspected);
            }
            return PAYLOAD_GRAPH_TYPES.stream()
                    .anyMatch(payloadType -> payloadType.isAssignableFrom(typeClass));
        }
        if (type instanceof ParameterizedType parameterized) {
            return containsPayloadGraph(parameterized.getOwnerType(), inspected)
                    || containsPayloadGraph(parameterized.getRawType(), inspected)
                    || Arrays.stream(parameterized.getActualTypeArguments())
                    .anyMatch(argument -> containsPayloadGraph(
                            argument, inspected));
        }
        if (type instanceof GenericArrayType array) {
            return containsPayloadGraph(
                    array.getGenericComponentType(), inspected);
        }
        if (type instanceof WildcardType wildcard) {
            return Arrays.stream(wildcard.getUpperBounds())
                    .anyMatch(bound -> containsPayloadGraph(bound, inspected))
                    || Arrays.stream(wildcard.getLowerBounds())
                    .anyMatch(bound -> containsPayloadGraph(bound, inspected));
        }
        if (type instanceof TypeVariable<?> variable) {
            return Arrays.stream(variable.getBounds())
                    .anyMatch(bound -> containsPayloadGraph(bound, inspected));
        }
        return false;
    }

    private static boolean isPublicOrProtected(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String methodSignature(Method method) {
        return method.getName() + "("
                + Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(java.util.stream.Collectors.joining(","))
                + "):" + method.getReturnType().getName();
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException failure) {
            return null;
        }
    }

    private static final class UnauthorizedDirectCall {
        private TraceData acquire(ActiveSegmentPayload payload) {
            return payload.trace();
        }
    }

    private static final class UnauthorizedMethodReference {
        private Function<ActiveSegmentPayload, TraceData> acquire() {
            return ActiveSegmentPayload::trace;
        }
    }

    private static final class UnauthorizedOpenHelper {
        private ActiveSegmentPayload acquire(
                TraceRunSegmentDescriptor descriptor) throws IOException {
            return TraceRunReplayWalker.openActiveSegment(descriptor, 0);
        }
    }

    private static final class UnauthorizedEagerPlan {
        private List<TraceRunReplayWalker.SegmentPlan> plan(
                TraceRunManifest manifest, Path directory) throws IOException {
            return TraceRunReplayWalker.plan(manifest, directory);
        }
    }

    private static final class LeaseRelayMutation {
        public ActiveSegmentPayload payload() {
            return null;
        }
    }

    private static final class TraceRelayMutation {
        protected TraceData trace() {
            return null;
        }
    }

    private static final class SpecialRowsRelayMutation {
        public TraceRunSpecialStageRows rows() {
            return null;
        }
    }

    private static final class ClassArrayRelayMutation {
        public ActiveSegmentPayload[] payloads;
    }

    private static final class GenericArrayRelayMutation {
        public List<TraceData>[] traces() {
            return null;
        }
    }

    private static final class UpperWildcardRelayMutation {
        public List<? extends TraceData> traces() {
            return null;
        }
    }

    private static final class LowerWildcardRelayMutation {
        public List<? super TraceData> traces() {
            return null;
        }
    }

    private static final class TypeVariableRelayMutation {
        public <T extends TraceRunSpecialStageRows> T rows() {
            return null;
        }
    }

    private static final class ErasedMultiHelperRelayMutation {
        private ActiveSegmentPayload payload;

        public Object relay() {
            return packageRelay();
        }

        Object packageRelay() {
            return privateRelay();
        }

        private Object privateRelay() {
            return payload.trace();
        }
    }

    private static final class ErasedLeaseFieldRelayMutation {
        private ActiveSegmentPayload activeRunPayload;

        public Object relay() {
            return privateLease();
        }

        private ActiveSegmentPayload privateLease() {
            return activeRunPayload;
        }
    }

    private static final class ErasedRowsFieldRelayMutation {
        private TraceRunSpecialStageRows rows;

        public Object relay() {
            return packageRows();
        }

        Object packageRows() {
            return privateRows();
        }

        private Object privateRows() {
            return rows;
        }
    }

    private static final class ErasedWrapperFieldRelayMutation {
        private List<TraceData> traces;

        public Object relay() {
            return privateWrapper();
        }

        private Object privateWrapper() {
            return traces;
        }
    }

    private static final class UnrelatedFieldRelayControl {
        private String label;

        public Object relay() {
            return privateLabel();
        }

        private String privateLabel() {
            return label;
        }
    }
}
