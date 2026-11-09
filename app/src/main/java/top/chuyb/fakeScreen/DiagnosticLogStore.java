package top.chuyb.fakeScreen;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 应用私有目录中的有界诊断日志。旧文件和当前文件最多各保留一份。 */
final class DiagnosticLogStore {
    static final long MAX_FILE_BYTES = 512 * 1024;
    private static final Object FILE_LOCK = new Object();
    private final File currentFile;
    private final File previousFile;
    private final long maxFileBytes;

    DiagnosticLogStore(File directory) {
        this(directory, MAX_FILE_BYTES);
    }

    DiagnosticLogStore(File directory, long maxFileBytes) {
        this.currentFile = new File(directory, "diagnostic.log");
        this.previousFile = new File(directory, "diagnostic.log.1");
        this.maxFileBytes = maxFileBytes;
    }

    void append(String entry) throws IOException {
        byte[] bytes = (entry + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFileBytes) {
            throw new IOException("Diagnostic log entry is too large");
        }
        synchronized (FILE_LOCK) {
            File directory = currentFile.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot create diagnostic log directory");
            }
            if (currentFile.exists() && currentFile.length() + bytes.length > maxFileBytes) {
                if (previousFile.exists() && !previousFile.delete()) {
                    throw new IOException("Cannot remove previous diagnostic log");
                }
                if (!currentFile.renameTo(previousFile)) {
                    throw new IOException("Cannot rotate diagnostic log");
                }
            }
            try (FileOutputStream output = new FileOutputStream(currentFile, true)) {
                output.write(bytes);
            }
        }
    }

    String readAll() throws IOException {
        synchronized (FILE_LOCK) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            copyIfPresent(previousFile, output);
            copyIfPresent(currentFile, output);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    void clear() throws IOException {
        synchronized (FILE_LOCK) {
            deleteIfPresent(previousFile);
            deleteIfPresent(currentFile);
        }
    }

    private static void copyIfPresent(File file, ByteArrayOutputStream output) throws IOException {
        if (!file.isFile()) return;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    private static void deleteIfPresent(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Cannot delete diagnostic log");
        }
    }
}
