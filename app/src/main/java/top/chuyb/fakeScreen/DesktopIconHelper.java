package top.chuyb.fakeScreen;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 桌面（启动器）图标显隐处理。
 *
 * <p>只在运行时启用或禁用占位启动组件（{@link LauncherActivity}）。{@link MainActivity} 本身
 * 始终保持启用，因此在隐藏桌面图标的同时，应用依旧可以从 Xposed 管理器、最近任务或显式 Intent
 * 进入。该操作不会杀掉应用进程。</p>
 *
 * @since 1.0
 * @author chuyb-re
 */
final class DesktopIconHelper {

    private DesktopIconHelper() {
        // 工具类，禁止实例化。
    }

    /**
     * @param context 任意可用的 Context。
     * @return 桌面图标当前是否已隐藏。
     */
    static boolean isHidden(Context context) {
        PackageManager pm = context.getPackageManager();
        ComponentName component = launcherComponent(context);
        int state = pm.getComponentEnabledSetting(component);
        if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return false;
        if (state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return true;
        try {
            // 默认状态取清单中的组件配置；用户手动修改后系统会保存显式状态。
            return !pm.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).enabled;
        } catch (PackageManager.NameNotFoundException error) {
            return true;
        }
    }

    /**
     * 隐藏或显示桌面图标。
     *
     * @param context 任意可用的 Context。
     * @param hide    true 表示隐藏桌面图标，false 表示重新显示。
     */
    static void applySetting(Context context, boolean hide) {
        ComponentName component = launcherComponent(context);
        PackageManager pm = context.getPackageManager();
        int newState = hide
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        pm.setComponentEnabledSetting(component, newState, PackageManager.DONT_KILL_APP);
    }

    /**
     * @param context 任意可用的 Context。
     * @return 占位启动组件的 ComponentName。
     */
    private static ComponentName launcherComponent(Context context) {
        return new ComponentName(context, LauncherActivity.class);
    }
}
