package top.chuyb.fakeScreen;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 查看、导出和清除应用内诊断日志。 */
public final class DiagnosticLogActivity extends AppCompatActivity {
    private static final int REQUEST_EXPORT = 1001;
    private static final int MAX_DISPLAY_CHARS = 100_000;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private DiagnosticLogStore store;
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostic_log);
        store = new DiagnosticLogStore(createDeviceProtectedStorageContext().getFilesDir());
        logText = findViewById(R.id.diagnostic_log_text);
        findViewById(R.id.diagnostic_log_refresh).setOnClickListener(v -> refreshLog());
        findViewById(R.id.diagnostic_log_export).setOnClickListener(v -> chooseExportLocation());
        findViewById(R.id.diagnostic_log_clear).setOnClickListener(v -> confirmClear());
        findViewById(R.id.diagnostic_log_back).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLog();
    }

    private void refreshLog() {
        if (isFinishing() || isDestroyed()) return;
        logText.setText(R.string.diagnostic_log_loading);
        ioExecutor.execute(() -> {
            try {
                String text = store.readAll();
                if (text.isEmpty()) text = getString(R.string.diagnostic_log_empty);
                boolean hasSystemLog = text.contains("/system_server (");
                if (text.length() > MAX_DISPLAY_CHARS) {
                    text = getString(R.string.diagnostic_log_truncated) + "\n\n"
                            + text.substring(text.length() - MAX_DISPLAY_CHARS);
                }
                if (!hasSystemLog) {
                    text = getString(R.string.diagnostic_log_no_system_entries) + "\n\n" + text;
                }
                String result = text;
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) logText.setText(result);
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        logText.setText(R.string.diagnostic_log_read_failed);
                    }
                });
            }
        });
    }

    private void chooseExportLocation() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "fakeScreen-diagnostics.txt");
        try {
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (ActivityNotFoundException error) {
            showMessage(R.string.diagnostic_log_export_unavailable);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT || resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        ioExecutor.execute(() -> {
            try {
                byte[] contents = store.readAll().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) throw new IOException("Cannot open export destination");
                    output.write(contents);
                }
                runOnUiThread(() -> showMessage(R.string.diagnostic_log_exported));
            } catch (IOException | SecurityException error) {
                runOnUiThread(() -> showMessage(R.string.diagnostic_log_export_failed));
            }
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.diagnostic_log_clear_confirm)
                .setNegativeButton(R.string.diagnostic_log_cancel, null)
                .setPositiveButton(R.string.diagnostic_log_clear, (dialog, which) ->
                        ioExecutor.execute(() -> {
                            try {
                                store.clear();
                                runOnUiThread(this::refreshLog);
                            } catch (IOException error) {
                                runOnUiThread(() -> showMessage(R.string.diagnostic_log_clear_failed));
                            }
                        }))
                .show();
    }

    private void showMessage(int message) {
        if (!isFinishing() && !isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdown();
        super.onDestroy();
    }
}
