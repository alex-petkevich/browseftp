package by.homesite.ftpclient.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the managed root directory.
 * <p>
 * The value can be supplied either through {@code application.properties}
 * (property {@code app.root-dir}) or via the command line, e.g.
 * {@code java -jar ftpclient.jar --app.root-dir=C:/data}.
 */
@ConfigurationProperties(prefix = "app")
public class StorageProperties {

    /**
     * Root directory all file operations are restricted to.
     * Defaults to the current working directory when not set.
     */
    private String rootDir = System.getProperty("user.dir");

    /**
     * File where user settings (theme, saved FTP connections, last-opened
     * directories) are persisted. Defaults to {@code ~/.browseftp/settings.json}.
     * Override with {@code --app.settings-file=/path/to/settings.json}.
     */
    private String settingsFile =
            System.getProperty("user.home") + "/.browseftp/settings.json";

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    public String getSettingsFile() {
        return settingsFile;
    }

    public void setSettingsFile(String settingsFile) {
        this.settingsFile = settingsFile;
    }
}

