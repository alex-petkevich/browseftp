package by.homesite.ftpclient.model;

/**
 * Represents a single file or directory entry returned to the frontend.
 *
 * @param name         the display name
 * @param path         the path relative to the managed root directory (using '/')
 * @param directory    whether the entry is a directory
 * @param size         the size in bytes (0 for directories)
 * @param lastModified last modification time in epoch milliseconds
 */
public record FileItem(
        String name,
        String path,
        boolean directory,
        long size,
        long lastModified
) {
}

