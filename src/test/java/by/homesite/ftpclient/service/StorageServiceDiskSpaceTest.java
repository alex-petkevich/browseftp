package by.homesite.ftpclient.service;

import by.homesite.ftpclient.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Disk-space reporting for the panel status bar. */
class StorageServiceDiskSpaceTest {

    @TempDir
    Path tmp;

    private StorageService storage;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("sub"));
        StorageProperties props = new StorageProperties();
        props.setRootDir(root.toString());
        storage = new StorageService(props);
        storage.init();
    }

    @Test
    void reportsConsistentNumbersForRootAndSubfolder() {
        for (String path : new String[] {"", "sub", "/sub"}) {
            StorageService.DiskSpace s = storage.diskSpace(path);
            assertTrue(s.total() > 0, "total");
            assertTrue(s.free() >= 0 && s.free() <= s.total(), "free within total");
            assertTrue(s.used() >= 0 && s.used() <= s.total(), "used within total");
        }
    }

    @Test
    void missingFolderFallsBackToRoot() {
        assertTrue(storage.diskSpace("no/such/dir").total() > 0);
    }

    @Test
    void pathsOutsideRootAreRejected() {
        assertThrows(StorageException.class, () -> storage.diskSpace("../.."));
    }
}

