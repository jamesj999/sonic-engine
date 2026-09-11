package com.openggf.level.objects;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestObjectUpdateClockTerminologyGuard {
    private static final String EXPECTED_NAME = "vIntRunCount";
    private static final String PLAYABLE_ENTITY = "com.openggf.game.PlayableEntity";
    private static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("src", "main", "java"),
            Path.of("src", "test", "java"));
    private static final List<MethodSeed> HOOK_SEEDS = List.of(
            new MethodSeed("com.openggf.level.objects.AbstractBadnikInstance", "updateMovement", 2),
            new MethodSeed("com.openggf.level.objects.AbstractBadnikInstance", "updateAnimation", 1),
            new MethodSeed("com.openggf.level.objects.AbstractProjectileInstance", "updateExtra", 2),
            new MethodSeed("com.openggf.level.objects.boss.AbstractBossInstance", "updateBossLogic", 2),
            new MethodSeed("com.openggf.level.objects.boss.AbstractBossInstance", "updateOwnerManagedChildren", 2),
            new MethodSeed("com.openggf.level.objects.boss.AbstractBossChild", "beginUpdate", 1),
            new MethodSeed("com.openggf.level.objects.boss.AbstractBossChild", "shouldUpdate", 1),
            new MethodSeed(
                    "com.openggf.level.objects.boss.AbstractBossInstance.BossDefeatSequencer",
                    "update",
                    1));
    private static final List<FieldSeed> RETAINED_CLOCK_FIELDS = List.of(
            new FieldSeed("com.openggf.level.objects.boss.BossStateContext",
                    "lastUpdatedVIntRunCount", "lastUpdatedFrame"),
            new FieldSeed("com.openggf.level.objects.boss.AbstractBossChild",
                    "lastUpdatedVIntRunCount", "lastUpdatedFrame"),
            new FieldSeed("com.openggf.game.sonic1.objects.Sonic1EggPrisonObjectInstance",
                    "buttonTriggerVIntRunCount", "buttonTriggerFrame"),
            new FieldSeed("com.openggf.game.sonic1.objects.badniks.Sonic1CaterkillerBodyInstance",
                    "deleteVIntRunCount", "deleteFrame"),
            new FieldSeed("com.openggf.game.sonic2.objects.CPZSpinTubeObjectInstance",
                    "currentVIntRunCount", "currentFrameCounter"),
            new FieldSeed("com.openggf.game.sonic2.objects.EggPrisonObjectInstance",
                    "vIntRunCount", "globalFrameCounter"),
            new FieldSeed("com.openggf.game.sonic2.objects.LateralCannonObjectInstance",
                    "phaseMatchedVIntRunCount", "phaseMatchedFrame"),
            new FieldSeed("com.openggf.game.sonic2.objects.LateralCannonObjectInstance",
                    "holdEnteredVIntRunCount", "holdEnteredFrame"),
            new FieldSeed("com.openggf.game.sonic2.objects.LateralCannonObjectInstance",
                    "retractEnteredVIntRunCount", "retractEnteredFrame"),
            new FieldSeed("com.openggf.game.sonic2.objects.LateralCannonObjectInstance",
                    "lastUpdateVIntRunCount", "lastUpdateFrameCounter"),
            new FieldSeed("com.openggf.game.sonic2.objects.RingPrizeObjectInstance",
                    "sparkleStartVIntRunCount", "sparkleStartFrame"),
            new FieldSeed("com.openggf.game.sonic2.objects.RingPrizeObjectInstance",
                    "lastVIntRunCount", "lastFrameCounter"),
            new FieldSeed("com.openggf.game.sonic2.objects.RisingLavaObjectInstance",
                    "lastVIntRunCount", "lastFrameCounter"),
            new FieldSeed("com.openggf.game.sonic2.objects.SignpostObjectInstance",
                    "walkOffEnteredVIntRunCount", "walkOffEnteredFrame"),
            new FieldSeed("com.openggf.game.sonic2.objects.bosses.CNZBossElectricBall",
                    "lastVIntRunCount", "lastFrameCounter"),
            new FieldSeed("com.openggf.game.sonic2.objects.bosses.Sonic2CNZBossInstance",
                    "lastVIntRunCount", "lastFrameCounter"),
            new FieldSeed("com.openggf.game.sonic2.objects.bosses.Sonic2EHZBossInstance",
                    "currentVIntRunCount", "currentFrameCounter"),
            new FieldSeed("com.openggf.game.sonic2.objects.bosses.Sonic2HTZBossInstance",
                    "currentVIntRunCount", "currentFrameCounter"),
            new FieldSeed("com.openggf.game.sonic2.objects.bosses.Sonic2MCZBossInstance",
                    "currentVIntRunCount", "currentFrameCounter"),
            new FieldSeed("com.openggf.game.sonic3k.objects.AbstractS3kFloatingEndEggCapsuleInstance",
                    "buttonTriggerVIntRunCount", "buttonTriggerFrame"),
            new FieldSeed("com.openggf.game.sonic3k.objects.CnzMinibossTopInstance",
                    "diagnosticCurrentVIntRunCount", "diagnosticCurrentFrameCounter"),
            new FieldSeed("com.openggf.game.sonic3k.objects.HczMinibossInstance",
                    "lastVIntRunCount", "lastFrameCounter"),
            new FieldSeed("com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance",
                    "lastUpdateVIntRunCount", "lastUpdateFrameCounter"),
            new FieldSeed("com.openggf.game.sonic3k.objects.PachinkoItemOrbObjectInstance",
                    "animationVIntRunCount", "animationFrameCounter"),
            new FieldSeed("com.openggf.game.sonic3k.objects.badniks.TunnelbotBadnikInstance",
                    "vIntRunCount", "globalFrameCounter"));

    @Test
    void objectUpdateClockUsesVIntRunCountTerminologyAcrossBoundaryAndFrameworkHooks() throws Exception {
        SourceModel model = SourceModel.load();
        List<String> violations = new ArrayList<>();

        for (MethodDeclaration declaration : model.methods()) {
            ExecutableElement method = declaration.element();
            if (isObjectUpdateBoundary(method)) {
                requireFirstParameterName(declaration, "object update boundary", violations);
            }
        }

        Map<MethodSeed, MethodDeclaration> seeds = new LinkedHashMap<>();
        for (MethodSeed seed : HOOK_SEEDS) {
            MethodDeclaration declaration = model.findDeclaredMethod(seed);
            if (declaration == null) {
                violations.add("missing framework hook seed " + seed);
            } else {
                seeds.put(seed, declaration);
            }
        }

        for (MethodDeclaration candidate : model.methods()) {
            if (!(candidate.element().getEnclosingElement() instanceof TypeElement candidateOwner)) {
                continue;
            }
            for (Map.Entry<MethodSeed, MethodDeclaration> entry : seeds.entrySet()) {
                ExecutableElement base = entry.getValue().element();
                if (candidate.element().equals(base)
                        || model.elements().overrides(candidate.element(), base, candidateOwner)) {
                    requireFirstParameterName(candidate, "framework hook " + entry.getKey(), violations);
                }
            }
        }

        RETAINED_CLOCK_FIELDS.forEach(field -> requireRetainedClockField(
                model, field.owner(), field.expected(), field.forbidden(), violations));
        requireLocalClockAlias(
                model,
                "com.openggf.game.sonic1.objects.bosses.Sonic1LZBossInstance#handleBossDefeated",
                "vIntRunCount",
                "frameCounter",
                violations);

        assertTrue(violations.isEmpty(), () -> formatViolations(violations));
    }

    private static boolean isObjectUpdateBoundary(ExecutableElement method) {
        List<? extends VariableElement> parameters = method.getParameters();
        return method.getSimpleName().contentEquals("update")
                && parameters.size() == 2
                && parameters.getFirst().asType().getKind() == TypeKind.INT
                && parameters.get(1).asType().toString().equals(PLAYABLE_ENTITY);
    }

    private static void requireFirstParameterName(
            MethodDeclaration declaration,
            String contract,
            List<String> violations) {
        List<? extends VariableElement> parameters = declaration.element().getParameters();
        if (parameters.isEmpty() || parameters.getFirst().asType().getKind() != TypeKind.INT) {
            violations.add(declaration.location() + " " + contract + " has no leading int clock parameter");
            return;
        }
        String actual = parameters.getFirst().getSimpleName().toString();
        if (!actual.equals(EXPECTED_NAME)) {
            violations.add(declaration.location() + " " + contract + " uses '" + actual + "'");
        }
    }

    private static void requireRetainedClockField(
            SourceModel model,
            String owner,
            String expected,
            String forbidden,
            List<String> violations) {
        Set<String> fields = model.fieldsByOwner().getOrDefault(owner, Set.of());
        if (!fields.contains(expected)) {
            violations.add(owner + " must declare retained V-int clock field '" + expected + "'");
        }
        if (fields.contains(forbidden)) {
            violations.add(owner + " must not declare misleading retained clock field '" + forbidden + "'");
        }
    }

    private static void requireLocalClockAlias(
            SourceModel model,
            String method,
            String expected,
            String forbidden,
            List<String> violations) {
        Set<String> locals = model.localsByMethod().getOrDefault(method, Set.of());
        if (!locals.contains(expected)) {
            violations.add(method + " must declare V-int clock local '" + expected + "'");
        }
        if (locals.contains(forbidden)) {
            violations.add(method + " must not declare misleading clock local '" + forbidden + "'");
        }
    }

    private static String formatViolations(List<String> violations) {
        int displayLimit = Math.min(60, violations.size());
        StringBuilder message = new StringBuilder("Object V-int terminology violations: ")
                .append(violations.size());
        violations.stream().sorted().limit(displayLimit)
                .forEach(violation -> message.append("\n  ").append(violation));
        if (violations.size() > displayLimit) {
            message.append("\n  ... ").append(violations.size() - displayLimit).append(" more");
        }
        return message.toString();
    }

    private record MethodSeed(String owner, String name, int parameterCount) {
    }

    private record FieldSeed(String owner, String expected, String forbidden) {
    }

    private record MethodDeclaration(ExecutableElement element, Path source, long line) {
        String location() {
            return source + ":" + line;
        }
    }

    private record SourceModel(
            List<MethodDeclaration> methods,
            Map<String, Set<String>> fieldsByOwner,
            Map<String, Set<String>> localsByMethod,
            javax.lang.model.util.Elements elements) {

        static SourceModel load() throws Exception {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            assertTrue(compiler != null, "terminology guard requires a JDK, not a JRE");

            List<Path> sources = javaSources();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
                Iterable<? extends JavaFileObject> files = fileManager.getJavaFileObjectsFromPaths(sources);
                List<String> options = List.of(
                        "-proc:none",
                        "--release", "21",
                        "-classpath", System.getProperty("java.class.path"));
                JavacTask task = (JavacTask) compiler.getTask(
                        null, fileManager, diagnostics, options, null, files);
                List<CompilationUnitTree> units = new ArrayList<>();
                task.parse().forEach(units::add);
                task.analyze();

                List<String> errors = diagnostics.getDiagnostics().stream()
                        .filter(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                        .map(Object::toString)
                        .toList();
                assertTrue(errors.isEmpty(), () -> "source attribution failed:\n" + String.join("\n", errors));

                Trees trees = Trees.instance(task);
                List<MethodDeclaration> methods = new ArrayList<>();
                Map<String, Set<String>> fields = new LinkedHashMap<>();
                Map<String, Set<String>> locals = new LinkedHashMap<>();
                for (CompilationUnitTree unit : units) {
                    new TreePathScanner<Void, Void>() {
                        @Override
                        public Void visitMethod(MethodTree method, Void unused) {
                            Element element = trees.getElement(getCurrentPath());
                            if (element instanceof ExecutableElement executable) {
                                long position = trees.getSourcePositions().getStartPosition(unit, method);
                                long line = position < 0 ? -1 : unit.getLineMap().getLineNumber(position);
                                methods.add(new MethodDeclaration(
                                        executable,
                                        Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize(),
                                        line));
                            }
                            return super.visitMethod(method, unused);
                        }

                        @Override
                        public Void visitVariable(VariableTree variable, Void unused) {
                            Element element = trees.getElement(getCurrentPath());
                            if (element != null
                                    && element.getKind() == ElementKind.FIELD
                                    && element.getEnclosingElement() instanceof TypeElement owner) {
                                fields.computeIfAbsent(owner.getQualifiedName().toString(), ignored -> new LinkedHashSet<>())
                                        .add(element.getSimpleName().toString());
                            } else if (element != null
                                    && element.getKind() == ElementKind.LOCAL_VARIABLE
                                    && element.getEnclosingElement() instanceof ExecutableElement method
                                    && method.getEnclosingElement() instanceof TypeElement owner) {
                                String methodKey = owner.getQualifiedName() + "#" + method.getSimpleName();
                                locals.computeIfAbsent(methodKey, ignored -> new LinkedHashSet<>())
                                        .add(element.getSimpleName().toString());
                            }
                            return super.visitVariable(variable, unused);
                        }
                    }.scan(unit, null);
                }
                methods.sort(Comparator.comparing(MethodDeclaration::location));
                return new SourceModel(
                        List.copyOf(methods), Map.copyOf(fields), Map.copyOf(locals), task.getElements());
            }
        }

        MethodDeclaration findDeclaredMethod(MethodSeed seed) {
            return methods.stream()
                    .filter(declaration -> declaration.element().getSimpleName().contentEquals(seed.name()))
                    .filter(declaration -> declaration.element().getParameters().size() == seed.parameterCount())
                    .filter(declaration -> declaration.element().getEnclosingElement() instanceof TypeElement owner
                            && owner.getQualifiedName().contentEquals(seed.owner()))
                    .findFirst()
                    .orElse(null);
        }

        private static List<Path> javaSources() throws IOException {
            List<Path> sources = new ArrayList<>();
            for (Path root : SOURCE_ROOTS) {
                try (var stream = Files.walk(root)) {
                    stream.filter(path -> path.toString().endsWith(".java"))
                            .forEach(sources::add);
                }
            }
            sources.sort(Comparator.naturalOrder());
            return sources;
        }
    }
}
