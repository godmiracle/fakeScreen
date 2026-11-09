package top.chuyb.fakeScreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DisplayServiceRecoveryTest {
    @Test
    public void restoresBrightnessWhenDirectFakeOffLeftDisplayServiceOn() {
        FakeDisplay display = new FakeDisplay();

        DisplayServiceRecovery.Result result = DisplayServiceRecovery.recover(display::request);

        assertTrue(result.isSuccessful());
        assertTrue(display.logicalOn);
        assertTrue(display.panelOn);
        assertEquals(1_144, display.panelBrightness);
        assertEquals(2, display.requests);
    }

    @Test
    public void resetIsAttemptedEvenWhenOffFailsAfterChangingState() {
        FakeDisplay display = new FakeDisplay();
        display.throwAfterOff = true;

        DisplayServiceRecovery.Result result = DisplayServiceRecovery.recover(display::request);

        assertFalse(result.isSuccessful());
        assertNotNull(result.offError);
        assertTrue(result.resetAccepted);
        assertTrue(display.logicalOn);
        assertTrue(display.panelOn);
        assertEquals(1_144, display.panelBrightness);
    }

    @Test
    public void rejectedResetIsReportedAsFailure() {
        FakeDisplay display = new FakeDisplay();
        display.rejectReset = true;

        DisplayServiceRecovery.Result result = DisplayServiceRecovery.recover(display::request);

        assertTrue(result.offAccepted);
        assertFalse(result.resetAccepted);
        assertFalse(result.isSuccessful());
        assertEquals(2, display.requests);
    }

    @Test
    public void resetIsAttemptedEvenWhenOffThrowsAnError() {
        FakeDisplay display = new FakeDisplay();
        display.errorAfterOff = true;

        assertThrows(AssertionError.class, () -> DisplayServiceRecovery.recover(display::request));

        assertTrue(display.panelOn);
        assertEquals(1_144, display.panelBrightness);
        assertEquals(2, display.requests);
    }

    private static final class FakeDisplay {
        // 假熄屏直接关闭面板，显示服务仍缓存 ON 和原亮度。
        boolean logicalOn = true;
        boolean panelOn = false;
        int panelBrightness = 0;
        int requests;
        boolean throwAfterOff;
        boolean errorAfterOff;
        boolean rejectReset;

        boolean request(int state) {
            requests++;
            if (state == DisplayServiceRecovery.STATE_OFF) {
                logicalOn = false;
                panelOn = false;
                panelBrightness = 0;
                if (errorAfterOff) throw new AssertionError("OFF failed after changing state");
                if (throwAfterOff) throw new IllegalStateException("OFF failed after changing state");
                return true;
            }
            assertEquals(DisplayServiceRecovery.STATE_UNKNOWN, state);
            if (rejectReset) return false;
            logicalOn = true;
            panelOn = true;
            panelBrightness = 1_144;
            return true;
        }
    }
}
