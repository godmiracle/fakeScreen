package top.chuyb.fakeScreen;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** 在后台把 system_server 和应用中的日志送到应用私有存储。 */
final class DiagnosticLogBridge {
    static final Uri URI = Uri.parse("content://top.chuyb.fakeScreen.diagnostics");
    static final String METHOD_APPEND = "append";
    static final String KEY_ENTRY = "entry";
    static final String KEY_STORED = "stored";
    static final int MAX_ENTRY_CHARS = 16 * 1024;

    private static final String TAG = "FakeScreen";
    private static final int MAX_PENDING = 256;
    private static final int MAX_RETRIES = 12;
    private static final long RETRY_DELAY_MS = 10_000;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> PENDING = new ArrayDeque<>();
    private static Context sContext;
    private static Handler sHandler;
    private static boolean sScheduled;
    private static int sFailures;
    private static int sDropped;

    private DiagnosticLogBridge() {
    }

    static void attach(Context context) {
        if (context == null) return;
        try {
            synchronized (LOCK) {
                Context applicationContext = context.getApplicationContext();
                sContext = applicationContext != null ? applicationContext : context;
                if (sHandler == null) {
                    HandlerThread thread = new HandlerThread("FakeScreen-log");
                    thread.start();
                    sHandler = new Handler(thread.getLooper());
                }
                sFailures = 0;
                scheduleLocked(0);
            }
        } catch (RuntimeException | LinkageError error) {
            Log.w(TAG, "Cannot initialize app diagnostic logging", error);
        }
    }

    static void record(int level, String message, Throwable error) {
        try {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            String source = Process.myUid() == Process.SYSTEM_UID ? "system_server" : "app";
            String entry = time + " " + levelName(level) + "/" + source
                    + " (pid=" + Process.myPid() + ", tid=" + Process.myTid() + ") " + message;
            if (error != null) entry += "\n" + Log.getStackTraceString(error);
            if (entry.length() > MAX_ENTRY_CHARS) {
                entry = entry.substring(0, MAX_ENTRY_CHARS - 32) + "\n[日志内容过长，已截断]";
            }
            synchronized (LOCK) {
                if (PENDING.size() == MAX_PENDING) {
                    PENDING.removeFirst();
                    sDropped++;
                }
                PENDING.addLast(entry);
                if (sFailures > MAX_RETRIES) sFailures = 0;
                scheduleLocked(0);
            }
        } catch (RuntimeException | LinkageError loggingError) {
            Log.w(TAG, "Cannot queue app diagnostic log", loggingError);
        }
    }

    private static void scheduleLocked(long delayMs) {
        if (sHandler == null || sContext == null || sScheduled || PENDING.isEmpty()) return;
        sScheduled = true;
        if (!sHandler.postDelayed(DiagnosticLogBridge::drain, delayMs)) sScheduled = false;
    }

    private static void drain() {
        while (true) {
            String entry;
            int dropped;
            Context context;
            synchronized (LOCK) {
                entry = PENDING.peekFirst();
                if (entry == null) {
                    sScheduled = false;
                    sFailures = 0;
                    return;
                }
                context = sContext;
                dropped = sDropped;
            }
            try {
                Bundle request = new Bundle();
                String prefix = dropped > 0 ? "[早期日志队列已丢弃 " + dropped + " 条记录]\n" : "";
                request.putString(KEY_ENTRY, prefix + entry);
                Bundle response = context.getContentResolver().call(URI, METHOD_APPEND, null, request);
                if (response == null || !response.getBoolean(KEY_STORED)) {
                    throw new IllegalStateException("Diagnostic log provider is unavailable");
                }
            } catch (RuntimeException | LinkageError error) {
                synchronized (LOCK) {
                    if (sFailures == 0) Log.w(TAG, "App diagnostic log unavailable; retrying", error);
                    sScheduled = false;
                    if (++sFailures <= MAX_RETRIES) scheduleLocked(RETRY_DELAY_MS);
                }
                return;
            }
            synchronized (LOCK) {
                if (PENDING.peekFirst() == entry) {
                    PENDING.removeFirst();
                } else if (sDropped > dropped) {
                    // 写入期间队列溢出移除了这条记录，但该记录已经成功保存。
                    sDropped--;
                }
                sDropped -= dropped;
                sFailures = 0;
            }
        }
    }

    private static String levelName(int level) {
        switch (level) {
            case Log.VERBOSE: return "V";
            case Log.DEBUG: return "D";
            case Log.INFO: return "I";
            case Log.WARN: return "W";
            case Log.ERROR: return "E";
            case Log.ASSERT: return "A";
            default: return Integer.toString(level);
        }
    }
}
