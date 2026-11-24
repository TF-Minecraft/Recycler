package net.tfminecraft.recycler.ownership;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Acknowledges file contents and every directory link not yet synced by this process; immutable creates cannot clobber. */
public final class QueueFiles {
    private QueueFiles() {}
    /** Paths whose latest write in this process finished every sync; only these may skip an identical rewrite. */
    private static final Set<Path> PUBLISHED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Folders whose entry this process has synced, by forcing their parent. */
    static final Set<Path> LINKED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static boolean published(Path file) { return PUBLISHED.contains(file.toAbsolutePath()); }
    public static void write(Path file, byte[] data, boolean replace) throws IOException {
        PUBLISHED.remove(file.toAbsolutePath());
        Path folder = file.toAbsolutePath().getParent();
        List<Path> entries = unsyncedEntries(folder);
        Files.createDirectories(folder);
        Path pending = Files.createTempFile(folder, "recycler-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(data);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            if (replace) Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else try { Files.createLink(file, pending); }
            catch (UnsupportedOperationException ex) { throw new IOException("No-clobber publication unavailable", ex); }
            force(folder);
            for (Path entry : entries) { force(entry.getParent()); LINKED.add(entry); }
        } finally { Files.deleteIfExists(pending); }
        PUBLISHED.add(file.toAbsolutePath());
    }
    /**
     * Folders whose entry needs its parent synced, deepest first: the file's folder always, then each ancestor that is
     * about to be created or that this process has not synced yet (created at startup, or left by a failed write).
     */
    static List<Path> unsyncedEntries(Path folder) {
        List<Path> entries = new ArrayList<>();
        for (Path entry = folder; !entry.equals(entry.getRoot()); entry = entry.getParent()) {
            if (!Files.exists(entry)) LINKED.remove(entry);
            if (entry.equals(folder) || !LINKED.contains(entry)) entries.add(entry);
        }
        return entries;
    }
    public static void delete(Path file) throws IOException {
        PUBLISHED.remove(file.toAbsolutePath());
        if (Files.deleteIfExists(file)) force(file.toAbsolutePath().getParent());
    }
    static void force(Path folder) throws IOException {
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) { channel.force(true); }
    }
}
