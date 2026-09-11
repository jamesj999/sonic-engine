package com.openggf.configuration;

import java.util.EnumSet;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A configured key binding, optionally qualified by modifiers:
 * {@code "O"}, {@code "CTRL+SHIFT+O"}, {@code "META+LEFT_BRACKET"}.
 *
 * <p>Bindings used to be a bare key code, so any shortcut wanting a modifier
 * had to hardcode it at its call site — live recording mandated Shift in
 * {@code Engine}, which meant {@code capture.toggleKey} accepted a key the
 * player could change while the Shift it was actually pressed with was
 * invisible and immovable. Modifiers belong in the binding.
 *
 * <p>Parsing accepts everything that worked before — an integer key code,
 * {@code "O"}, {@code "GLFW_KEY_O"} — and reads those as a chord with no
 * modifiers, so existing {@code config.yaml} files keep their meaning.
 *
 * <p>{@link #matchesModifiers} requires the declared modifiers to be held and the others
 * to be released. A binding of {@code "O"} therefore does not fire while Ctrl
 * is down, which is what stops a plain shortcut stealing a chord that another
 * binding has claimed.
 */
public record KeyChord(int keyCode, Set<Modifier> modifiers) {

    /** Canonical order, used when formatting so a round trip is stable. */
    public enum Modifier { CTRL, SHIFT, ALT, META }

    public static final int NO_KEY = -1;

    public KeyChord {
        modifiers = modifiers == null || modifiers.isEmpty()
                ? EnumSet.noneOf(Modifier.class)
                : EnumSet.copyOf(modifiers);
    }

    public static KeyChord of(int keyCode, Modifier... modifiers) {
        return new KeyChord(keyCode, modifiers.length == 0
                ? EnumSet.noneOf(Modifier.class)
                : EnumSet.of(modifiers[0], modifiers));
    }

    /**
     * @param configured an integer key code, a key name, or a {@code +}
     *        separated chord. {@code null} or unrecognised input yields
     *        {@link #NO_KEY}, matching how unresolvable bindings behaved before.
     */
    public static KeyChord parse(Object configured) {
        if (configured instanceof Number number) {
            return new KeyChord(number.intValue(), EnumSet.noneOf(Modifier.class));
        }
        if (configured == null) {
            return new KeyChord(NO_KEY, EnumSet.noneOf(Modifier.class));
        }
        String text = configured.toString().trim();
        if (text.isEmpty()) {
            return new KeyChord(NO_KEY, EnumSet.noneOf(Modifier.class));
        }

        Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
        // The final segment is the key; everything before it is a modifier.
        // Splitting on '+' is safe because no GLFW key name contains one — the
        // plus key is named KP_ADD or EQUAL.
        String[] segments = text.split("\\+");
        if (segments.length == 0) {
            // "+", "++" and "+++" split to a zero-length array, so there is no
            // key segment to index. '+' is the separator; the plus key is named
            // EQUAL or KP_ADD.
            return new KeyChord(NO_KEY, EnumSet.noneOf(Modifier.class));
        }
        for (int i = 0; i < segments.length - 1; i++) {
            Modifier modifier = modifier(segments[i]);
            if (modifier == null) {
                return new KeyChord(NO_KEY, EnumSet.noneOf(Modifier.class));
            }
            modifiers.add(modifier);
        }
        return new KeyChord(keyCode(segments[segments.length - 1]), modifiers);
    }

    private static Modifier modifier(String segment) {
        return switch (segment.trim().toUpperCase(Locale.ROOT)) {
            case "CTRL", "CONTROL" -> Modifier.CTRL;
            case "SHIFT" -> Modifier.SHIFT;
            case "ALT", "OPTION" -> Modifier.ALT;
            // GLFW calls it SUPER; players call it Command or Windows.
            case "META", "SUPER", "CMD", "COMMAND", "WIN" -> Modifier.META;
            default -> null;
        };
    }

    private static int keyCode(String segment) {
        String name = segment.trim();
        if (name.isEmpty()) {
            return NO_KEY;
        }
        // The name table wins, exactly as SonicConfigurationService.resolveInt
        // does for a KEY value: "1" is the number-row key (49), not raw code 1.
        // A raw code is only read when no key of that name exists.
        OptionalInt resolved = GlfwKeyNameResolver.resolve(name);
        if (resolved.isPresent()) {
            return resolved.getAsInt();
        }
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException ignored) {
            return NO_KEY;
        }
    }

    public boolean isBound() {
        return keyCode != NO_KEY;
    }

    /**
     * True when this chord's key is down with exactly its modifiers held.
     * Modifiers this chord does not declare must be released.
     */
    public boolean matchesModifiers(boolean shift, boolean ctrl, boolean alt, boolean meta) {
        return modifiers.contains(Modifier.SHIFT) == shift
                && modifiers.contains(Modifier.CTRL) == ctrl
                && modifiers.contains(Modifier.ALT) == alt
                && modifiers.contains(Modifier.META) == meta;
    }

    /** Canonical form, parseable back to an equal chord. */
    public String format() {
        StringBuilder text = new StringBuilder();
        for (Modifier modifier : Modifier.values()) {
            if (modifiers.contains(modifier)) {
                text.append(modifier.name()).append('+');
            }
        }
        return text.append(GlfwKeyNameResolver.nameOf(keyCode)).toString();
    }
}
