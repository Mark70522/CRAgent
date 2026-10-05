package com.company.cragent.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Two cr-agent processes share the same files: the one Copilot starts (run.bat) and the one behind the page
 * (start.bat). {@code synchronized} only covers one process, so every read-modify-write of a data directory
 * runs under {@link #with}: a JVM lock plus an OS file lock on {@code <dir>/.lock}. Reentrant, so a locked
 * method may call another. {@link #writeAtomically} replaces a file in one step, so a reader in the other
 * process never sees half of it.
 */
public final class DirLock {

    @FunctionalInterface
    public interface Body<T> { T run() throws Exception; }

    private static final class Holder {
        final ReentrantLock jvm = new ReentrantLock();
        FileChannel channel;
        FileLock os;
        int depth;
    }

    private static final Map<Path, Holder> HOLDERS = new ConcurrentHashMap<>();

    private DirLock() {}

    public static <T> T with(Path dir, Body<T> body) {
        Holder h = HOLDERS.computeIfAbsent(dir.toAbsolutePath().normalize(), k -> new Holder());
        h.jvm.lock();
        try {
            if (h.depth == 0) {
                Files.createDirectories(dir);
                h.channel = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                h.os = h.channel.lock();   // waits while the other process holds it
            }
            h.depth++;
            try {
                return body.run();
            } finally {
                if (--h.depth == 0) release(h);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            h.jvm.unlock();
        }
    }

    public static void run(Path dir, Runnable body) { with(dir, () -> { body.run(); return null; }); }

    private static void release(Holder h) {
        try { if (h.os != null) h.os.release(); } catch (IOException ignored) { }
        try { if (h.channel != null) h.channel.close(); } catch (IOException ignored) { }
        h.os = null;
        h.channel = null;
    }

    /** Write to a temp file next to the target, then move it over the target in one step. */
    public static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, bytes);
            move(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static void writeAtomically(Path target, String text) throws IOException {
        writeAtomically(target, text.getBytes(StandardCharsets.UTF_8));
    }

    // Windows refuses the replace for a moment while someone else (an editor, a virus scanner) has the file open
    private static void move(Path tmp, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
                return;
            } catch (AccessDeniedException e) {
                if (attempt >= 20) throw e;
            } catch (FileSystemException e) {
                if (attempt >= 20 || e instanceof java.nio.file.NoSuchFileException) throw e;
            }
            try { Thread.sleep(25); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException("interrupted", ie); }
        }
    }
}
