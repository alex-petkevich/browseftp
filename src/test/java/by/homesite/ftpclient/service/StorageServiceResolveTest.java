package by.homesite.ftpclient.service;
import by.homesite.ftpclient.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
/** Verifies that user-supplied paths can never escape app.root-dir. */
class StorageServiceResolveTest {
    @TempDir
    Path tmp;
    private Path root;
    private StorageService storage;
    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("root"));
        Files.createDirectories(root.resolve("photos/2026"));
        Files.createDirectories(tmp.resolve("outside"));
        StorageProperties props = new StorageProperties();
        props.setRootDir(root.toString());
        storage = new StorageService(props);
        storage.init();
    }
    private String rel(String input) {
        return storage.relativize(storage.resolve(input));
    }
    @Test
    void relativeAndLeadingSlashPathsResolveUnderRoot() {
        assertEquals("", rel(""));
        assertEquals("", rel("/"));
        assertEquals("photos", rel("photos"));
        assertEquals("photos/2026", rel("/photos/2026"));
        assertEquals("photos/2026", rel("\\photos\\2026"));
        assertEquals("photos", rel("photos/2026/.."));
    }
    @Test
    void absolutePathInsideRootIsAccepted() {
        assertEquals("photos/2026", rel(root.resolve("photos/2026").toString()));
        assertEquals("", rel(root.toString()));
    }
    @Test
    void pathsOutsideRootAreRejected() {
        assertThrows(StorageException.class, () -> storage.resolve(".."));
        assertThrows(StorageException.class, () -> storage.resolve("/../outside"));
        assertThrows(StorageException.class, () -> storage.resolve("photos/../../outside"));
        assertThrows(StorageException.class, () -> storage.resolve(tmp.resolve("outside").toString()));
        assertThrows(StorageException.class, () -> storage.resolve(root + "/../outside"));
    }
    @Test
    void listingAMissingDirectoryFails() {
        assertThrows(StorageException.class, () -> storage.list("no/such/dir"));
    }
}
