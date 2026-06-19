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
        this.file = Paths.get(properties.getSettingsFile()).toAbsolutePath().normalize();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
        } catch (IOException e) {
            log.warn("Cannot create settings directory {}: {}", file.getParent(), e.getMessage());
        }
        log.info("User settings file: {}", file);
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

