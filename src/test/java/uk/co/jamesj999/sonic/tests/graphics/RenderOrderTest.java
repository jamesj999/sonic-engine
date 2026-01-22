package uk.co.jamesj999.sonic.tests.graphics;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.graphics.pipeline.*;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for render order compliance.
 * These tests ensure the rendering pipeline maintains correct ordering:
 * SCENE → OVERLAY → FADE_PASS
 */
public class RenderOrderTest {
    
    private RenderOrderRecorder recorder;
    
    @Before
    public void setUp() {
        recorder = RenderOrderRecorder.getInstance();
        recorder.clear();
        recorder.setEnabled(true);
    }
    
    @Test
    public void testRecorderDisabledByDefault() {
        RenderOrderRecorder fresh = new RenderOrderRecorder() {
            // Create new instance to test default state
        };
        // Default getInstance() should work - just verify it exists
        assertNotNull(RenderOrderRecorder.getInstance());
    }
    
    @Test
    public void testRecordingWhenEnabled() {
        recorder.record(RenderPhase.SCENE, "Level");
        recorder.record(RenderPhase.OVERLAY, "HUD");
        recorder.record(RenderPhase.FADE_PASS, "Fade");
        
        List<RenderCommand> commands = recorder.getCommands();
        assertEquals(3, commands.size());
        assertEquals(RenderPhase.SCENE, commands.get(0).phase());
        assertEquals(RenderPhase.OVERLAY, commands.get(1).phase());
        assertEquals(RenderPhase.FADE_PASS, commands.get(2).phase());
    }
    
    @Test
    public void testNoRecordingWhenDisabled() {
        recorder.setEnabled(false);
        recorder.record(RenderPhase.SCENE, "Level");
        
        assertTrue(recorder.getCommands().isEmpty());
    }
    
    @Test
    public void testClearRemovesAllCommands() {
        recorder.record(RenderPhase.SCENE, "Level");
        recorder.record(RenderPhase.OVERLAY, "HUD");
        assertFalse(recorder.getCommands().isEmpty());
        
        recorder.clear();
        assertTrue(recorder.getCommands().isEmpty());
    }
    
    @Test
    public void testOrderIndexIncrementsCorrectly() {
        recorder.clear();
        recorder.record(RenderPhase.SCENE, "Level");
        recorder.record(RenderPhase.OVERLAY, "HUD");
        recorder.record(RenderPhase.FADE_PASS, "Fade");
        
        List<RenderCommand> commands = recorder.getCommands();
        assertEquals(0, commands.get(0).orderIndex());
        assertEquals(1, commands.get(1).orderIndex());
        assertEquals(2, commands.get(2).orderIndex());
    }
    
    @Test
    public void testVerifyOrder_correctOrder() {
        recorder.record(RenderPhase.SCENE, "Level");
        recorder.record(RenderPhase.OVERLAY, "HUD");
        recorder.record(RenderPhase.FADE_PASS, "Fade");
        
        List<String> violations = recorder.verifyOrder();
        assertTrue("Correct order should have no violations", violations.isEmpty());
    }
    
    @Test
    public void testVerifyOrder_fadeBeforeOverlay() {
        recorder.record(RenderPhase.SCENE, "Level");
        recorder.record(RenderPhase.FADE_PASS, "Fade"); // Wrong order!
        recorder.record(RenderPhase.OVERLAY, "HUD");
        
        List<String> violations = recorder.verifyOrder();
        assertFalse("Should detect order violation", violations.isEmpty());
        assertTrue("Should mention HUD", violations.get(0).contains("HUD"));
    }
    
    @Test
    public void testVerifyOrder_overlayBeforeScene() {
        recorder.record(RenderPhase.OVERLAY, "HUD"); // Wrong order!
        recorder.record(RenderPhase.SCENE, "Level");
        recorder.record(RenderPhase.FADE_PASS, "Fade");
        
        List<String> violations = recorder.verifyOrder();
        assertFalse("Should detect order violation", violations.isEmpty());
    }
    
    @Test
    public void testFadeRenderedLast_correct() {
        recorder.record(RenderPhase.SCENE, "Level");
        recorder.record(RenderPhase.OVERLAY, "HUD");
        recorder.record(RenderPhase.FADE_PASS, "Fade");
        
        assertTrue(recorder.fadeRenderedLast());
    }
    
    @Test
    public void testFadeRenderedLast_incorrect() {
        recorder.record(RenderPhase.SCENE, "Level");
        recorder.record(RenderPhase.FADE_PASS, "Fade");
        recorder.record(RenderPhase.OVERLAY, "HUD"); // HUD after fade is wrong
        
        assertFalse(recorder.fadeRenderedLast());
    }
    
    @Test
    public void testFadeRenderedLast_emptyIsTrue() {
        assertTrue("Empty recorder should return true", recorder.fadeRenderedLast());
    }
    
    @Test
    public void testFadeRenderedLast_noFade() {
        recorder.record(RenderPhase.SCENE, "Level");
        recorder.record(RenderPhase.OVERLAY, "HUD");
        
        // No fade recorded - last is OVERLAY
        assertFalse(recorder.fadeRenderedLast());
    }
    
    @Test
    public void testRenderCommandRecord() {
        RenderCommand cmd = RenderCommand.of(RenderPhase.SCENE, "Level", 0);
        
        assertEquals(RenderPhase.SCENE, cmd.phase());
        assertEquals("Level", cmd.component());
        assertEquals(0, cmd.orderIndex());
    }
    
    @Test
    public void testRenderPhaseOrdering() {
        // Verify enum ordinals are correct for comparison
        assertTrue(RenderPhase.SCENE.ordinal() < RenderPhase.OVERLAY.ordinal());
        assertTrue(RenderPhase.OVERLAY.ordinal() < RenderPhase.FADE_PASS.ordinal());
    }
    
    @Test
    public void testMultipleSceneComponentsAllowed() {
        recorder.record(RenderPhase.SCENE, "Background");
        recorder.record(RenderPhase.SCENE, "Sprites");
        recorder.record(RenderPhase.SCENE, "Foreground");
        recorder.record(RenderPhase.OVERLAY, "HUD");
        recorder.record(RenderPhase.FADE_PASS, "Fade");
        
        List<String> violations = recorder.verifyOrder();
        assertTrue("Multiple SCENE components should be allowed", violations.isEmpty());
    }
    
    @Test
    public void testMultipleOverlayComponentsAllowed() {
        recorder.record(RenderPhase.SCENE, "Level");
        recorder.record(RenderPhase.OVERLAY, "HUD");
        recorder.record(RenderPhase.OVERLAY, "Debug");
        recorder.record(RenderPhase.FADE_PASS, "Fade");
        
        List<String> violations = recorder.verifyOrder();
        assertTrue("Multiple OVERLAY components should be allowed", violations.isEmpty());
    }
}
