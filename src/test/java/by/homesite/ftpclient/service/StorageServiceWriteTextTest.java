package by.homesite.ftpclient.service;

import by.homesite.ftpclient.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies saving edited text files inside the managed root directory. */
class StorageServiceWriteTextTest {

    @TempDir
    Path tmp;

    private Path root;
    private StorageService storage;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/notes.txt"), "old", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("outside.txt"), "secret", StandardCharsets.UTF_8);
        StorageProperties props = new StorageProperties();
        props.setRootDir(root.toString());
        storage = new StorageService(props);
        storage.init();
    }

    @Test
    void replacesContentOfExistingFile() throws Exception {
        storage.writeText("docs/notes.txt", "new text\r\nwith CRLF and ümlauts / кириллица");
        assertEquals("new text\r\nwith CRLF and ümlauts / кириллица",
                Files.readString(root.resolve("docs/notes.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void roundTripsWithReadText() {
        storage.writeText("docs/notes.txt", "line 1\nline 2\n");
        assertEquals("line 1\nline 2\n", storage.readText("docs/notes.txt").get("content"));
    }

    @Test
    void rejectsMissingFilesDirectoriesAndPathsOutsideRoot() {
        assertThrows(StorageException.class, () -> storage.writeText("docs/missing.txt", "x"));
        assertThrows(StorageException.class, () -> storage.writeText("docs", "x"));
        assertThrows(StorageException.class, () -> storage.writeText("../outside.txt", "x"));
    }

    @Test
    void rejectsContentOverTheLimit() throws Exception {
        String big = "a".repeat(2 * 1024 * 1024 + 1);
        assertThrows(StorageException.class, () -> storage.writeText("docs/notes.txt", big));
        assertEquals("old", Files.readString(root.resolve("docs/notes.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void leavesNoTempFilesBehind() throws Exception {
        storage.writeText("docs/notes.txt", "x");
        try (var s = Files.list(root.resolve("docs"))) {
            assertFalse(s.anyMatch(p -> p.getFileName().toString().endsWith(".part")));
        }
    }
}

