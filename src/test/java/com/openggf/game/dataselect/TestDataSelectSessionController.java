package com.openggf.game.dataselect;

import com.openggf.game.save.SaveSlotState;
import com.openggf.game.save.SaveSlotSummary;
import com.openggf.game.save.SelectedTeam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDataSelectSessionController {

    @Test
    void clearRestartDestinations_comeFromHostProfile() {
        StubHostProfile hostProfile = new StubHostProfile();
        DataSelectSessionController controller = new DataSelectSessionController(hostProfile);

        controller.loadAvailableTeams(null);
        controller.loadSlotSummaries(List.of(
                SaveSlotSummary.empty(1),
                new SaveSlotSummary(2, SaveSlotState.VALID, Map.of(
                        "zone", 7,
                        "act", 1,
                        "clear", true,
                        "mainCharacter", "knuckles",
                        "sidekicks", List.of()
                ))
        ));
        controller.menuModel().setSelectedRow(2);

        assertEquals(hostProfile.clearRestartTargets, controller.currentClearRestartDestinations());
        DataSelectAction action = controller.confirmSelection();
        assertEquals(DataSelectActionType.CLEAR_RESTART, action.type());
        assertEquals(90, action.zone());
        assertEquals(2, action.act());
        assertEquals(new SelectedTeam("knuckles", List.of()), action.team());
    }

    @Test
    void loadAvailableTeams_usesHostProfileForExtraTeams() {
        StubHostProfile hostProfile = new StubHostProfile();
        DataSelectSessionController controller = new DataSelectSessionController(hostProfile);

        controller.loadAvailableTeams("sonic,knuckles");

        assertEquals(List.of(
                new SelectedTeam("sonic", List.of()),
                new SelectedTeam("tails", List.of()),
                new SelectedTeam("sonic", List.of("knuckles"))
        ), controller.availableTeams());
        assertEquals("sonic,knuckles", hostProfile.lastExtraTeamsRaw);
    }

    @Test
    void controller_loadsSlotSummaries_andProducesLoadActionWithoutRendering() {
        StubHostProfile hostProfile = new StubHostProfile();
        DataSelectSessionController controller = new DataSelectSessionController(hostProfile);

        controller.loadAvailableTeams(null);
        controller.loadSlotSummaries(List.of(
                new SaveSlotSummary(1, SaveSlotState.VALID, Map.of(
                        "zone", 4,
                        "act", 0,
                        "mainCharacter", "sonic",
                        "sidekicks", List.of("tails")
                ))
        ));
        controller.menuModel().setSelectedRow(1);

        assertEquals(2, controller.slotSummaries().size());
        assertEquals(SaveSlotState.EMPTY, controller.slotSummaries().get(1).state());

        DataSelectAction action = controller.confirmSelection();

        assertEquals(DataSelectActionType.LOAD_SLOT, action.type());
        assertEquals(1, action.slot());
        assertEquals(4, action.zone());
        assertEquals(0, action.act());
        assertEquals(new SelectedTeam("sonic", List.of("tails")), action.team());
        assertSame(controller.menuModel(), controller.model());
    }

    @Test
    void hashWarningSlot_cannotLaunchOrClearRestart() {
        StubHostProfile hostProfile = new StubHostProfile();
        DataSelectSessionController controller = new DataSelectSessionController(hostProfile);

        controller.loadAvailableTeams(null);
        controller.loadSlotSummaries(List.of(
                new SaveSlotSummary(1, SaveSlotState.HASH_WARNING, Map.of(
                        "zone", 4,
                        "act", 0,
                        "clear", true,
                        "mainCharacter", "sonic",
                        "sidekicks", List.of("tails")
                ))
        ));
        controller.menuModel().setSelectedRow(1);

        assertEquals(List.of(), controller.currentClearRestartDestinations());

        DataSelectAction action = controller.confirmSelection();

        assertEquals(DataSelectActionType.NONE, action.type());
    }

    @Test
    void unavailableSlot_isPreservedAndCannotStartNewGameOverFile() {
        StubHostProfile hostProfile = new StubHostProfile();
        DataSelectSessionController controller = new DataSelectSessionController(hostProfile);

        controller.loadAvailableTeams(null);
        controller.loadSlotSummaries(List.of(SaveSlotSummary.unavailable(1)));
        controller.menuModel().setSelectedRow(1);

        assertEquals(SaveSlotState.UNAVAILABLE, controller.slotSummaries().get(0).state());
        assertEquals(List.of(), controller.currentClearRestartDestinations());

        DataSelectAction action = controller.confirmSelection();

        assertEquals(DataSelectActionType.NONE, action.type(),
                "Transiently unreadable save slots must not be treated as fresh slots");
    }

    @Test
    void presentationProvider_injectsControllerIntoDelegate_andConsumePendingActionDelegatesToController() {
        StubHostProfile hostProfile = new StubHostProfile();
        DataSelectSessionController controller = new DataSelectSessionController(hostProfile);
        DataSelectPresentationProvider presentationProvider =
                new DataSelectPresentationProvider(ignored -> new StubProvider(), controller);

        StubProvider delegate = (StubProvider) presentationProvider.delegate();
        DataSelectAction queued = new DataSelectAction(
                DataSelectActionType.LOAD_SLOT, 2, 5, 1, new SelectedTeam("tails", List.of()));
        controller.queuePendingAction(queued);

        assertSame(controller, delegate.getSessionController());
        assertEquals(queued, delegate.consumePendingAction());
        assertEquals(DataSelectActionType.NONE, delegate.consumePendingAction().type());
        assertTrue(delegate.controllerAttached);
    }

    @Test
    void deleteMode_isModalUntilARealSlotIsChosen() {
        StubHostProfile hostProfile = new StubHostProfile();
        DataSelectSessionController controller = new DataSelectSessionController(hostProfile);

        controller.loadAvailableTeams(null);
        controller.loadSlotSummaries(List.of(
                new SaveSlotSummary(1, SaveSlotState.VALID, Map.of(
                        "zone", 4,
                        "act", 0,
                        "mainCharacter", "sonic",
                        "sidekicks", List.of()
                )),
                SaveSlotSummary.empty(2)
        ));
        controller.menuModel().setSelectedRow(controller.deleteRowIndex());

        assertEquals(DataSelectActionType.NONE, controller.confirmSelection().type());
        assertTrue(controller.menuModel().isDeleteMode());

        controller.menuModel().setSelectedRow(0);
        assertEquals(DataSelectActionType.NONE, controller.confirmSelection().type());
        assertTrue(controller.menuModel().isDeleteMode());

        controller.menuModel().setSelectedRow(controller.deleteRowIndex());
        assertEquals(DataSelectActionType.NONE, controller.confirmSelection().type());
        assertTrue(controller.menuModel().isDeleteMode());

        controller.menuModel().setSelectedRow(1);
        DataSelectAction action = controller.confirmSelection();
        assertEquals(DataSelectActionType.DELETE_SLOT, action.type());
        assertEquals(1, action.slot());
    }

    @Test
    void presentationProvider_rejectsFactoryDelegatesThatCannotReceiveController() {
        StubHostProfile hostProfile = new StubHostProfile();
        DataSelectSessionController controller = new DataSelectSessionController(hostProfile);
        DataSelectPresentationProvider presentationProvider =
                new DataSelectPresentationProvider(ignored -> new NonAttachableProvider(), controller);

        IllegalStateException error = assertThrows(IllegalStateException.class, presentationProvider::delegate);
        assertTrue(error.getMessage().contains("controller"));
    }

    private static final class StubHostProfile implements DataSelectHostProfile {
        private final List<DataSelectDestination> clearRestartTargets = List.of(
                new DataSelectDestination(90, 2),
                new DataSelectDestination(91, 0)
        );
        private String lastExtraTeamsRaw;

        @Override
        public String gameCode() {
            return "stub";
        }

        @Override
        public int slotCount() {
            return 2;
        }

        @Override
        public List<SelectedTeam> builtInTeams() {
            return List.of(
                    new SelectedTeam("sonic", List.of()),
                    new SelectedTeam("tails", List.of())
            );
        }

        @Override
        public List<SelectedTeam> parseExtraTeams(String raw) {
            lastExtraTeamsRaw = raw;
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return List.of(new SelectedTeam("sonic", List.of("knuckles")));
        }

        @Override
        public SaveSlotSummary summarizeFreshSlot(int slot) {
            return SaveSlotSummary.empty(slot);
        }

        @Override
        public boolean isPayloadValid(Map<String, Object> payload) {
            return true;
        }

        @Override
        public List<DataSelectDestination> clearRestartDestinations(Map<String, Object> payload) {
            return Boolean.TRUE.equals(payload.get("clear")) ? clearRestartTargets : List.of();
        }
    }

    private static final class StubProvider extends AbstractDataSelectProvider {
        private boolean controllerAttached;

        @Override
        public void initialize() {
        }

        @Override
        public void update(com.openggf.control.InputHandler input) {
        }

        @Override
        public void draw() {
        }

        @Override
        public void setClearColor() {
        }

        @Override
        public void reset() {
        }

        @Override
        public State getState() {
            return State.INACTIVE;
        }

        @Override
        public boolean isExiting() {
            return false;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        protected void attachSessionController(DataSelectSessionController controller) {
            super.attachSessionController(controller);
            controllerAttached = true;
        }
    }

    private static final class NonAttachableProvider implements com.openggf.game.DataSelectProvider {
        @Override
        public void initialize() {
        }

        @Override
        public void update(com.openggf.control.InputHandler input) {
        }

        @Override
        public void draw() {
        }

        @Override
        public void setClearColor() {
        }

        @Override
        public void reset() {
        }

        @Override
        public State getState() {
            return State.INACTIVE;
        }

        @Override
        public boolean isExiting() {
            return false;
        }

        @Override
        public boolean isActive() {
            return false;
        }
    }
}
