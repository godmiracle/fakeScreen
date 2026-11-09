package top.chuyb.fakeScreen;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Method;
import java.util.List;

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
    private static volatile Runnable mPendingPowerTask = null;
    private static final long MULTI_PRESS_TIMEOUT_MS = 220; // 多击防抖超时

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
        try {
            Class<?> pwmClass = classLoader.loadClass("com.android.server.policy.PhoneWindowManager");
            // 1. 动态注册 IPC 接收器
            registerIpcReceiver(pwmClass);
            // 2. hook 电源键处理
            hookPowerButton(pwmClass, classLoader);
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
            FakeScreenIpc.attach(this, pwmClass, pwmInstance);
            return result;
        });
        XLog.log(Log.INFO, "Hooked PhoneWindowManager.init: " + initMethod.toGenericString());
    }

    // =========================================================================
    // 2. 电源键 hook 与单击/双击处理
    // =========================================================================

    /**
     * hook PhoneWindowManager 中的 powerPress 方法，处理 FakeScreen 单击熄屏和双击唤醒。
     *
     * @param pwmClass    PhoneWindowManager 的 Class 引用。
     * @param classLoader 用于加载系统类的 ClassLoader。
     */
    private void hookPowerButton(Class<?> pwmClass, ClassLoader classLoader) {
        Method powerPress = findPowerPressMethod(pwmClass);
        if (powerPress == null) {
            XLog.log(Log.ERROR, "Failed to find a compatible PhoneWindowManager.powerPress method.");
            return;
        }
        powerPress.setAccessible(true);

        hook(powerPress).intercept(chain -> {
            if (!FakeScreenIpc.isEnabled()) {
                return chain.proceed();
            }

            int count = extractClickCount(chain.getArgs());
            boolean screenOffByFakeScreen = DisplayPowerControl.isScreenOffByFakeScreen();
            XLog.log(Log.INFO, "powerPress received: count=" + count
                    + ", screenOffByFakeScreen=" + screenOffByFakeScreen);
            XLog.log(Log.DEBUG, "powerPress arguments: " + describeArguments(chain.getArgs()));

            // FakeScreen 熄屏时，双击恢复屏幕；亮屏时保留系统原有双击动作。
            if (count == 2) {
                cancelPendingPowerTask();
                if (screenOffByFakeScreen) {
                    XLog.log(Log.INFO, "Power-key action: wake FakeScreen display on double press.");
                    scheduleScreenModeChange(false, classLoader, 0);
                    return null;
                }
                XLog.log(Log.INFO, "Power-key action: pass double press to the system.");
                return chain.proceed();
            }

            // 三击及以上交给系统处理，保留系统手势。
            if (count > 2) {
                cancelPendingPowerTask();
                XLog.log(Log.INFO, "Power-key action: pass multi-press count=" + count + " to the system.");
                return chain.proceed();
            }

            // FakeScreen 熄屏期间，单击不唤醒；亮屏期间单击仅关闭物理屏幕。
            if (count == 1) {
                if (screenOffByFakeScreen) {
                    XLog.log(Log.INFO, "Power-key action: ignore single press while FakeScreen display is off.");
                    return null;
                }
                cancelPendingPowerTask();
                XLog.log(Log.INFO, "Power-key action: turn the display off after the single-press window.");
                scheduleScreenModeChange(true, classLoader, MULTI_PRESS_TIMEOUT_MS);
                return null;
            }

            return chain.proceed();
        });
    }

    /**
     * 取消 Handler 队列中待执行的电源键延时任务。
     */
    private void cancelPendingPowerTask() {
        if (mPendingPowerTask != null) {
            mHandler.removeCallbacks(mPendingPowerTask);
            mPendingPowerTask = null;
            XLog.log(Log.DEBUG, "Cancelled a pending power-key display action.");
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
        cancelPendingPowerTask();
        String targetMode = turnScreenOff ? "OFF" : "NORMAL";
        XLog.log(Log.DEBUG, "Queue display mode request: target=" + targetMode + ", delayMs=" + delayMs);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    if (turnScreenOff) {
                        DisplayPowerControl.turnScreenOff(classLoader);
                    } else {
                        DisplayPowerControl.wakeScreen(classLoader);
                    }
                    XLog.log(Log.INFO, "Display mode request completed: target=" + targetMode
                            + ", screenOffByFakeScreen=" + DisplayPowerControl.isScreenOffByFakeScreen());
                } catch (Exception e) {
                    XLog.error("Display mode request failed: target=" + targetMode, e);
                } finally {
                    if (mPendingPowerTask == this) {
                        mPendingPowerTask = null;
                    }
                }
            }
        };
        mPendingPowerTask = task;
        if (!mHandler.postDelayed(task, delayMs)) {
            mPendingPowerTask = null;
            XLog.log(Log.ERROR, "Failed to enqueue display mode request: target=" + targetMode);
        }
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
                if (paramTypes.length >= 2 && paramTypes[0] == long.class && paramTypes[1] == int.class) {
                    XLog.log(Log.INFO, "Selected powerPress method: " + method.toGenericString());
                    return method;
                }
            }
        }
        return null;
    }
}
