package by.homesite.ftpclient.service;

import by.homesite.ftpclient.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies browser uploads into the managed root directory. */
class StorageServiceUploadTest {

    @TempDir
    Path tmp;

    private Path root;
    private StorageService storage;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("docs"));
        StorageProperties props = new StorageProperties();
        props.setRootDir(root.toString());
        storage = new StorageService(props);
        storage.init();
    }

    private static InputStream text(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String rel) throws Exception {
        return Files.readString(root.resolve(rel));
    }

    @Test
    void storesNewFile() throws Exception {
        storage.storeUpload("docs", "a.txt", text("hello"), OverwritePolicy.REPLACE);
        assertEquals("hello", read("docs/a.txt"));
    }

    @Test
    void stripsClientPathFromName() throws Exception {
        storage.storeUpload("docs", "C:\\Users\\me\\b.txt", text("x"), OverwritePolicy.REPLACE);
        assertEquals("x", read("docs/b.txt"));
    }

    @Test
    void appliesOverwritePolicies() throws Exception {
        storage.storeUpload("docs", "c.txt", text("v1"), OverwritePolicy.REPLACE);

        assertNull(storage.storeUpload("docs", "c.txt", text("v2"), OverwritePolicy.SKIP));
        assertEquals("v1", read("docs/c.txt"));

        storage.storeUpload("docs", "c.txt", text("v3"), OverwritePolicy.RENAME);
        assertEquals("v1", read("docs/c.txt"));
        assertEquals("v3", read("docs/c 1.txt"));

        storage.storeUpload("docs", "c.txt", text("v4"), OverwritePolicy.REPLACE);
        assertEquals("v4", read("docs/c.txt"));
    }

    @Test
    void rejectsInvalidNamesAndDirectoriesOutsideRoot() throws Exception {
        assertThrows(StorageException.class,
                () -> storage.storeUpload("docs", "..", text("x"), OverwritePolicy.REPLACE));
        assertThrows(StorageException.class,
                () -> storage.storeUpload("../outside", "d.txt", text("x"), OverwritePolicy.REPLACE));
        assertThrows(StorageException.class,
                () -> storage.storeUpload("no-such-dir", "d.txt", text("x"), OverwritePolicy.REPLACE));
    }

    @Test
    void leavesNoTempFilesBehind() throws Exception {
        storage.storeUpload("docs", "e.txt", text("x"), OverwritePolicy.REPLACE);
        try (var s = Files.list(root.resolve("docs"))) {
            assertFalse(s.anyMatch(p -> p.getFileName().toString().endsWith(".part")));
        }
    }
}

