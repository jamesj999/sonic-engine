package com.openggf.level.objects;

public record SolidContact(boolean standing, boolean touchSide, boolean touchBottom, boolean touchTop,
                           boolean pushing, int sideDistX, boolean movingInto) {
    /** Convenience constructor for contacts without side displacement. */
    public SolidContact(boolean standing, boolean touchSide, boolean touchBottom, boolean touchTop, boolean pushing) {
        this(standing, touchSide, touchBottom, touchTop, pushing, 0, false);
    }

    static final SolidContact NONE = new SolidContact(false, false, false, false, false);
    static final SolidContact STANDING = new SolidContact(true, false, false, true, false);
    static final SolidContact SIDE_NO_PUSH = new SolidContact(false, true, false, false, false);
    static final SolidContact SIDE_PUSH = new SolidContact(false, true, false, false, true);
    static final SolidContact CEILING = new SolidContact(false, false, true, false, false);

    /**
     * Create a side contact with the pre-correction X displacement.
     * ROM: d0 from Solid_ChkEnter is preserved through SolidObject return,
     * and the push handler tests it directly ("tst.w d0 / beq locret_C2E4").
     */
    static SolidContact side(boolean pushing, int distX, boolean movingInto) {
        return new SolidContact(false, true, false, false, pushing, distX, movingInto);
    }
}
