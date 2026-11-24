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

/** Acknowledges file contents and every directory link this write changed; immutable creates cannot clobber. */
public final class QueueFiles {
    private QueueFiles() {}
    public static void write(Path file, byte[] data, boolean replace) throws IOException {
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
    }
    /** The file's folder, then the parent of each folder about to be created, deepest first. Older ancestors are untouched. */
    static List<Path> syncDirectories(Path folder) {
        List<Path> folders = new ArrayList<>(List.of(folder));
        for (Path missing = folder; !missing.equals(missing.getRoot()) && !Files.exists(missing); missing = missing.getParent())
            folders.add(missing.getParent());
        return folders;
    }
    public static void delete(Path file) throws IOException {
        if (Files.deleteIfExists(file)) force(file.toAbsolutePath().getParent());
    }
    static void force(Path folder) throws IOException {
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) { channel.force(true); }
    }
}
