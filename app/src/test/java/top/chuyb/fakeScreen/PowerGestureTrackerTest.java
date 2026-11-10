package top.chuyb.fakeScreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PowerGestureTrackerTest {
    private final PowerGestureTracker tracker = new PowerGestureTracker(400, 500, 10_000, 5);

    private PowerGestureTracker.Result event(PowerGestureTracker.Key key, boolean down,
            long downTime, long eventTime, int repeat, boolean canceled, boolean screenOff) {
        return tracker.onKeyEvent(key, down, 7, downTime, eventTime, repeat, canceled,
                screenOff, screenOff);
    }

    private PowerGestureTracker.Result press(PowerGestureTracker.Key key, long downTime,
            long upTime, boolean screenOff) {
        assertEquals(PowerGestureTracker.Result.NONE,
                event(key, true, downTime, downTime, 0, false, screenOff));
        return event(key, false, downTime, upTime, 0, false, screenOff);
    }

    @Test
    public void twoSeparateShortPowerPressesWakeWhileFakeScreenIsOff() {
        assertEquals(PowerGestureTracker.Result.NONE,
                press(PowerGestureTracker.Key.POWER, 1_000, 1_050, true));
        assertEquals(PowerGestureTracker.Result.WAKE,
                press(PowerGestureTracker.Key.POWER, 1_180, 1_230, true));
        assertTrue(tracker.isFakeOffPowerPress(1_000));
        assertTrue(tracker.isFakeOffPowerPress(1_180));
        assertTrue(tracker.isFakeOffPowerPress(1_230));
    }

    @Test
    public void pressCrossingIntoFakeOffCountsAsFirstWakePress() {
        event(PowerGestureTracker.Key.POWER, true, 1_000, 1_000, 0, false, false);
        assertEquals(PowerGestureTracker.Result.NONE,
                event(PowerGestureTracker.Key.POWER, false, 1_000, 1_150, 0, false, true));

        assertTrue(tracker.isFakeOffPowerPress(1_000));
        assertEquals(PowerGestureTracker.Result.WAKE,
                press(PowerGestureTracker.Key.POWER, 1_250, 1_300, true));
        tracker.resetPendingGestures();
        assertTrue(tracker.isFakeOffPowerPress(1_000));
    }

    @Test
    public void canceledCrossingPressCannotStartWakeGesture() {
        event(PowerGestureTracker.Key.POWER, true, 1_000, 1_000, 0, false, false);
        assertEquals(PowerGestureTracker.Result.NONE,
                event(PowerGestureTracker.Key.POWER, false, 1_000, 1_150, 0, true, true));

        assertFalse(tracker.isFakeOffPowerPress(1_000));
        assertEquals(PowerGestureTracker.Result.NONE,
                press(PowerGestureTracker.Key.POWER, 1_250, 1_300, true));
        assertEquals(PowerGestureTracker.Result.WAKE,
                press(PowerGestureTracker.Key.POWER, 1_400, 1_450, true));
    }

    @Test
    public void duplicateHooksDoNotTurnOnePressIntoTwo() {
        event(PowerGestureTracker.Key.POWER, true, 1_000, 1_000, 0, false, true);
        event(PowerGestureTracker.Key.POWER, true, 1_000, 1_000, 0, false, true);
        assertEquals(PowerGestureTracker.Result.NONE,
                event(PowerGestureTracker.Key.POWER, false, 1_000, 1_050, 0, false, true));
        assertEquals(PowerGestureTracker.Result.NONE,
                event(PowerGestureTracker.Key.POWER, false, 1_000, 1_050, 0, false, true));
        assertEquals(PowerGestureTracker.Result.WAKE,
                press(PowerGestureTracker.Key.POWER, 1_120, 1_170, true));
    }

    @Test
    public void canceledAndLongPressesAreNotCountedAsShortClicks() {
        event(PowerGestureTracker.Key.POWER, true, 1_000, 1_000, 0, false, true);
        event(PowerGestureTracker.Key.POWER, false, 1_000, 1_050, 0, true, true);
        assertEquals(PowerGestureTracker.Result.NONE,
                press(PowerGestureTracker.Key.POWER, 1_150, 1_200, true));

        event(PowerGestureTracker.Key.POWER, true, 1_300, 1_300, 0, false, true);
        event(PowerGestureTracker.Key.POWER, true, 1_300, 1_600, 1, false, true);
        tracker.onPowerLongPress();
        assertEquals(PowerGestureTracker.Result.NONE,
                event(PowerGestureTracker.Key.POWER, false, 1_300, 1_900, 0, false, true));
        assertEquals(PowerGestureTracker.Result.NONE,
                press(PowerGestureTracker.Key.POWER, 2_000, 2_050, true));
        assertEquals(PowerGestureTracker.Result.WAKE,
                press(PowerGestureTracker.Key.POWER, 2_150, 2_200, true));
    }

    @Test
    public void slowPowerPressesDoNotWakeAndLitPressIsNotAFakeOffPress() {
        press(PowerGestureTracker.Key.POWER, 1_000, 1_050, false);
        assertTrue(tracker.isCompletedPowerPress(1_000));
        assertTrue(tracker.isCompletedPowerPress(1_050));
        assertFalse(tracker.isFakeOffPowerPress(1_000));
        assertFalse(tracker.isCompletedPowerPress(999));
        assertTrue(tracker.claimCompletedPowerPress(1_050));
        assertFalse(tracker.claimCompletedPowerPress(1_000));
        assertFalse(tracker.claimCompletedPowerPress(999));
        assertTrue(tracker.isPowerPressHandled(1_000));
        assertFalse(tracker.isPowerPressHandled(999));
        assertEquals(PowerGestureTracker.Result.NONE,
                press(PowerGestureTracker.Key.POWER, 2_000, 2_050, true));
        assertEquals(PowerGestureTracker.Result.NONE,
                press(PowerGestureTracker.Key.POWER, 3_000, 3_050, true));
    }

    @Test
    public void fiveVolumeUpPressesEscapeEvenWithDuplicateCallbacks() {
        for (int i = 0; i < 5; i++) {
            long time = 1_000 + i * 300;
            event(PowerGestureTracker.Key.VOLUME_UP, true, time, time, 0, false, true);
            event(PowerGestureTracker.Key.VOLUME_UP, true, time, time, 0, false, true);
            PowerGestureTracker.Result result =
                    event(PowerGestureTracker.Key.VOLUME_UP, false, time, time + 50, 0, false, true);
            assertEquals(i == 4 ? PowerGestureTracker.Result.ESCAPE : PowerGestureTracker.Result.NONE,
                    result);
            assertEquals(PowerGestureTracker.Result.NONE,
                    event(PowerGestureTracker.Key.VOLUME_UP, false, time, time + 50, 0, false, true));
        }
    }

    @Test
    public void escapeWindowExpiresAndVolumePressesWhileLitAreIgnored() {
        for (int i = 0; i < 4; i++) {
            assertEquals(PowerGestureTracker.Result.NONE,
                    press(PowerGestureTracker.Key.VOLUME_UP, 1_000 + i * 200, 1_050 + i * 200, true));
        }
        assertEquals(PowerGestureTracker.Result.NONE,
                press(PowerGestureTracker.Key.VOLUME_UP, 12_000, 12_050, true));
        for (int i = 0; i < 5; i++) {
            assertEquals(PowerGestureTracker.Result.NONE,
                    press(PowerGestureTracker.Key.VOLUME_UP, 13_000 + i * 200, 13_050 + i * 200, false));
        }
    }

    @Test
    public void resetKeepsDarkPressIdentityForDelayedFrameworkCallbacks() {
        press(PowerGestureTracker.Key.POWER, 1_000, 1_050, true);
        tracker.resetPendingGestures();
        assertTrue(tracker.isFakeOffPowerPress(1_000));
        assertEquals(PowerGestureTracker.Result.NONE,
                press(PowerGestureTracker.Key.POWER, 1_200, 1_250, true));
    }

    @Test
    public void escapeCanBeRetriedAfterDisplayRequestWhenRecoveryIsUnconfirmed() {
        for (int i = 0; i < 5; i++) {
            long time = 1_000 + i * 300;
            tracker.onKeyEvent(PowerGestureTracker.Key.VOLUME_UP, true, 7, time, time,
                    0, false, false, true);
            PowerGestureTracker.Result result = tracker.onKeyEvent(PowerGestureTracker.Key.VOLUME_UP,
                    false, 7, time, time + 50, 0, false, false, true);
            assertEquals(i == 4 ? PowerGestureTracker.Result.ESCAPE : PowerGestureTracker.Result.NONE,
                    result);
        }
    }

    @Test
    public void anotherKeyInterruptsVolumeEscapeAndPowerDoublePress() {
        for (int i = 0; i < 4; i++) {
            press(PowerGestureTracker.Key.VOLUME_UP, 1_000 + i * 200, 1_050 + i * 200, true);
        }
        tracker.onOtherKeyPressed();
        assertEquals(PowerGestureTracker.Result.NONE,
                press(PowerGestureTracker.Key.VOLUME_UP, 1_900, 1_950, true));
        press(PowerGestureTracker.Key.POWER, 2_000, 2_050, true);
        tracker.onOtherKeyPressed();
        assertEquals(PowerGestureTracker.Result.NONE,
                press(PowerGestureTracker.Key.POWER, 2_150, 2_200, true));
    }

    @Test
    public void volumeLongPressDoesNotCountAsMultipleEscapePresses() {
        event(PowerGestureTracker.Key.VOLUME_UP, true, 1_000, 1_000, 0, false, true);
        event(PowerGestureTracker.Key.VOLUME_UP, true, 1_000, 1_300, 1, false, true);
        event(PowerGestureTracker.Key.VOLUME_UP, false, 1_000, 1_600, 0, false, true);
        for (int i = 0; i < 4; i++) {
            assertEquals(PowerGestureTracker.Result.NONE,
                    press(PowerGestureTracker.Key.VOLUME_UP, 2_000 + i * 200, 2_050 + i * 200, true));
        }
        assertEquals(PowerGestureTracker.Result.ESCAPE,
                press(PowerGestureTracker.Key.VOLUME_UP, 3_000, 3_050, true));
    }
}
