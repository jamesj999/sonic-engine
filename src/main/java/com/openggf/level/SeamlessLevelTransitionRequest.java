package com.openggf.level;

import com.openggf.game.RuntimeArtAdmissionPolicy;

/**
 * In-place transition request for seamless level events.
 */
public final class SeamlessLevelTransitionRequest {
    public enum TransitionType {
        MUTATE_ONLY,
        RELOAD_SAME_LEVEL,
        RELOAD_TARGET_LEVEL
    }

    private final TransitionType type;
    private final int targetZone;
    private final int targetAct;
    private final boolean deactivateLevelNow;
    private final boolean preserveMusic;
    private final boolean preserveLevelGamestate;
    private final boolean preserveEndOfLevelActive;
    private final boolean preserveEndOfLevelFlag;
    private final boolean showInLevelTitleCard;
    private final RuntimeArtAdmissionPolicy runtimeArtAdmissionPolicy;
    private final boolean resetLevelGamestateAtInLevelTitleCardDisplay;
    private final int inLevelTitleCardResetAdditionalDispatches;
    private final int inLevelTitleCardResetPhaseOneDispatchOverlap;
    private final boolean lockPlayerControlForInLevelTitleCard;
    private final int inLevelTitleCardExitAdditionalDispatches;
    private final int inLevelTitleCardExitPhaseOneDispatchOverlap;
    private final int inLevelTitleCardPreloadedActCameraReleaseDispatches;
    private final int carriedResultsRetireDispatches;
    private final boolean forceAirOnStaleObjectSupportLoss;
    private final boolean preserveOffsetCameraPosition;
    private final Integer postTransitionMinX;
    private final Integer postTransitionMaxX;
    private final Integer postTransitionMinY;
    private final Integer postTransitionMaxY;
    private final Integer postTransitionMaxYTarget;
    private final int playerOffsetX;
    private final int playerOffsetY;
    private final int cameraOffsetX;
    private final int cameraOffsetY;
    private final String mutationKey;
    private final int musicOverrideId;
    private final SeamlessTransitionResourceHandoffId resourceHandoffId;

    private SeamlessLevelTransitionRequest(Builder builder) {
        this.type = builder.type;
        this.targetZone = builder.targetZone;
        this.targetAct = builder.targetAct;
        this.deactivateLevelNow = builder.deactivateLevelNow;
        this.preserveMusic = builder.preserveMusic;
        this.preserveLevelGamestate = builder.preserveLevelGamestate;
        this.preserveEndOfLevelActive = builder.preserveEndOfLevelActive;
        this.preserveEndOfLevelFlag = builder.preserveEndOfLevelFlag;
        this.showInLevelTitleCard = builder.showInLevelTitleCard;
        this.runtimeArtAdmissionPolicy = builder.runtimeArtAdmissionPolicy;
        this.resetLevelGamestateAtInLevelTitleCardDisplay =
                builder.resetLevelGamestateAtInLevelTitleCardDisplay;
        this.inLevelTitleCardResetAdditionalDispatches =
                builder.inLevelTitleCardResetAdditionalDispatches;
        this.inLevelTitleCardResetPhaseOneDispatchOverlap =
                builder.inLevelTitleCardResetPhaseOneDispatchOverlap;
        this.lockPlayerControlForInLevelTitleCard = builder.lockPlayerControlForInLevelTitleCard;
        this.inLevelTitleCardExitAdditionalDispatches =
                builder.inLevelTitleCardExitAdditionalDispatches;
        this.inLevelTitleCardExitPhaseOneDispatchOverlap =
                builder.inLevelTitleCardExitPhaseOneDispatchOverlap;
        this.inLevelTitleCardPreloadedActCameraReleaseDispatches =
                builder.inLevelTitleCardPreloadedActCameraReleaseDispatches;
        this.carriedResultsRetireDispatches = builder.carriedResultsRetireDispatches;
        this.forceAirOnStaleObjectSupportLoss = builder.forceAirOnStaleObjectSupportLoss;
        this.preserveOffsetCameraPosition = builder.preserveOffsetCameraPosition;
        this.postTransitionMinX = builder.postTransitionMinX;
        this.postTransitionMaxX = builder.postTransitionMaxX;
        this.postTransitionMinY = builder.postTransitionMinY;
        this.postTransitionMaxY = builder.postTransitionMaxY;
        this.postTransitionMaxYTarget = builder.postTransitionMaxYTarget;
        this.playerOffsetX = builder.playerOffsetX;
        this.playerOffsetY = builder.playerOffsetY;
        this.cameraOffsetX = builder.cameraOffsetX;
        this.cameraOffsetY = builder.cameraOffsetY;
        this.mutationKey = builder.mutationKey;
        this.musicOverrideId = builder.musicOverrideId;
        this.resourceHandoffId = builder.resourceHandoffId;
    }

    public TransitionType type() {
        return type;
    }

    public int targetZone() {
        return targetZone;
    }

    public int targetAct() {
        return targetAct;
    }

    public boolean deactivateLevelNow() {
        return deactivateLevelNow;
    }

    public boolean preserveMusic() {
        return preserveMusic;
    }

    public boolean preserveLevelGamestate() {
        return preserveLevelGamestate;
    }

    public boolean preserveEndOfLevelState() {
        return preserveEndOfLevelActive && preserveEndOfLevelFlag;
    }

    public boolean preserveEndOfLevelActive() {
        return preserveEndOfLevelActive;
    }

    public boolean preserveEndOfLevelFlag() {
        return preserveEndOfLevelFlag;
    }

    public boolean showInLevelTitleCard() {
        return showInLevelTitleCard;
    }

    public RuntimeArtAdmissionPolicy runtimeArtAdmissionPolicy() {
        return runtimeArtAdmissionPolicy;
    }

    public boolean resetLevelGamestateAtInLevelTitleCardDisplay() {
        return resetLevelGamestateAtInLevelTitleCardDisplay;
    }

    public int inLevelTitleCardResetAdditionalDispatches() {
        return inLevelTitleCardResetAdditionalDispatches;
    }

    public int inLevelTitleCardResetPhaseOneDispatchOverlap() {
        return inLevelTitleCardResetPhaseOneDispatchOverlap;
    }

    public boolean lockPlayerControlForInLevelTitleCard() {
        return lockPlayerControlForInLevelTitleCard;
    }

    public int inLevelTitleCardExitAdditionalDispatches() {
        return inLevelTitleCardExitAdditionalDispatches;
    }

    public int inLevelTitleCardExitPhaseOneDispatchOverlap() {
        return inLevelTitleCardExitPhaseOneDispatchOverlap;
    }

    /**
     * Optional retained-owner tail for a preloaded next-act title card.
     * A negative value keeps the legacy title-owner default.
     */
    public int inLevelTitleCardPreloadedActCameraReleaseDispatches() {
        return inLevelTitleCardPreloadedActCameraReleaseDispatches;
    }

    /**
     * Optional retained-results owner tail for a reload whose results object
     * survives into the target level. A negative value keeps the native
     * results-owner default.
     */
    public int carriedResultsRetireDispatches() {
        return carriedResultsRetireDispatches;
    }

    public boolean forceAirOnStaleObjectSupportLoss() {
        return forceAirOnStaleObjectSupportLoss;
    }

    public boolean preserveOffsetCameraPosition() {
        return preserveOffsetCameraPosition;
    }

    public Integer postTransitionMinX() {
        return postTransitionMinX;
    }

    public Integer postTransitionMaxX() {
        return postTransitionMaxX;
    }

    public Integer postTransitionMinY() {
        return postTransitionMinY;
    }

    public Integer postTransitionMaxY() {
        return postTransitionMaxY;
    }

    public Integer postTransitionMaxYTarget() {
        return postTransitionMaxYTarget;
    }

    public int playerOffsetX() {
        return playerOffsetX;
    }

    public int playerOffsetY() {
        return playerOffsetY;
    }

    public int cameraOffsetX() {
        return cameraOffsetX;
    }

    public int cameraOffsetY() {
        return cameraOffsetY;
    }

    public String mutationKey() {
        return mutationKey;
    }

    public int musicOverrideId() {
        return musicOverrideId;
    }

    public SeamlessTransitionResourceHandoffId resourceHandoffId() {
        return resourceHandoffId;
    }

    public static Builder builder(TransitionType type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final TransitionType type;
        private int targetZone = -1;
        private int targetAct = -1;
        private boolean deactivateLevelNow;
        private boolean preserveMusic = true;
        private boolean preserveLevelGamestate;
        private boolean preserveEndOfLevelActive;
        private boolean preserveEndOfLevelFlag;
        private boolean showInLevelTitleCard;
        private RuntimeArtAdmissionPolicy runtimeArtAdmissionPolicy =
                RuntimeArtAdmissionPolicy.IMMEDIATE;
        private boolean resetLevelGamestateAtInLevelTitleCardDisplay;
        private int inLevelTitleCardResetAdditionalDispatches;
        private int inLevelTitleCardResetPhaseOneDispatchOverlap;
        private boolean lockPlayerControlForInLevelTitleCard;
        private int inLevelTitleCardExitAdditionalDispatches;
        private int inLevelTitleCardExitPhaseOneDispatchOverlap;
        private int inLevelTitleCardPreloadedActCameraReleaseDispatches = -1;
        private int carriedResultsRetireDispatches = -1;
        private boolean forceAirOnStaleObjectSupportLoss;
        private boolean preserveOffsetCameraPosition;
        private Integer postTransitionMinX;
        private Integer postTransitionMaxX;
        private Integer postTransitionMinY;
        private Integer postTransitionMaxY;
        private Integer postTransitionMaxYTarget;
        private int playerOffsetX;
        private int playerOffsetY;
        private int cameraOffsetX;
        private int cameraOffsetY;
        private String mutationKey;
        private int musicOverrideId = -1;
        private SeamlessTransitionResourceHandoffId resourceHandoffId;

        private Builder(TransitionType type) {
            this.type = type;
        }

        public Builder targetZoneAct(int zone, int act) {
            this.targetZone = zone;
            this.targetAct = act;
            return this;
        }

        public Builder deactivateLevelNow(boolean deactivateLevelNow) {
            this.deactivateLevelNow = deactivateLevelNow;
            return this;
        }

        public Builder preserveMusic(boolean preserveMusic) {
            this.preserveMusic = preserveMusic;
            return this;
        }

        public Builder preserveLevelGamestate(boolean preserveLevelGamestate) {
            this.preserveLevelGamestate = preserveLevelGamestate;
            return this;
        }

        /**
         * Keeps the ROM end-of-level globals alive across an in-place
         * {@code Load_Level}. Use this when the results/end-sign objects span
         * the resource reload and still own those globals afterward.
         */
        public Builder preserveEndOfLevelState(boolean preserveEndOfLevelState) {
            this.preserveEndOfLevelActive = preserveEndOfLevelState;
            this.preserveEndOfLevelFlag = preserveEndOfLevelState;
            return this;
        }

        /** Keeps Level_end_flag while allowing End_of_level_flag to reset. */
        public Builder preserveEndOfLevelActive(boolean preserveEndOfLevelActive) {
            this.preserveEndOfLevelActive = preserveEndOfLevelActive;
            return this;
        }

        public Builder showInLevelTitleCard(boolean showInLevelTitleCard) {
            this.showInLevelTitleCard = showInLevelTitleCard;
            return this;
        }

        public Builder runtimeArtAdmissionPolicy(
                RuntimeArtAdmissionPolicy runtimeArtAdmissionPolicy) {
            this.runtimeArtAdmissionPolicy = java.util.Objects.requireNonNull(
                    runtimeArtAdmissionPolicy, "runtimeArtAdmissionPolicy");
            return this;
        }

        public Builder resetLevelGamestateAtInLevelTitleCardDisplay(boolean reset) {
            this.resetLevelGamestateAtInLevelTitleCardDisplay = reset;
            return this;
        }

        public Builder inLevelTitleCardResetAdditionalDispatches(int dispatches) {
            this.inLevelTitleCardResetAdditionalDispatches = Math.max(0, dispatches);
            return this;
        }

        public Builder inLevelTitleCardResetPhaseOneDispatchOverlap(int dispatches) {
            this.inLevelTitleCardResetPhaseOneDispatchOverlap = Math.max(0, dispatches);
            return this;
        }

        public Builder lockPlayerControlForInLevelTitleCard(boolean lock) {
            this.lockPlayerControlForInLevelTitleCard = lock;
            return this;
        }

        public Builder inLevelTitleCardExitAdditionalDispatches(int dispatches) {
            this.inLevelTitleCardExitAdditionalDispatches = Math.max(0, dispatches);
            return this;
        }

        public Builder inLevelTitleCardExitPhaseOneDispatchOverlap(int dispatches) {
            this.inLevelTitleCardExitPhaseOneDispatchOverlap = Math.max(0, dispatches);
            return this;
        }

        public Builder inLevelTitleCardPreloadedActCameraReleaseDispatches(int dispatches) {
            this.inLevelTitleCardPreloadedActCameraReleaseDispatches = dispatches < 0
                    ? -1 : dispatches;
            return this;
        }

        public Builder carriedResultsRetireDispatches(int dispatches) {
            this.carriedResultsRetireDispatches = dispatches < 0 ? -1 : dispatches;
            return this;
        }

        public Builder forceAirOnStaleObjectSupportLoss(boolean forceAirOnStaleObjectSupportLoss) {
            this.forceAirOnStaleObjectSupportLoss = forceAirOnStaleObjectSupportLoss;
            return this;
        }

        public Builder preserveOffsetCameraPosition(boolean preserveOffsetCameraPosition) {
            this.preserveOffsetCameraPosition = preserveOffsetCameraPosition;
            return this;
        }

        public Builder postTransitionMinX(int minX) {
            this.postTransitionMinX = minX;
            return this;
        }

        public Builder postTransitionMinXIfPresent(Integer minX) {
            this.postTransitionMinX = minX;
            return this;
        }

        public Builder postTransitionMaxX(int maxX) {
            this.postTransitionMaxX = maxX;
            return this;
        }

        public Builder postTransitionMaxXIfPresent(Integer maxX) {
            this.postTransitionMaxX = maxX;
            return this;
        }

        public Builder postTransitionMinY(int minY) {
            this.postTransitionMinY = minY;
            return this;
        }

        public Builder postTransitionMinYIfPresent(Integer minY) {
            this.postTransitionMinY = minY;
            return this;
        }

        public Builder postTransitionMaxY(int maxY) {
            this.postTransitionMaxY = maxY;
            return this;
        }

        public Builder postTransitionMaxYIfPresent(Integer maxY) {
            this.postTransitionMaxY = maxY;
            return this;
        }

        public Builder postTransitionMaxYTarget(int maxYTarget) {
            this.postTransitionMaxYTarget = maxYTarget;
            return this;
        }

        public Builder postTransitionMaxYTargetIfPresent(Integer maxYTarget) {
            this.postTransitionMaxYTarget = maxYTarget;
            return this;
        }

        public Builder playerOffset(int x, int y) {
            this.playerOffsetX = x;
            this.playerOffsetY = y;
            return this;
        }

        public Builder cameraOffset(int x, int y) {
            this.cameraOffsetX = x;
            this.cameraOffsetY = y;
            return this;
        }

        public Builder mutationKey(String mutationKey) {
            this.mutationKey = mutationKey;
            return this;
        }

        public Builder musicOverrideId(int musicOverrideId) {
            this.musicOverrideId = musicOverrideId;
            return this;
        }

        public Builder resourceHandoff(
                SeamlessTransitionResourceHandoffId resourceHandoffId) {
            this.resourceHandoffId = resourceHandoffId;
            return this;
        }

        public SeamlessLevelTransitionRequest build() {
            return new SeamlessLevelTransitionRequest(this);
        }
    }
}
