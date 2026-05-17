package com.wukong.agent.statemachine;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class BusinessStateMachineTest {

    private BusinessStateMachine stateMachine;

    @Before
    public void setUp() {
        stateMachine = new BusinessStateMachine();
    }

    @Test
    public void testInitialStateIsIdle() {
        assertEquals(BusinessState.IDLE, stateMachine.getCurrentState());
    }

    @Test
    public void testValidTransitionIdleToWakeup() {
        assertTrue(stateMachine.transitionTo(BusinessState.WAKEUP));
        assertEquals(BusinessState.WAKEUP, stateMachine.getCurrentState());
    }

    @Test
    public void testInvalidTransitionIdleToRecording() {
        assertFalse(stateMachine.transitionTo(BusinessState.RECORDING));
        assertEquals(BusinessState.IDLE, stateMachine.getCurrentState());
    }

    @Test
    public void testValidFlowIdleToPlaying() {
        stateMachine.transitionTo(BusinessState.WAKEUP);
        stateMachine.transitionTo(BusinessState.RECORDING);
        stateMachine.transitionTo(BusinessState.PROCESSING);
        assertTrue(stateMachine.transitionTo(BusinessState.PLAYING));
        assertEquals(BusinessState.PLAYING, stateMachine.getCurrentState());
    }

    @Test
    public void testPlayingToIdle() {
        stateMachine.transitionTo(BusinessState.WAKEUP);
        stateMachine.transitionTo(BusinessState.RECORDING);
        stateMachine.transitionTo(BusinessState.PROCESSING);
        stateMachine.transitionTo(BusinessState.PLAYING);
        assertTrue(stateMachine.transitionTo(BusinessState.IDLE));
        assertEquals(BusinessState.IDLE, stateMachine.getCurrentState());
    }

    @Test
    public void testPlayingInterrupt() {
        stateMachine.transitionTo(BusinessState.WAKEUP);
        stateMachine.transitionTo(BusinessState.RECORDING);
        stateMachine.transitionTo(BusinessState.PROCESSING);
        stateMachine.transitionTo(BusinessState.PLAYING);
        // Interrupt: PLAYING -> RECORDING
        assertTrue(stateMachine.transitionTo(BusinessState.RECORDING));
        assertEquals(BusinessState.RECORDING, stateMachine.getCurrentState());
    }

    @Test
    public void testForceTransition() {
        // Force transition should work even for invalid transitions
        assertTrue(stateMachine.forceTransitionTo(BusinessState.PLAYING, "test"));
        assertEquals(BusinessState.PLAYING, stateMachine.getCurrentState());
    }

    @Test
    public void testSameStateTransitionFails() {
        assertFalse(stateMachine.transitionTo(BusinessState.IDLE));
    }

    @Test
    public void testStateChangeListener() {
        final boolean[] notified = {false};
        final BusinessState[] capturedOld = new BusinessState[1];
        final BusinessState[] capturedNew = new BusinessState[1];

        stateMachine.addListener(new StateChangeListener() {
            @Override
            public void onStateChanged(BusinessState oldState, BusinessState newState) {
                notified[0] = true;
                capturedOld[0] = oldState;
                capturedNew[0] = newState;
            }

            @Override
            public void onStateError(BusinessState state, String errorMessage) {}
        });

        stateMachine.transitionTo(BusinessState.WAKEUP);
        assertTrue(notified[0]);
        assertEquals(BusinessState.IDLE, capturedOld[0]);
        assertEquals(BusinessState.WAKEUP, capturedNew[0]);
    }

    @Test
    public void testRemoveListener() {
        final int[] count = {0};
        StateChangeListener listener = new StateChangeListener() {
            @Override
            public void onStateChanged(BusinessState oldState, BusinessState newState) {
                count[0]++;
            }

            @Override
            public void onStateError(BusinessState state, String errorMessage) {}
        };

        stateMachine.addListener(listener);
        stateMachine.transitionTo(BusinessState.WAKEUP);
        assertEquals(1, count[0]);

        stateMachine.removeListener(listener);
        stateMachine.forceTransitionTo(BusinessState.IDLE, "test");
        stateMachine.transitionTo(BusinessState.WAKEUP);
        assertEquals(1, count[0]); // Should not have been called again
    }

    @Test
    public void testWakeupToIdle() {
        stateMachine.transitionTo(BusinessState.WAKEUP);
        assertTrue(stateMachine.transitionTo(BusinessState.IDLE));
        assertEquals(BusinessState.IDLE, stateMachine.getCurrentState());
    }

    @Test
    public void testRecordingToIdle() {
        stateMachine.transitionTo(BusinessState.WAKEUP);
        stateMachine.transitionTo(BusinessState.RECORDING);
        assertTrue(stateMachine.transitionTo(BusinessState.IDLE));
        assertEquals(BusinessState.IDLE, stateMachine.getCurrentState());
    }

    @Test
    public void testProcessingToIdle() {
        stateMachine.transitionTo(BusinessState.WAKEUP);
        stateMachine.transitionTo(BusinessState.RECORDING);
        stateMachine.transitionTo(BusinessState.PROCESSING);
        assertTrue(stateMachine.transitionTo(BusinessState.IDLE));
        assertEquals(BusinessState.IDLE, stateMachine.getCurrentState());
    }

    @Test
    public void testTimeoutConfiguration() {
        stateMachine.setRecordingTimeoutMs(5000);
        stateMachine.setProcessingTimeoutMs(3000);
        stateMachine.setPlayingTimeoutMs(10000);
        // No assertion needed - just verify no exceptions
    }

    @Test
    public void testCleanup() {
        stateMachine.addListener(new StateChangeListener() {
            @Override
            public void onStateChanged(BusinessState oldState, BusinessState newState) {}
            @Override
            public void onStateError(BusinessState state, String errorMessage) {}
        });
        stateMachine.cleanup();
        // Should not crash
    }
}
