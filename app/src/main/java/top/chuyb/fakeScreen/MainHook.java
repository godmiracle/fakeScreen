package top.chuyb.fakeScreen;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Xposed 模块入口：安装 hook，并决定电源键被按下时如何处理。所有与 hook 无关的逻辑都放在各自的类中：
 * {@link FakeScreenIpc}（IPC / 状态同步）、{@link DisplayPowerControl}（显示电源模式）、
 * {@link ScreenTimeoutController}（屏幕超时）与 {@link ReflectUtils}（反射工具）。
 *
 * @since 1.0
 * @author chuyb-re
 */
public class MainHook extends XposedModule {
    private static final Handler mHandler = new Handler(Looper.getMainLooper());
    private static final Object POWER_TASK_LOCK = new Object();
    private static Runnable mPendingPowerTask = null;
    private static final long MULTI_PRESS_TIMEOUT_MS = 220; // 多击防抖超时
    private static final long DOUBLE_PRESS_TIMEOUT_MS = 400;
    private static final long SHORT_PRESS_LIMIT_MS = 500;
    private static final long ESCAPE_WINDOW_MS = 10_000;
    private static final long RECOVERY_RETRY_WINDOW_MS = 60_000;
    private final PowerGestureTracker mGestures = new PowerGestureTracker(
            DOUBLE_PRESS_TIMEOUT_MS, SHORT_PRESS_LIMIT_MS, ESCAPE_WINDOW_MS, 5);
    private final Set<Method> mHookedMethods = new HashSet<>();
    private ClassLoader mSystemClassLoader;
    private volatile long mRecoveryRetryUntil;

    private final String PREFS_NAME ="FakeScreenSetting";

    /**
     * system_server 正在初始化时调用的入口。
     *
     * @param param Xposed 框架提供的 SystemServerStartingParam，其中包含 ClassLoader。
     */
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        super.onSystemServerStarting(param);
        XLog.init(this);
        XLog.log(Log.INFO, "system_server starting; Android API=" + Build.VERSION.SDK_INT);
        ClassLoader classLoader = param.getClassLoader();
        mSystemClassLoader = classLoader;
        try {
            Class<?> pwmClass = classLoader.loadClass("com.android.server.policy.PhoneWindowManager");
            // 1. 动态注册 IPC 接收器
            registerIpcReceiver(pwmClass);
            // 2. hook 电源键处理
            installPwmHooks(pwmClass, classLoader);
            hookPowerRuleLongPress(classLoader);
            //3. 初始化兼容模式设置
            SharedPreferences remotePreferences = getRemotePreferences(PREFS_NAME);
            DisplayPowerControl.useCompatibilityMode =remotePreferences.getBoolean("CompatibilityMode",false);
            //4. 注册监听器
            remotePreferences.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
                if("CompatibilityMode".equals(key)){
                    DisplayPowerControl.useCompatibilityMode=sharedPreferences.getBoolean("CompatibilityMode",false);
                }
            });
        } catch (Exception e) {
            XLog.error("MainHook initialization error",e);
        }
    }

    // =========================================================================
    // 1. IPC 接收器 hook
    // =========================================================================

    /**
     * hook PhoneWindowManager.init，以便在实例（及其系统 Context）就绪后注册 IPC 广播接收器。
     *
     * @param pwmClass PhoneWindowManager 的 Class 引用。
     */
    private void registerIpcReceiver(Class<?> pwmClass) {
        Method initMethod = ReflectUtils.findMethodByName(pwmClass, "init");
        if (initMethod == null) {
            XLog.log(Log.ERROR, "PhoneWindowManager.init was not found; toggle receiver will not be registered.");
            return;
        }
        initMethod.setAccessible(true);
        hook(initMethod).intercept(chain -> {
            Object result = chain.proceed();
            Object pwmInstance = chain.getThisObject();
            if (pwmInstance != null && pwmInstance.getClass() != pwmClass) {
                Class<?> actualClass = pwmInstance.getClass();
                XLog.log(Log.INFO, "PhoneWindowManager runtime class: " + actualClass.getName());
                // 各种方法分别安装，子类没有覆写 powerPress 时也不能跳过上游按键 hook。
                while (actualClass != null && actualClass != pwmClass) {
                    installPwmHooks(actualClass, mSystemClassLoader);
                    actualClass = actualClass.getSuperclass();
                }
            }
            FakeScreenIpc.attach(this, pwmClass, pwmInstance, this::onFakeScreenDisabled);
            return result;
        });
        XLog.log(Log.INFO, "Hooked PhoneWindowManager.init: " + initMethod.toGenericString());
    }

    // =========================================================================
    // 2. 电源键 hook 与单击/双击处理
    // =========================================================================

    private void installPwmHooks(Class<?> clazz, ClassLoader classLoader) {
        try {
            hookPowerButton(clazz, classLoader);
        } catch (Throwable error) {
            XLog.log(Log.ERROR, "Cannot inspect powerPress in " + clazz.getName() + ": " + error);
        }
        try {
            hookKeyEvents(clazz);
        } catch (Throwable error) {
            XLog.log(Log.ERROR, "Cannot inspect key events in " + clazz.getName() + ": " + error);
        }
        try {
            hookLongPresses(clazz);
        } catch (Throwable error) {
            XLog.log(Log.WARN, "Cannot inspect long presses in " + clazz.getName() + ": " + error);
        }
    }

    /**
     * hook PhoneWindowManager 中的 powerPress 方法，处理 FakeScreen 单击熄屏和双击唤醒。
     *
     * @param pwmClass    PhoneWindowManager 的 Class 引用。
     * @param classLoader 用于加载系统类的 ClassLoader。
     */
    private void hookPowerButton(Class<?> pwmClass, ClassLoader classLoader) {
        Method powerPress = findPowerPressMethod(pwmClass);
        if (powerPress == null) {
            XLog.log(Log.DEBUG, "No declared compatible powerPress in " + pwmClass.getName());
            return;
        }
        if (!beginHook(powerPress)) return;
        try {
            powerPress.setAccessible(true);
            hook(powerPress).intercept(chain -> {
                int count = extractClickCount(chain.getArgs());
                long powerEventTime = extractPowerEventTime(chain.getArgs());
                boolean enabled = FakeScreenIpc.isEnabled();
                boolean screenOff = DisplayPowerControl.isScreenOffByFakeScreen();
                XLog.log(Log.INFO, "powerPress: count=" + count + ", eventTime=" + powerEventTime
                        + ", enabled=" + enabled + ", screenOffByFakeScreen=" + screenOff);
                XLog.log(Log.DEBUG, "powerPress arguments: " + describeArguments(chain.getArgs()));

                if (count > 2) {
                    // 三击及以上交还系统，包括紧急功能；先尝试恢复面板。
                    restoreDisplayNow("system multi-press");
                    return chain.proceed();
                }
                if (!enabled && screenOff) {
                    restoreDisplayNow("power press after disable");
                    return chain.proceed();
                }
                if (screenOff && count == 2) {
                    XLog.log(Log.INFO, "System reported double power press while display was off.");
                    scheduleScreenModeChange(false, classLoader, 0);
                    return null;
                }
                if (powerEventTime >= 0 && mGestures.isFakeOffPowerPress(powerEventTime)) {
                    // 上游已处理的旧按键即使在面板恢复后才回调，也不能再次触发熄屏。
                    XLog.log(Log.INFO, "Consumed delayed powerPress originating while FakeScreen was off.");
                    return null;
                }
                if (count == 1 && powerEventTime >= 0 && mGestures.isPowerPressHandled(powerEventTime)) {
                    XLog.log(Log.INFO, "Consumed duplicate powerPress for an already handled key event.");
                    return null;
                }
                if (!enabled) {
                    return chain.proceed();
                }
                if (count == 2) {
                    cancelPendingPowerTask();
                    return chain.proceed(); // 亮屏时保留系统双击动作。
                }
                if (count == 1) {
                    if (screenOff) return null;
                    if (powerEventTime < 0 || !mGestures.claimCompletedPowerPress(powerEventTime)) {
                        if (powerEventTime >= 0 && mGestures.isPowerPressHandled(powerEventTime)) return null;
                        // 看不到上游完整事件时不进入假熄屏，避免没有可用的按键恢复路径。
                        cancelPendingPowerTask();
                        XLog.log(Log.WARN, "Power event not observed upstream; passing through for safety.");
                        return chain.proceed();
                    }
                    scheduleScreenModeChange(true, classLoader, MULTI_PRESS_TIMEOUT_MS);
                    return null;
                }
                return chain.proceed();
            });
            XLog.log(Log.INFO, "Hooked powerPress: " + powerPress.toGenericString());
        } catch (Throwable error) {
            forgetHook(powerPress);
            XLog.log(Log.ERROR, "Failed to hook powerPress " + powerPress + ": " + error);
        }
    }

    private void hookKeyEvents(Class<?> pwmClass) {
        for (Method method : pwmClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!"interceptKeyBeforeQueueing".equals(method.getName())
                    || method.getReturnType() != int.class || types.length != 2
                    || types[0] != KeyEvent.class || types[1] != int.class || !beginHook(method)) {
                continue;
            }
            try {
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    try {
                        Object arg = chain.getArgs().get(0);
                        if (arg instanceof KeyEvent) onKeyEvent((KeyEvent) arg);
                    } catch (Throwable error) {
                        XLog.log(Log.ERROR, "Key observation failed: " + error);
                    }
                    return chain.proceed(); // 不吞掉上游事件，保留系统按键和 SOS 处理。
                });
                XLog.log(Log.INFO, "Hooked key observation: " + method.toGenericString());
            } catch (Throwable error) {
                forgetHook(method);
                XLog.log(Log.ERROR, "Failed to hook key observation " + method + ": " + error);
            }
        }
    }

    private void onKeyEvent(KeyEvent event) {
        boolean screenOff = DisplayPowerControl.isScreenOffByFakeScreen();
        boolean enabled = FakeScreenIpc.isEnabled();
        boolean escapeEligible = enabled || screenOff || isRecoveryRetryAvailable();
        if (!escapeEligible) return;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return;
        PowerGestureTracker.Key key;
        if (event.getKeyCode() == KeyEvent.KEYCODE_POWER) {
            key = PowerGestureTracker.Key.POWER;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
            key = PowerGestureTracker.Key.VOLUME_UP;
        } else {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                mGestures.onOtherKeyPressed();
            }
            return;
        }
        PowerGestureTracker.Result result = mGestures.onKeyEvent(key, action == KeyEvent.ACTION_DOWN,
                event.getDeviceId(), event.getDownTime(), event.getEventTime(),
                event.getRepeatCount(), event.isCanceled(), screenOff, escapeEligible);
        XLog.log(Log.DEBUG, "Key observed: key=" + key + ", action=" + action
                + ", downTime=" + event.getDownTime() + ", eventTime=" + event.getEventTime()
                + ", repeat=" + event.getRepeatCount() + ", canceled=" + event.isCanceled()
                + ", screenOffByFakeScreen=" + screenOff + ", result=" + result);
        if (result == PowerGestureTracker.Result.WAKE) {
            XLog.log(Log.INFO, "Two separate power presses observed; requesting display recovery.");
            scheduleScreenModeChange(false, mSystemClassLoader, 0);
        } else if (result == PowerGestureTracker.Result.ESCAPE) {
            XLog.log(Log.WARN, "Volume-up escape detected; disabling FakeScreen and recovering display.");
            cancelPendingPowerTask();
            if (!mHandler.post(() -> FakeScreenIpc.disableFromEscape(this))) {
                XLog.log(Log.ERROR, "Could not enqueue escape; attempting recovery immediately.");
                FakeScreenIpc.disableFromEscape(this);
            }
        }
    }

    private void hookLongPresses(Class<?> clazz) {
        boolean powerRule = clazz.getName().equals(
                "com.android.server.policy.PhoneWindowManager$PowerKeyRule");
        for (Method method : clazz.getDeclaredMethods()) {
            String name = method.getName();
            boolean match = powerRule
                    ? ("onLongPress".equals(name) || "onVeryLongPress".equals(name))
                    : ("powerLongPress".equals(name) || "powerVeryLongPress".equals(name));
            if (!match
                    || method.getReturnType() != void.class || !beginHook(method)) continue;
            try {
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    mGestures.onPowerLongPress();
                    restoreDisplayNow("before " + method.getDeclaringClass().getSimpleName() + "." + name);
                    return chain.proceed();
                });
                XLog.log(Log.INFO, "Hooked long press: " + method.toGenericString());
            } catch (Throwable error) {
                forgetHook(method);
                XLog.log(Log.WARN, "Could not hook long press " + method + ": " + error);
            }
        }
    }

    private void hookPowerRuleLongPress(ClassLoader classLoader) {
        try {
            hookLongPresses(classLoader.loadClass("com.android.server.policy.PhoneWindowManager$PowerKeyRule"));
        } catch (Throwable error) {
            XLog.log(Log.WARN, "PowerKeyRule hook unavailable; using other long-press hooks: " + error);
        }
    }

    private boolean beginHook(Method method) {
        synchronized (mHookedMethods) {
            return mHookedMethods.add(method);
        }
    }

    private void forgetHook(Method method) {
        synchronized (mHookedMethods) {
            mHookedMethods.remove(method);
        }
    }

    /**
     * 取消 Handler 队列中待执行的电源键延时任务。
     */
    private void cancelPendingPowerTask() {
        synchronized (POWER_TASK_LOCK) {
            cancelPendingPowerTaskLocked();
        }
    }

    private void cancelPendingPowerTaskLocked() {
        if (mPendingPowerTask == null) return;
        mHandler.removeCallbacks(mPendingPowerTask);
        mPendingPowerTask = null;
        XLog.log(Log.DEBUG, "Cancelled a pending power-key display action.");
    }

    private void onFakeScreenDisabled() {
        restoreDisplayNow("FakeScreen disabled");
    }

    /** 同步尝试恢复，以便系统长按或退出路径继续执行前先发出亮屏请求。 */
    private void restoreDisplayNow(String reason) {
        synchronized (POWER_TASK_LOCK) {
            cancelPendingPowerTaskLocked();
            mGestures.resetPendingGestures();
            if (!DisplayPowerControl.isScreenOffByFakeScreen() && !isRecoveryRetryAvailable()) return;
            allowRecoveryRetry();
            try {
                boolean requested = DisplayPowerControl.wakeScreen(mSystemClassLoader);
                XLog.log(requested ? Log.INFO : Log.ERROR,
                        "Display recovery " + (requested ? "requested" : "not sent")
                                + ": reason=" + reason);
            } catch (Exception e) {
                XLog.error("Display recovery failed: reason=" + reason, e);
            }
        }
    }

    /**
     * 延迟执行显示模式切换，避免在 PhoneWindowManager 的按键处理调用栈中操作显示服务。
     *
     * @param turnScreenOff true 表示熄屏，false 表示唤醒屏幕。
     * @param classLoader 用于解析系统显示类的 ClassLoader。
     * @param delayMs 执行延迟。
     */
    private void scheduleScreenModeChange(boolean turnScreenOff, ClassLoader classLoader, long delayMs) {
        String targetMode = turnScreenOff ? "OFF" : "NORMAL";
        XLog.log(Log.DEBUG, "Queue display mode request: target=" + targetMode + ", delayMs=" + delayMs);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                synchronized (POWER_TASK_LOCK) {
                    if (mPendingPowerTask != this) return;
                    mPendingPowerTask = null;
                    if (turnScreenOff && !FakeScreenIpc.isEnabled()) {
                        XLog.log(Log.INFO, "Discarded pending OFF request after FakeScreen was disabled.");
                        return;
                    }
                    try {
                        boolean requested;
                        allowRecoveryRetry();
                        if (turnScreenOff) {
                            requested = DisplayPowerControl.turnScreenOff(classLoader);
                        } else {
                            requested = DisplayPowerControl.wakeScreen(classLoader);
                            mGestures.resetPendingGestures();
                        }
                        XLog.log(requested ? Log.INFO : Log.ERROR,
                                "Display mode " + (requested ? "requested" : "not sent")
                                        + ": target=" + targetMode + ", screenOffByFakeScreen="
                                        + DisplayPowerControl.isScreenOffByFakeScreen());
                    } catch (Exception e) {
                        XLog.error("Display mode request failed: target=" + targetMode, e);
                    }
                }
            }
        };
        synchronized (POWER_TASK_LOCK) {
            cancelPendingPowerTaskLocked();
            mPendingPowerTask = task;
            if (!mHandler.postDelayed(task, delayMs)) {
                mPendingPowerTask = null;
                XLog.log(Log.ERROR, "Failed to enqueue display mode request: target=" + targetMode);
                if (!turnScreenOff) restoreDisplayNow("wake queue unavailable");
            }
        }
    }

    private static long extractPowerEventTime(List<Object> args) {
        return !args.isEmpty() && args.get(0) instanceof Long ? (Long) args.get(0) : -1;
    }

    private boolean isRecoveryRetryAvailable() {
        return mRecoveryRetryUntil > 0 && SystemClock.uptimeMillis() <= mRecoveryRetryUntil;
    }

    private void allowRecoveryRetry() {
        mRecoveryRetryUntil = SystemClock.uptimeMillis() + RECOVERY_RETRY_WINDOW_MS;
    }

    /**
     * 从方法参数列表中取出点击次数。
     *
     * @param args 调用时捕获的方法参数列表。
     * @return 找到的点击次数，没有则返回 1。
     */
    private int extractClickCount(List<Object> args) {
        for (Object arg : args) {
            if (arg instanceof Integer) {
                return (Integer) arg;
            }
        }
        XLog.log(Log.WARN, "No integer click-count argument found; defaulting to one press.");
        return 1;
    }

    private static String describeArguments(List<Object> args) {
        StringBuilder description = new StringBuilder("[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                description.append(", ");
            }
            Object arg = args.get(i);
            description.append(i).append('=');
            if (arg == null) {
                description.append("null");
            } else if (arg instanceof Number || arg instanceof Boolean || arg instanceof Character) {
                description.append(arg.getClass().getSimpleName()).append(':').append(arg);
            } else {
                description.append(arg.getClass().getName());
            }
        }
        return description.append(']').toString();
    }

    /**
     * 在 PhoneWindowManager 中查找参数签名匹配的 powerPress 方法。
     *
     * @param pwmClass 目标 PhoneWindowManager 的 Class 引用。
     * @return 匹配的 powerPress Method，未找到时返回 null。
     */
    private static Method findPowerPressMethod(Class<?> pwmClass) {
        for (Method method : pwmClass.getDeclaredMethods()) {
            if ("powerPress".equals(method.getName())) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (method.getReturnType() == void.class && paramTypes.length >= 2
                        && paramTypes[0] == long.class && paramTypes[1] == int.class) {
                    XLog.log(Log.INFO, "Selected powerPress method: " + method.toGenericString());
                    return method;
                }
            }
        }
        return null;
    }
}
