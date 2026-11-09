package top.chuyb.fakeScreen;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通过硬件层 SurfaceControl 接口控制显示屏电源模式。本类不安装任何 hook，
 * hook 层只调用明确的熄屏/唤醒操作，并通过状态标记区分 FakeScreen 主动熄屏。
 *
 * @author chuyb-re
 * @since 1.0
 */
final class DisplayPowerControl {
    private static final int POWER_MODE_OFF = 0;    // 熄屏
    private static final int POWER_MODE_NORMAL = 2; // 亮屏
    private static final AtomicBoolean sScreenOffByFakeScreen = new AtomicBoolean(false);
    public static volatile boolean useCompatibilityMode = false;


    private DisplayPowerControl() {
        // 工具类，禁止实例化。
    }

    static boolean isScreenOffByFakeScreen() {
        return sScreenOffByFakeScreen.get();
    }

    /**
     * 通过硬件层 SurfaceControl 接口仅关闭物理屏幕。
     *
     * @param classLoader 用于解析 SurfaceControl 与显示 token 的 ClassLoader。
     */
    static boolean turnScreenOff(ClassLoader classLoader) throws Exception {
        // 原生调用可能在抛出异常前已经改变了面板状态，先保守标记为需要恢复。
        sScreenOffByFakeScreen.set(true);
        try {
            if (!applyPowerMode(POWER_MODE_OFF, classLoader)) {
                sScreenOffByFakeScreen.set(false); // 无显示 token，尚未发出熄屏请求。
                return false;
            }
            XLog.log(Log.INFO, "FakeScreen display OFF was requested.");
            return true;
        } catch (Exception error) {
            try {
                if (!wakeScreen(classLoader)) {
                    XLog.log(Log.ERROR, "Display recovery after failed OFF request could not be sent.");
                }
            } catch (Exception recoveryError) {
                error.addSuppressed(recoveryError);
            }
            throw error;
        }
    }

    /**
     * 将 FakeScreen 主动关闭的物理屏幕恢复到正常显示模式。
     */
    static boolean wakeScreen(ClassLoader classLoader) throws ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        if (applyPowerMode(POWER_MODE_NORMAL, classLoader)) {
            sScreenOffByFakeScreen.set(false);
            XLog.log(Log.INFO, "FakeScreen display NORMAL was requested; physical state is not confirmed.");
            return true;
        }
        return false;
    }

    private static boolean applyPowerMode(int targetPowerMode, ClassLoader classLoader) throws ClassNotFoundException, InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        IBinder displayBinder = getUniversalDisplayBinder(classLoader);
        if (displayBinder == null) {
            XLog.log(Log.ERROR, "DisplayBinder is null; cannot apply display mode=" + modeName(targetPowerMode));
            return false;
        }
        XLog.log(Log.DEBUG, "Applying display power mode=" + modeName(targetPowerMode)
                + ", compatibilityMode=" + useCompatibilityMode);
        // 恢复始终直接请求 NORMAL：dumpsys 可能过期或阻塞，不能让它阻止应急恢复。
        if (useCompatibilityMode && targetPowerMode == POWER_MODE_OFF) {
            int currentPowerMode = getCurrentPowerMode();
            XLog.log(Log.DEBUG, "SurfaceFlinger reports current display mode=" + modeName(currentPowerMode));
            if (currentPowerMode == targetPowerMode) {
                XLog.log(Log.INFO, "Display already has requested mode=" + modeName(targetPowerMode));
                return true;
            }
        }
        setDisplayPowerMode(targetPowerMode, classLoader, displayBinder);
        XLog.log(Log.INFO, "SurfaceControl.setDisplayPowerMode invoked with mode=" + modeName(targetPowerMode));
        return true;
    }

    private static String modeName(int powerMode) {
        if (powerMode == POWER_MODE_OFF) return "OFF(0)";
        if (powerMode == POWER_MODE_NORMAL) return "NORMAL(2)";
        return "UNKNOWN(" + powerMode + ")";
    }

    /**
     * 兼容模式下读取 SurfaceFlinger 的实际显示电源模式；无法识别时返回未知值。
     */
    private static int getCurrentPowerMode() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("dumpsys SurfaceFlinger");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("powerMode=On")) {
                        return POWER_MODE_NORMAL;
                    }
                    if (line.contains("powerMode=Off")) {
                        return POWER_MODE_OFF;
                    }
                }
            }
        } catch (IOException e) {
            XLog.error("Failed to read the current display power mode.", e);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        XLog.log(Log.WARN, "Could not parse powerMode from dumpsys SurfaceFlinger output.");
        return -1;
    }

    /**
     * 切换屏幕电源模式
     *
     * @param targetPowerMode 目标电源模式，参考{@link POWER_MODE_OFF}与{@link POWER_MODE_NORMAL}
     * @param classLoader     使用的类加载器，需能够访问私有api
     * @param displayBinder   目标屏幕binder
     */
    private static void setDisplayPowerMode(int targetPowerMode, ClassLoader classLoader, IBinder displayBinder) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class<?> surfaceControlClass = classLoader.loadClass("android.view.SurfaceControl");
        @SuppressLint("SoonBlockedPrivateApi")
        Method setDisplayPowerMode = surfaceControlClass.getDeclaredMethod(
                "setDisplayPowerMode", IBinder.class, int.class);
        setDisplayPowerMode.setAccessible(true);
        // 直接调用 SurfaceControl 切换显示电源模式
        setDisplayPowerMode.invoke(null, displayBinder, targetPowerMode);
    }

    /**
     * 获取各 Android 版本（Android 9 ~ 14+）下的物理显示屏 IBinder token。
     *
     * @param classLoader 用于解析系统类的 ClassLoader。
     * @return 物理显示屏 IBinder token，解析失败时返回 null。
     */
    private static IBinder getUniversalDisplayBinder(ClassLoader classLoader) {
        int sdkInt = Build.VERSION.SDK_INT;

        if (sdkInt >= 34) { // Android 14+
            try {
                @SuppressLint("PrivateApi") Class<?> displayControl = classLoader.loadClass("com.android.server.display.DisplayControl");
                Method getPhysicalDisplayIds = displayControl.getDeclaredMethod("getPhysicalDisplayIds");
                getPhysicalDisplayIds.setAccessible(true);
                long[] ids = (long[]) getPhysicalDisplayIds.invoke(null);
                if (ids != null && ids.length > 0) {
                    Method getPhysicalDisplayToken = displayControl.getDeclaredMethod("getPhysicalDisplayToken", long.class);
                    getPhysicalDisplayToken.setAccessible(true);
                    IBinder token = (IBinder) getPhysicalDisplayToken.invoke(null, ids[0]);
                    if (token != null) {
                        XLog.log(Log.DEBUG, "Resolved physical display token with DisplayControl; id=" + ids[0]);
                    }
                    return token;
                }
            } catch (Throwable error) {
                XLog.log(Log.WARN, "DisplayControl token lookup failed: " + error);
            }
        }

        if (sdkInt >= 29) { // Android 10 ~ 13
            try {
                Class<?> surfaceControl = classLoader.loadClass("android.view.SurfaceControl");
                @SuppressLint("BlockedPrivateApi")
                Method getInternalDisplayToken = surfaceControl.getDeclaredMethod("getInternalDisplayToken");
                getInternalDisplayToken.setAccessible(true);
                IBinder token = (IBinder) getInternalDisplayToken.invoke(null);
                if (token != null) {
                    XLog.log(Log.DEBUG, "Resolved physical display token with SurfaceControl.getInternalDisplayToken.");
                    return token;
                }
            } catch (Throwable error) {
                XLog.log(Log.WARN, "Internal display token lookup failed: " + error);
            }
        }

        try { // Android 9 (API 28)
            Class<?> surfaceControl = classLoader.loadClass("android.view.SurfaceControl");
            Method getBuiltInDisplay = surfaceControl.getDeclaredMethod("getBuiltInDisplay", int.class);
            getBuiltInDisplay.setAccessible(true);
            IBinder token = (IBinder) getBuiltInDisplay.invoke(null, 0);
            if (token != null) {
                XLog.log(Log.DEBUG, "Resolved physical display token with SurfaceControl.getBuiltInDisplay.");
            }
            return token;
        } catch (Throwable error) {
            XLog.log(Log.WARN, "Built-in display token lookup failed: " + error);
        }

        return null;
    }
}
