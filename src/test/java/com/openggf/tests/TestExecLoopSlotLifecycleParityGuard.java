package com.openggf.tests;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code ObjectManager} carries two implementations of the ROM
 * {@code ExecuteObjects} walk (docs/s1disasm/_inc/ExecuteObjects.asm:10-30):
 * {@code updateCounterBasedExecThenLoad} (the counter-based S1 lane) and
 * {@code runExecLoop} (the lane S2 and S3K take). The walk itself is
 * substantially the same in all three games — ascending SST order, each object's
 * {@code out_of_range}/{@code MarkObjGone} tail freeing THAT object's slot at
 * THAT slot's position — so a mechanism modelling the walk's slot lifecycle
 * belongs in both loops unless something game-specific justifies the split.
 *
 * <p>Twice now a slot-lifecycle mechanism has been added to the counter lane
 * only and the omission on the S2/S3K lane went undetected by the whole suite,
 * surfacing later as an unexplained trace divergence:
 * <ul>
 *   <li>{@code pendingChildSlotRelease} — every deferred child-slot release on
 *       the S2/S3K path leaked its SST slot permanently.</li>
 *   <li>{@code ownSlotRetireOrder} / {@code indexOwnSlotRetirement} /
 *       {@code retireBorrowedExecutionSlotOwnerAtOwnSlot} — consolidated parents
 *       running from a borrowed child slot (S3K
 *       {@code Obj_AIZGiantRideVine}) released their own slot later in the walk
 *       than ROM.</li>
 * </ul>
 *
 * <p>This guard compares the two loop bodies over the slot-lifecycle vocabulary
 * and fails on any asymmetry that is not in {@link #ALLOWED_ASYMMETRIES}, where
 * each entry carries a one-line justification. It is a source scan, in the same
 * house style as {@code TestBuildToolingGuard}, because the defect is one of
 * omission and therefore has no runtime signal to assert on.
 */
class TestExecLoopSlotLifecycleParityGuard {

    private static final Path OBJECT_MANAGER =
            Path.of("src/main/java/com/openggf/level/objects/ObjectManager.java").toAbsolutePath();

    private static final String COUNTER_LANE = "updateCounterBasedExecThenLoad";
    private static final String STANDARD_LANE = "runExecLoop";

    /**
     * Identifiers whose name marks them as part of the object-slot lifecycle.
     * Deliberately broad: a new mechanism should be caught by default and then
     * either mirrored or explicitly justified below.
     */
    private static final Pattern SLOT_LIFECYCLE_VOCABULARY =
            Pattern.compile("(?i)(slot|retire|release|child|unload|freed|outofrange)");

    /**
     * Identifier -> justification for a genuine per-lane difference. An entry
     * here is a claim that the ROM (or an engine-wide invariant) actually
     * differs between the two lanes, not that mirroring is inconvenient.
     */
    private static final Map<String, String> ALLOWED_ASYMMETRIES = new LinkedHashMap<>();

    static {
        ALLOWED_ASYMMETRIES.put("checksOutOfRangeAfterRoutine",
                "Counter lane only: S1 ExecuteObjects checks out_of_range at the START of most "
                        + "routines, so the flag selects pre- vs post-routine. S2/S3K objects end "
                        + "their routines with MarkObjGone_P1, which tests the CURRENT x_pos "
                        + "(docs/s2disasm/s2.asm:30281-30289), so the standard lane always checks "
                        + "after the routine and the flag has nothing to select.");
        ALLOWED_ASYMMETRIES.put("checksOutOfRangeAfter",
                "Local variable of the counter lane's pre/post branch; see "
                        + "checksOutOfRangeAfterRoutine above.");
        ALLOWED_ASYMMETRIES.put("objectUnloadCameraX",
                "Standard lane only: adjusts the camera X used for the range test for the "
                        + "SONIC_2 slot layout. A per-layout camera value, not a slot-lifecycle "
                        + "mechanism; the counter lane is S1 and uses cameraX unchanged.");
        ALLOWED_ASYMMETRIES.put("unloadCameraX",
                "Local holding the result of objectUnloadCameraX; see above.");
    }

    @Test
    void bothExecLoopsImplementTheSameSlotLifecycleMechanisms() throws Exception {
        String source = Files.readString(OBJECT_MANAGER, StandardCharsets.UTF_8);

        String counterBody = stripComments(methodBody(source, COUNTER_LANE));
        String standardBody = stripComments(methodBody(source, STANDARD_LANE));

        TreeSet<String> counterOnly = new TreeSet<>();
        TreeSet<String> standardOnly = new TreeSet<>();

        for (String identifier : identifiers(source)) {
            if (!SLOT_LIFECYCLE_VOCABULARY.matcher(identifier).find()) {
                continue;
            }
            boolean inCounter = mentions(counterBody, identifier);
            boolean inStandard = mentions(standardBody, identifier);
            if (inCounter == inStandard || ALLOWED_ASYMMETRIES.containsKey(identifier)) {
                continue;
            }
            (inCounter ? counterOnly : standardOnly).add(identifier);
        }

        if (counterOnly.isEmpty() && standardOnly.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder()
                .append("ObjectManager's two ExecuteObjects loops diverge on slot-lifecycle "
                        + "mechanisms. Both loops model the same ascending ROM walk "
                        + "(docs/s1disasm/_inc/ExecuteObjects.asm:10-30), so a mechanism present "
                        + "in only one is a defect on the other lane until proven otherwise.\n");
        if (!counterOnly.isEmpty()) {
            message.append("  Only in ").append(COUNTER_LANE)
                    .append(" (the S1 lane; missing on S2/S3K): ").append(counterOnly).append('\n');
        }
        if (!standardOnly.isEmpty()) {
            message.append("  Only in ").append(STANDARD_LANE)
                    .append(" (the S2/S3K lane; missing on S1): ").append(standardOnly).append('\n');
        }
        message.append("Mirror the mechanism into the other loop, or add the identifier to "
                + "ALLOWED_ASYMMETRIES in ")
                .append(TestExecLoopSlotLifecycleParityGuard.class.getSimpleName())
                .append(" with a one-line justification naming the ROM behaviour that differs.");
        fail(message.toString());
    }

    /**
     * The mechanisms whose omission has already cost a debugging round. Asserted
     * by name as well as by the symmetry scan above, so that deleting them from
     * either lane fails loudly rather than merely restoring symmetry-by-absence.
     */
    @Test
    void knownRegressedMechanismsArePresentInBothExecLoops() throws Exception {
        String source = Files.readString(OBJECT_MANAGER, StandardCharsets.UTF_8);
        String counterBody = stripComments(methodBody(source, COUNTER_LANE));
        String standardBody = stripComments(methodBody(source, STANDARD_LANE));

        for (String mechanism : new String[] {
                "pendingChildSlotRelease",
                "ownSlotRetireOrder",
                "indexOwnSlotRetirement",
                "retireBorrowedExecutionSlotOwnerAtOwnSlot",
                "slotsFreedDuringObjectPass",
        }) {
            assertTrue(mentions(counterBody, mechanism),
                    mechanism + " must be honoured by " + COUNTER_LANE);
            assertTrue(mentions(standardBody, mechanism),
                    mechanism + " must be honoured by " + STANDARD_LANE);
        }
    }

    @Test
    void allowlistEntriesCarryAJustification() {
        ALLOWED_ASYMMETRIES.forEach((identifier, justification) ->
                assertTrue(justification != null && justification.length() >= 40,
                        "Allowlist entry '" + identifier + "' needs a real justification"));
    }

    private static boolean mentions(String body, String identifier) {
        return Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b").matcher(body).find();
    }

    private static TreeSet<String> identifiers(String source) {
        TreeSet<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("\\b[A-Za-z_]\\w*\\b").matcher(source);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Extracts a method declaration's body by brace matching. */
    private static String methodBody(String source, String methodName) {
        Matcher m = Pattern.compile(
                        "\\b\\w+\\s+" + Pattern.quote(methodName)
                                + "\\s*\\((?:[^()]|\\([^()]*\\))*\\)\\s*\\{",
                        Pattern.DOTALL)
                .matcher(source);
        if (!m.find()) {
            fail("Could not locate the declaration of ObjectManager." + methodName
                    + "; this guard must be updated alongside any rename.");
        }
        int i = m.end();
        int depth = 1;
        while (depth > 0) {
            char c = source.charAt(i++);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return source.substring(m.end(), i - 1);
    }

    private static String stripComments(String body) {
        String withoutBlocks = body.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlocks.replaceAll("//[^\\n]*", "");
    }
}
