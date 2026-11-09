package top.chuyb.fakeScreen;

import android.util.Log;
import io.github.libxposed.api.XposedModule;

/**
 * 日志工具
 * @author chuyb-re
 * @since 2.1
 */
public class XLog {
    private static volatile XposedModule module;
    private static final String TAG = "FakeScreen";

    private XLog(){

    }

    public static void log(int level,String message){
        Log.println(level, TAG, message);
        XposedModule currentModule = module;
        if (currentModule != null) {
            try {
                currentModule.log(level, TAG, message);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to write to the Xposed module log.", e);
            }
        }
    }

    public static void error(String message,Exception e){
        Log.e(TAG, message, e);
        XposedModule currentModule = module;
        if (currentModule != null) {
            try {
                currentModule.log(Log.ERROR, TAG, message, e);
            } catch (RuntimeException loggingError) {
                Log.e(TAG, "Failed to write the exception to the Xposed module log.", loggingError);
            }
        }
    }
    public static void init(XposedModule module){
        XLog.module=module;
        log(Log.INFO, "FakeScreen diagnostic logging initialized.");
    }

}
