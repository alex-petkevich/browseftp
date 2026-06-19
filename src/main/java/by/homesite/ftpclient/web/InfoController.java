package by.homesite.ftpclient.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes basic application info (name and version) for the UI. The version is
 * read from the jar manifest's {@code Implementation-Version} (set by the
 * Spring Boot Maven plugin); it falls back to {@code "dev"} when running from
 * an exploded build.
 */
@RestController
@RequestMapping("/api/info")
public class InfoController {

    @GetMapping
    public Map<String, String> info() {
        String version = InfoController.class.getPackage().getImplementationVersion();
        return Map.of(
                "name", "Tiny FTP File Browser",
                "version", version == null || version.isBlank() ? "dev" : version
        );
    }
}

