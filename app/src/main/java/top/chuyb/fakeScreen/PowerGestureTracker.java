package top.chuyb.fakeScreen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** 根据实际按键事件识别假熄屏期间的双击和退出手势，不改变系统按键分发。 */
final class PowerGestureTracker {
    enum Key { POWER, VOLUME_UP }
    enum Result { NONE, WAKE, ESCAPE }

    private static final int MAX_RECENT_EVENTS = 32;
    private static final int MAX_POWER_PRESSES = 16;

    private final long doublePressTimeoutMs;
    private final long shortPressLimitMs;
    private final long escapeTimeoutMs;
    private final int escapePressCount;
    private final Deque<EventId> recentEvents = new ArrayDeque<>();
    private final Map<Long, PowerPress> powerPresses = new LinkedHashMap<>();

    private Press currentPower;
    private Press currentVolume;
    private long lastPowerUpTime = -1;
    private long escapeWindowStart = -1;
    private int volumePresses;

    PowerGestureTracker(long doublePressTimeoutMs, long shortPressLimitMs,
            long escapeTimeoutMs, int escapePressCount) {
        this.doublePressTimeoutMs = doublePressTimeoutMs;
        this.shortPressLimitMs = shortPressLimitMs;
        this.escapeTimeoutMs = escapeTimeoutMs;
        this.escapePressCount = escapePressCount;
    }

    synchronized Result onKeyEvent(Key key, boolean down, int deviceId, long downTime,
            long eventTime, int repeatCount, boolean canceled, boolean screenOff,
            boolean escapeEligible) {
        EventId id = new EventId(key, down, deviceId, downTime, eventTime, repeatCount, canceled);
        if (recentEvents.contains(id)) return Result.NONE;
        recentEvents.addLast(id);
        if (recentEvents.size() > MAX_RECENT_EVENTS) recentEvents.removeFirst();

        if (!screenOff) {
            lastPowerUpTime = -1;
        }
        if (down && repeatCount == 0) {
            if (key == Key.POWER) {
                resetEscape();
                if (currentVolume != null) currentVolume.repeated = true;
            } else {
                lastPowerUpTime = -1;
                if (currentPower != null) currentPower.repeated = true;
            }
        }
        if (key == Key.POWER) {
            return handlePower(down, deviceId, downTime, eventTime, repeatCount, canceled, screenOff);
        }
        return handleVolume(down, deviceId, downTime, eventTime, repeatCount, canceled, escapeEligible);
    }

    synchronized boolean isCompletedPowerPress(long eventTime) {
        PowerPress press = findPowerPress(eventTime);
        return press != null && press.completed;
    }

    synchronized boolean isFakeOffPowerPress(long eventTime) {
        PowerPress press = findPowerPress(eventTime);
        return press != null && press.fakeOff;
    }

    synchronized boolean isPowerPressHandled(long eventTime) {
        PowerPress press = findPowerPress(eventTime);
        return press != null && press.handled;
    }

    synchronized boolean claimCompletedPowerPress(long eventTime) {
        PowerPress press = findPowerPress(eventTime);
        if (press == null || !press.completed || press.handled) return false;
        press.handled = true;
        return true;
    }

    private PowerPress findPowerPress(long eventTime) {
        PowerPress byDownTime = powerPresses.get(eventTime);
        if (byDownTime != null) return byDownTime;
        // 不同 Android 版本可能把按下时间或松开时间传给 powerPress。
        for (PowerPress press : powerPresses.values()) {
            if (press.upTime == eventTime) return press;
        }
        return null;
    }

    synchronized void onPowerLongPress() {
        lastPowerUpTime = -1;
        if (currentPower != null) currentPower.repeated = true;
    }

    synchronized void onOtherKeyPressed() {
        lastPowerUpTime = -1;
        resetEscape();
        if (currentPower != null) currentPower.repeated = true;
        if (currentVolume != null) currentVolume.repeated = true;
    }

    synchronized void resetPendingGestures() {
        currentPower = null;
        currentVolume = null;
        lastPowerUpTime = -1;
        resetEscape();
        // 保留近期 powerPress 对应关系，防止系统延迟回调在恢复后再次熄屏。
    }

    private Result handlePower(boolean down, int deviceId, long downTime, long eventTime,
            int repeatCount, boolean canceled, boolean screenOff) {
        if (down) {
            if (repeatCount > 0) {
                if (matches(currentPower, deviceId, downTime)) currentPower.repeated = true;
                lastPowerUpTime = -1;
                return Result.NONE;
            }
            currentPower = new Press(deviceId, downTime);
            powerPresses.put(downTime, new PowerPress(screenOff));
            if (powerPresses.size() > MAX_POWER_PRESSES) {
                powerPresses.remove(powerPresses.keySet().iterator().next());
            }
            return Result.NONE;
        }

        if (!matches(currentPower, deviceId, downTime)) return Result.NONE;
        Press press = currentPower;
        currentPower = null;
        PowerPress observed = powerPresses.get(downTime);
        boolean shortPress = isShortPress(press, eventTime, canceled);
        if (observed != null) {
            observed.upTime = eventTime;
            observed.completed = shortPress;
            // 按下时仍亮、松开时已进入假熄屏，也属于熄屏后的恢复手势。
            // 保留身份，避免延迟到亮屏后的 powerPress 再次触发熄屏。
            if (screenOff && shortPress) observed.fakeOff = true;
        }
        if (!screenOff || !shortPress) {
            lastPowerUpTime = -1;
            return Result.NONE;
        }
        if (lastPowerUpTime >= 0 && downTime >= lastPowerUpTime
                && downTime - lastPowerUpTime <= doublePressTimeoutMs) {
            lastPowerUpTime = -1;
            return Result.WAKE;
        }
        lastPowerUpTime = eventTime;
        return Result.NONE;
    }

    private Result handleVolume(boolean down, int deviceId, long downTime, long eventTime,
            int repeatCount, boolean canceled, boolean escapeEligible) {
        if (!escapeEligible) {
            currentVolume = null;
            resetEscape();
            return Result.NONE;
        }
        if (down) {
            if (repeatCount > 0) {
                if (matches(currentVolume, deviceId, downTime)) currentVolume.repeated = true;
                return Result.NONE;
            }
            currentVolume = new Press(deviceId, downTime);
            return Result.NONE;
        }
        if (!matches(currentVolume, deviceId, downTime)) return Result.NONE;
        Press press = currentVolume;
        currentVolume = null;
        if (!isShortPress(press, eventTime, canceled)) return Result.NONE;
        if (escapeWindowStart < 0 || eventTime < escapeWindowStart
                || eventTime - escapeWindowStart > escapeTimeoutMs) {
            escapeWindowStart = eventTime;
            volumePresses = 0;
        }
        if (++volumePresses >= escapePressCount) {
            resetEscape();
            return Result.ESCAPE;
        }
        return Result.NONE;
    }

    private boolean isShortPress(Press press, long eventTime, boolean canceled) {
        return !canceled && !press.repeated && eventTime >= press.downTime
                && eventTime - press.downTime < shortPressLimitMs;
    }

    private static boolean matches(Press press, int deviceId, long downTime) {
        return press != null && press.deviceId == deviceId && press.downTime == downTime;
    }

    private void resetEscape() {
        escapeWindowStart = -1;
        volumePresses = 0;
    }

    private static final class Press {
        final int deviceId;
        final long downTime;
        boolean repeated;

        Press(int deviceId, long downTime) {
            this.deviceId = deviceId;
            this.downTime = downTime;
        }
    }

    private static final class PowerPress {
        boolean fakeOff;
        long upTime = -1;
        boolean completed;
        boolean handled;

        PowerPress(boolean startedWhileOff) {
            this.fakeOff = startedWhileOff;
        }
    }

    private static final class EventId {
        final Key key;
        final boolean down;
        final int deviceId;
        final long downTime;
        final long eventTime;
        final int repeatCount;
        final boolean canceled;

        EventId(Key key, boolean down, int deviceId, long downTime, long eventTime,
                int repeatCount, boolean canceled) {
            this.key = key;
            this.down = down;
            this.deviceId = deviceId;
            this.downTime = downTime;
            this.eventTime = eventTime;
            this.repeatCount = repeatCount;
            this.canceled = canceled;
        }

        @Override public boolean equals(Object object) {
            if (!(object instanceof EventId)) return false;
            EventId other = (EventId) object;
            return key == other.key && down == other.down && deviceId == other.deviceId
                    && downTime == other.downTime && eventTime == other.eventTime
                    && repeatCount == other.repeatCount && canceled == other.canceled;
        }

        @Override public int hashCode() {
            int result = key.hashCode();
            result = 31 * result + Boolean.hashCode(down);
            result = 31 * result + deviceId;
            result = 31 * result + Long.hashCode(downTime);
            result = 31 * result + Long.hashCode(eventTime);
            result = 31 * result + repeatCount;
            return 31 * result + Boolean.hashCode(canceled);
        }
    }
}
