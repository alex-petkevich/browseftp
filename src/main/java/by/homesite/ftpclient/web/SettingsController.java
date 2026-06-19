package by.homesite.ftpclient.web;

import by.homesite.ftpclient.service.SettingsService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stores and returns the front-end's user settings (theme, saved FTP
 * connections, last-opened directories) as a single JSON document persisted on
 * the server (see {@link SettingsService}). The JSON is passed through as raw
 * bytes so the backend needs no JSON library of its own.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> get() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(settings.load());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> put(@RequestBody(required = false) byte[] body) {
        settings.save(body);
        return ResponseEntity.noContent().build();
    }
}

