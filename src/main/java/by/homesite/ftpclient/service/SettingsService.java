package by.homesite.ftpclient.service;

import by.homesite.ftpclient.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists user settings (theme, saved FTP connections, last-opened directories)
 * as a single JSON document on disk so they survive across browsers and devices.
 *
 * <p>The payload is an opaque JSON document owned by the front-end. To avoid
 * coupling to any particular JSON library, the bytes are stored and served
 * verbatim; Spring MVC handles (de)serialization at the controller boundary.
 *
 * <p>The file location defaults to {@code ~/.browseftp/settings.json} and can be
 * overridden with {@code --app.settings-file=...}.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final byte[] EMPTY_JSON = "{}".getBytes(StandardCharsets.UTF_8);

    private final StorageProperties properties;
    private Path file;

    public SettingsService(StorageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        // Candidate locations, in order of preference. The configured path comes
        // first; the others are fall-backs for environments (e.g. a hardened
        // systemd service with no home directory / ProtectHome=true) where the
        // configured location is not writable.
        List<Path> candidates = new ArrayList<>();
        addCandidate(candidates, properties.getSettingsFile());
        addCandidate(candidates, System.getProperty("user.dir") + "/.browseftp/settings.json");
        addCandidate(candidates, properties.getRootDir() + "/.browseftp/settings.json");

        for (Path candidate : candidates) {
            if (isWritableLocation(candidate)) {
                this.file = candidate;
                if (!candidate.equals(candidates.get(0))) {
                    log.warn("Configured settings file {} is not writable; using {} instead. "
                            + "Set --app.settings-file to a writable path to silence this warning.",
                            candidates.get(0), candidate);
                }
                log.info("User settings file: {}", file);
                return;
            }
        }
        this.file = candidates.get(0);
        log.error("User settings file {} is NOT writable (and no fallback location is writable). "
                + "Settings will not be persisted. Point --app.settings-file to a writable path "
                + "(and add its directory to ReadWritePaths when running under systemd).", file);
    }

    private static void addCandidate(List<Path> list, String path) {
        if (path == null || path.isBlank() || path.startsWith("?")) {
            return;
        }
        try {
            Path p = Paths.get(path).toAbsolutePath().normalize();
            if (!list.contains(p)) {
                list.add(p);
            }
        } catch (Exception ignored) {
            // invalid path - skip
        }
    }

    /** Creates the parent directory if needed and verifies we can actually write there. */
    private static boolean isWritableLocation(Path file) {
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            if (Files.exists(file)) {
                return Files.isReadable(file) && Files.isWritable(file)
                        && (dir == null || Files.isWritable(dir));
            }
            // Probe by actually creating a file (catches read-only mounts too).
            Path probe = file.resolveSibling(file.getFileName() + ".probe");
            Files.write(probe, EMPTY_JSON);
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception e) {
            log.debug("Settings location {} not usable: {}", file, e.getMessage());
            return false;
        }
    }

    /** The resolved settings file location. */
    public Path getFile() {
        return file;
    }

    /** Reads the stored settings JSON, or {@code {}} when none exist yet. */
    public synchronized byte[] load() {
        if (file == null || !Files.exists(file)) {
            return EMPTY_JSON.clone();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            return bytes.length == 0 ? EMPTY_JSON.clone() : bytes;
        } catch (IOException e) {
            log.warn("Cannot read settings file {}: {}", file, e.getMessage());
            return EMPTY_JSON.clone();
        }
    }

    /** Persists the given settings JSON, replacing any previous content. */
    public synchronized void save(byte[] data) {
        byte[] payload = (data == null || data.length == 0) ? EMPTY_JSON : data;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // Write to a temp file then move into place so a crash can't leave a
            // half-written settings file.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, payload);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new StorageException("Cannot save settings: " + e.getMessage(), e);
        }
    }
}

