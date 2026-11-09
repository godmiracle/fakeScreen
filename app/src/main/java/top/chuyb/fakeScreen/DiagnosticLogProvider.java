package top.chuyb.fakeScreen;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.io.IOException;

/** 仅允许 system_server 和本应用写入诊断日志。 */
public final class DiagnosticLogProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != Process.myUid()) {
            throw new SecurityException("Only the system and this app can write diagnostic logs");
        }
        if (!DiagnosticLogBridge.METHOD_APPEND.equals(method) || extras == null) {
            throw new IllegalArgumentException("Unsupported diagnostic log request");
        }
        String entry = extras.getString(DiagnosticLogBridge.KEY_ENTRY);
        if (entry == null || entry.length() > DiagnosticLogBridge.MAX_ENTRY_CHARS + 128) {
            throw new IllegalArgumentException("Invalid diagnostic log entry");
        }
        Bundle result = new Bundle();
        try {
            new DiagnosticLogStore(getContext().createDeviceProtectedStorageContext().getFilesDir())
                    .append(entry);
            result.putBoolean(DiagnosticLogBridge.KEY_STORED, true);
        } catch (IOException | RuntimeException error) {
            Log.w("FakeScreen", "Cannot write app diagnostic log", error);
            result.putBoolean(DiagnosticLogBridge.KEY_STORED, false);
        }
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
