package top.chuyb.fakeScreen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DiagnosticLogStoreTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rotatesAndRetainsMostRecentFilesAcrossInstances() throws Exception {
        DiagnosticLogStore store = new DiagnosticLogStore(temporaryFolder.getRoot(), 10);
        store.append("first");
        store.append("second");
        store.append("third");

        DiagnosticLogStore reopened = new DiagnosticLogStore(temporaryFolder.getRoot(), 10);
        assertEquals("second\nthird\n", reopened.readAll());
        reopened.clear();
        assertEquals("", store.readAll());
    }

    @Test
    public void rotationUsesUtf8ByteSizeAndRejectsOversizedEntry() throws Exception {
        DiagnosticLogStore store = new DiagnosticLogStore(temporaryFolder.getRoot(), 8);
        store.append("中文");
        store.append("a");
        assertEquals("中文\na\n", store.readAll());
        assertThrows(IOException.class, () -> store.append("中文文"));
        assertEquals("中文\na\n", store.readAll());
    }

    @Test
    public void concurrentWritersKeepCompleteEntries() throws Exception {
        DiagnosticLogStore store = new DiagnosticLogStore(temporaryFolder.getRoot(), 100_000);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        Future<?>[] futures = new Future<?>[8];
        try {
            for (int worker = 0; worker < futures.length; worker++) {
                final int id = worker;
                futures[worker] = executor.submit(() -> {
                    start.await();
                    for (int index = 0; index < 50; index++) {
                        store.append(id + ":" + index);
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> future : futures) future.get();
            String[] lines = store.readAll().split("\n");
            Set<String> unique = new HashSet<>();
            for (String line : lines) unique.add(line);
            assertEquals(400, lines.length);
            assertEquals(400, unique.size());
        } finally {
            executor.shutdownNow();
        }
    }
}
