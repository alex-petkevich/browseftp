package by.homesite.ftpclient.service;

import by.homesite.ftpclient.config.StorageProperties;
import by.homesite.ftpclient.model.FileItem;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.Map;

/**
 * Provides sandboxed file-system operations. Every path supplied by the client
 * is resolved relative to the configured root directory and validated to make
 * sure it never escapes that root (protection against path traversal).
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final StorageProperties properties;
    private Path root;

    public StorageService(StorageProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.root = Paths.get(properties.getRootDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new StorageException("Cannot create/access root directory: " + root, e);
        }
        log.info("File manager root directory: {}", root);
    }

    /** Absolute root directory currently being managed. */
    public String getRootDisplay() {
        return root.toString();
    }

    /**
     * Resolves a relative path against the root and ensures the result stays
     * inside the sandbox.
     */
    public Path resolve(String relativePath) {
        String clean = relativePath == null ? "" : relativePath.trim();
        // normalise separators and strip leading slashes so it is always relative
        clean = clean.replace('\\', '/');
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        Path resolved = root.resolve(clean).normalize();
        if (!resolved.startsWith(root)) {
            throw new StorageException("Access outside of the managed directory is not allowed: " + relativePath);
        }
        return resolved;
    }

    /** Path relative to root expressed with forward slashes. */
    public String relativize(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /**
     * Lists the directory at the given relative path.
     */
    public List<FileItem> list(String relativePath) {
        Path dir = resolve(relativePath);
        if (!Files.exists(dir)) {
            throw new StorageException("Directory does not exist: " + relativePath);
        }
        if (!Files.isDirectory(dir)) {
            throw new StorageException("Not a directory: " + relativePath);
        }
        List<FileItem> items = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                items.add(toItem(p));
            }
        } catch (IOException e) {
            throw new StorageException("Cannot read directory: " + relativePath, e);
        }
        // directories first, then alphabetical (case-insensitive)
        items.sort(Comparator
                .comparing(FileItem::directory).reversed()
                .thenComparing(item -> item.name().toLowerCase()));
        return items;
    }

    private FileItem toItem(Path p) {
        boolean dir = Files.isDirectory(p);
        long size = 0;
        long modified = 0;
        try {
            BasicFileAttributes attr = Files.readAttributes(p, BasicFileAttributes.class);
            size = dir ? 0 : attr.size();
            modified = attr.lastModifiedTime().toMillis();
        } catch (IOException ignored) {
            // keep defaults for files we cannot stat
        }
        return new FileItem(
                p.getFileName().toString(),
                relativize(p),
                dir,
                size,
                modified
        );
    }

    /** Creates a new directory under {@code parent}. */
    public FileItem createDirectory(String parent, String name) {
        validateName(name);
        Path target = resolve(joinPath(parent, name));
        try {
            Files.createDirectory(target);
        } catch (IOException e) {
            throw new StorageException("Cannot create directory '" + name + "': " + e.getMessage(), e);
        }
        return toItem(target);
    }

    /** Renames a file or directory in place. */
    public FileItem rename(String path, String newName) {
        validateName(newName);
        Path source = resolve(path);
        if (!Files.exists(source)) {
            throw new StorageException("Path does not exist: " + path);
        }
        Path target = source.resolveSibling(newName);
        if (!target.normalize().startsWith(root)) {
            throw new StorageException("Invalid target name");
        }
        if (Files.exists(target)) {
            throw new StorageException("A file named '" + newName + "' already exists");
        }
        try {
            Files.move(source, target);
        } catch (IOException e) {
            throw new StorageException("Cannot rename: " + e.getMessage(), e);
        }
        return toItem(target);
    }

    /** Moves the given paths into the destination directory. */
    public void move(List<String> paths, String destinationDir) {
        move(paths, destinationDir, OverwritePolicy.REPLACE);
    }

    /** Moves with explicit overwrite policy. */
    public void move(List<String> paths, String destinationDir, OverwritePolicy policy) {
        Path dest = resolve(destinationDir);
        if (!Files.isDirectory(dest)) {
            throw new StorageException("Destination is not a directory: " + destinationDir);
        }
        for (String path : paths) {
            Path source = resolve(path);
            Path target = dest.resolve(source.getFileName());
            if (target.normalize().equals(source.normalize())) {
                continue; // moving onto itself
            }
            if (Files.exists(target)) {
                switch (policy) {
                    case SKIP:
                        continue;
                    case RENAME:
                        target = dest.resolve(uniqueName(dest, source.getFileName().toString()));
                        break;
                    case RESUME:
                    case REPLACE:
                    default:
                        // fall through to REPLACE_EXISTING
                        break;
                }
            }
            try {
                if (policy == OverwritePolicy.RENAME && Files.exists(target)) {
                    Files.move(source, target);
                } else {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new StorageException("Cannot move '" + source.getFileName() + "': " + e.getMessage(), e);
            }
        }
    }

    /** Copies the given paths into the destination directory (recursively). */
    public void copy(List<String> paths, String destinationDir) {
        copy(paths, destinationDir, OverwritePolicy.REPLACE);
    }

    /** Copies with explicit overwrite policy. */
    public void copy(List<String> paths, String destinationDir, OverwritePolicy policy) {
        Path dest = resolve(destinationDir);
        if (!Files.isDirectory(dest)) {
            throw new StorageException("Destination is not a directory: " + destinationDir);
        }
        for (String path : paths) {
            Path source = resolve(path);
            Path target = dest.resolve(source.getFileName());
            if (target.normalize().startsWith(source.normalize())) {
                throw new StorageException("Cannot copy a directory into itself: " + source.getFileName());
            }
            if (Files.exists(target)) {
                if (policy == OverwritePolicy.SKIP) {
                    continue;
                }
                if (policy == OverwritePolicy.RENAME) {
                    target = dest.resolve(uniqueName(dest, source.getFileName().toString()));
                }
                // REPLACE / RESUME: continue into copyRecursive which uses REPLACE_EXISTING
            }
            copyRecursive(source, target);
        }
    }

    /**
     * Looks at the top-level entries in {@code paths} and reports those whose
     * name already exists at {@code destinationDir}. Directories vs. files are
     * both reported so the UI can show one prompt before the transfer starts.
     */
    public List<Conflict> previewConflicts(List<String> paths, String destinationDir) {
        Path dest = resolve(destinationDir);
        if (!Files.isDirectory(dest)) {
            return List.of();
        }
        List<Conflict> out = new ArrayList<>();
        for (String path : paths) {
            Path source = resolve(path);
            Path target = dest.resolve(source.getFileName());
            if (target.normalize().equals(source.normalize())) {
                continue; // moving/copying onto itself is handled separately
            }
            if (!Files.exists(target)) {
                continue;
            }
            boolean dir = Files.isDirectory(target);
            long srcSize = -1;
            long dstSize = -1;
            try {
                if (!Files.isDirectory(source)) {
                    srcSize = Files.size(source);
                }
                if (!dir) {
                    dstSize = Files.size(target);
                }
            } catch (IOException ignored) {
                // best-effort; sizes are informational only
            }
            out.add(new Conflict(
                    source.getFileName().toString(),
                    relativize(source),
                    relativize(target),
                    srcSize,
                    dstSize,
                    dir
            ));
        }
        return out;
    }

    /** Returns a name in {@code dir} that does not yet exist by appending " 1", " 2", ... */
    public static String uniqueName(Path dir, String desired) {
        if (!Files.exists(dir.resolve(desired))) {
            return desired;
        }
        String base = desired;
        String ext = "";
        int dot = desired.lastIndexOf('.');
        if (dot > 0 && dot < desired.length() - 1) {
            base = desired.substring(0, dot);
            ext = desired.substring(dot);
        }
        for (int i = 1; i < 10000; i++) {
            String candidate = base + " " + i + ext;
            if (!Files.exists(dir.resolve(candidate))) {
                return candidate;
            }
        }
        throw new StorageException("Cannot find a unique name for '" + desired + "'");
    }

    private void copyRecursive(Path source, Path target) {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(src -> {
                Path rel = source.relativize(src);
                Path dst = target.resolve(rel);
                try {
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            throw new StorageException("Cannot copy '" + source.getFileName() + "': " + e.getMessage(), e);
        }
    }

    /** Deletes the given paths (directories are removed recursively). */
    public void delete(List<String> paths) {
        for (String path : paths) {
            Path target = resolve(path);
            if (target.equals(root)) {
                throw new StorageException("Cannot delete the root directory");
            }
            if (!Files.exists(target)) {
                continue;
            }
            deleteRecursive(target);
        }
    }

    /** Maximum number of bytes returned by {@link #readText(String)}. */
    private static final long MAX_VIEW_BYTES = 2L * 1024 * 1024;

    /** Raw file payload returned by {@link #readBytes(String)}. */
    public record RawFile(String name, String contentType, byte[] bytes) {
    }

    /**
     * Reads a file as raw bytes and guesses its content type, for binary previews
     * (e.g. images). Same size limit as the text viewer.
     */
    public RawFile readBytes(String path) {
        Path target = resolve(path);
        if (!Files.exists(target)) {
            throw new StorageException("File does not exist: " + path);
        }
        if (Files.isDirectory(target)) {
            throw new StorageException("Cannot view a directory");
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            throw new StorageException("Cannot read file: " + e.getMessage(), e);
        }
        if (size > MAX_VIEW_BYTES) {
            throw new StorageException("File is too large to view (" + size + " bytes, limit "
                    + MAX_VIEW_BYTES + ").");
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            String name = target.getFileName().toString();
            String type = Files.probeContentType(target);
            if (type == null || type.isBlank()) {
                type = "application/octet-stream";
            }
            return new RawFile(name, type, bytes);
        } catch (IOException e) {
            throw new StorageException("Cannot read file: " + e.getMessage(), e);
        }
    }

    /**
     * Reads a file as UTF-8 text for previewing. Refuses directories and files
     * larger than {@link #MAX_VIEW_BYTES}.
     */
    public Map<String, Object> readText(String path) {
        Path target = resolve(path);
        if (!Files.exists(target)) {
            throw new StorageException("File does not exist: " + path);
        }
        if (Files.isDirectory(target)) {
            throw new StorageException("Cannot view a directory");
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            throw new StorageException("Cannot read file: " + e.getMessage(), e);
        }
        if (size > MAX_VIEW_BYTES) {
            throw new StorageException("File is too large to view (" + size + " bytes, limit "
                    + MAX_VIEW_BYTES + ").");
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            String content = new String(bytes, StandardCharsets.UTF_8);
            return Map.of(
                    "path", path,
                    "name", target.getFileName().toString(),
                    "size", size,
                    "content", content
            );
        } catch (IOException e) {
            throw new StorageException("Cannot read file: " + e.getMessage(), e);
        }
    }

    private void deleteRecursive(Path target) {
        try (Stream<Path> walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            throw new StorageException("Cannot delete '" + target.getFileName() + "': " + e.getMessage(), e);
        }
    }

    private static String joinPath(String parent, String name) {
        if (parent == null || parent.isBlank()) {
            return name;
        }
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new StorageException("Name must not be empty");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new StorageException("Invalid name: " + name);
        }
    }
}

