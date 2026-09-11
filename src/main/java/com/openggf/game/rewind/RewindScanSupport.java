package com.openggf.game.rewind;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.sprites.AbstractSprite;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class RewindScanSupport {
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;");

    public static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("src/main/java/com/openggf/level/objects"),
            Path.of("src/main/java/com/openggf/game/sonic1/objects"),
            Path.of("src/main/java/com/openggf/game/sonic2/objects"),
            Path.of("src/main/java/com/openggf/game/sonic3k/objects"),
            Path.of("src/main/java/com/openggf/sprites/playable")
    );

    public static List<Class<?>> discoverRuntimeOwnerClasses() throws IOException {
        Set<Class<?>> classes = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (Path root : SOURCE_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                    Optional<String> className = classNameFor(file);
                    if (className.isEmpty()) {
                        continue;
                    }
                    try {
                        Class<?> cls = Class.forName(className.get(), false, loader);
                        if (isRuntimeOwner(cls)) {
                            classes.add(cls);
                        }
                    } catch (ClassNotFoundException e) {
                        unresolved.add(file + " -> " + className.get());
                    }
                }
            }
        }
        classes.add(load("com.openggf.sprites.AbstractSprite"));
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Unresolved source classes:\n"
                    + String.join("\n", unresolved));
        }
        return List.copyOf(classes);
    }

    public static List<Class<?>> withNestedRuntimeOwnerClasses(Class<?> cls) {
        List<Class<?>> result = new ArrayList<>();
        result.add(cls);
        for (Class<?> nested : cls.getDeclaredClasses()) {
            if (isRuntimeOwner(nested)) {
                result.addAll(withNestedRuntimeOwnerClasses(nested));
            }
        }
        return result;
    }

    public static boolean isRuntimeOwner(Class<?> cls) {
        return AbstractObjectInstance.class.isAssignableFrom(cls)
                || AbstractPlayableSprite.class.isAssignableFrom(cls)
                || cls == SidekickCpuController.class
                || cls == AbstractSprite.class;
    }

    private static Optional<String> classNameFor(Path file) throws IOException {
        String packageName = null;
        for (String line : Files.readAllLines(file)) {
            Matcher matcher = PACKAGE_PATTERN.matcher(line);
            if (matcher.find()) {
                packageName = matcher.group(1);
                break;
            }
        }
        if (packageName == null) {
            return Optional.empty();
        }
        String simpleName = file.getFileName().toString().replaceFirst("\\.java$", "");
        return Optional.of(packageName + "." + simpleName);
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(
                    className,
                    false,
                    Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Missing expected class " + className, e);
        }
    }

    private RewindScanSupport() {
    }
}
