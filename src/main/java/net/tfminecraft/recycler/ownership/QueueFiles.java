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

/** Acknowledges file contents and every directory link this write changed; immutable creates cannot clobber. */
public final class QueueFiles {
    private QueueFiles() {}
    /** Paths whose latest write in this process finished every sync; only these may skip an identical rewrite. */
    private static final Set<Path> PUBLISHED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static boolean published(Path file) { return PUBLISHED.contains(file.toAbsolutePath()); }
    public static void write(Path file, byte[] data, boolean replace) throws IOException {
        PUBLISHED.remove(file.toAbsolutePath());
        Path folder = file.toAbsolutePath().getParent();
        List<Path> changed = syncDirectories(folder);
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
            for (Path parent : changed) force(parent);
        } finally { Files.deleteIfExists(pending); }
        PUBLISHED.add(file.toAbsolutePath());
    }
    /**
     * The file's folder and its parent (a folder left by an interrupted write is re-acknowledged), then the parent of
     * each further folder about to be created, deepest first. Older ancestors are untouched.
     */
    static List<Path> syncDirectories(Path folder) {
        List<Path> folders = new ArrayList<>(List.of(folder));
        for (Path child = folder; !child.equals(child.getRoot()); child = child.getParent()) {
            folders.add(child.getParent());
            if (Files.exists(child.getParent())) break;
        }
        return folders;
    }
    public static void delete(Path file) throws IOException {
        PUBLISHED.remove(file.toAbsolutePath());
        if (Files.deleteIfExists(file)) force(file.toAbsolutePath().getParent());
    }
    static void force(Path folder) throws IOException {
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) { channel.force(true); }
    }
}
