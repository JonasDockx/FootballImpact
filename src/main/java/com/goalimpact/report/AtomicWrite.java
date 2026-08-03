package com.goalimpact.report;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Temp file beside the target, then rename: the reader either sees the old file
// or the new one, never half of either.
//
// It matters for both generated artefacts and for the same reason twice over. A
// half-written HTML page still RENDERS, just wrongly (#22); a half-written
// match-log shard is a syntax error rather than a short log (#24), which is
// louder but no more welcome. And a stray .tmp left beside the real files is
// exactly the confusion temp-then-rename exists to avoid, so a failed move
// cleans up after itself.
final class AtomicWrite {

    private AtomicWrite() {
    }

    static void toFile(Path out, byte[] bytes) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            try {
                Files.move(tmp, out,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Same directory, so this should not happen; a filesystem that
                // cannot rename atomically still leaves the old file intact
                // until the move, which is the property that matters.
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
