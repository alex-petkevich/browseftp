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
        // Accept an absolute path that points inside the root (e.g. typed by the
        // user as "/srv/ftpfiles/photos") by converting it to a relative one.
        Path absolute = toAbsoluteOrNull(clean);
        if (absolute != null && absolute.startsWith(root)) {
            clean = root.relativize(absolute).toString().replace('\\', '/');
        }
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        Path resolved = root.resolve(clean).normalize();
        if (!resolved.startsWith(root)) {
            throw new StorageException("Access outside of the managed directory is not allowed: " + relativePath);
        }
        return resolved;
    }

    /** Parses {@code p} as an absolute, normalized path; null if it isn't absolute or is invalid. */
    private static Path toAbsoluteOrNull(String p) {
        if (p.isEmpty()) {
            return null;
        }
        try {
            Path candidate = Paths.get(p);
            return candidate.isAbsolute() ? candidate.normalize() : null;
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /** Path relative to root expressed with forward slashes. */
    public String relativize(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /** Size of the file system (disk) holding a directory, in bytes. */
    public record DiskSpace(long total, long free, long used) {
    }

    /**
     * Returns total / free / used space of the disk that holds the given
     * directory (so a sub-folder on another mount reports that mount). "free"
     * is the space available to this process; "used" excludes reserved blocks.
     */
    public DiskSpace diskSpace(String path) {
        Path dir = resolve(path);
        if (!Files.isDirectory(dir)) {
            dir = root;
        }
        try {
            java.nio.file.FileStore store = Files.getFileStore(dir);
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            long used = Math.max(0, total - store.getUnallocatedSpace());
            return new DiskSpace(total, free, used);
        } catch (IOException e) {
            throw new StorageException("Cannot read disk space: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves a path for downloading a single file: validates the sandbox,
     * ensures it exists and is a regular file.
     */
    public Path resolveForDownload(String path) {
        Path file = resolve(path);
        if (!Files.exists(file)) {
            throw new StorageException("File does not exist: " + path);
        }
        if (Files.isDirectory(file)) {
            throw new StorageException("Cannot download a directory directly; use the zip download.");
        }
        return file;
    }

    /**
     * Streams the given paths (files and/or directories) as a ZIP into
     * {@code out}. Entry names are relative to each selection's parent so that a
     * selected folder keeps its structure inside the archive.
     */
    public void writeZip(List<String> paths, java.io.OutputStream out) {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            for (String p : paths) {
                Path abs = resolve(p);
                if (!Files.exists(abs)) {
                    continue;
                }
                Path base = abs.getParent();
                if (Files.isDirectory(abs)) {
                    try (Stream<Path> walk = Files.walk(abs)) {
                        for (Path f : (Iterable<Path>) walk::iterator) {
                            String name = base.relativize(f).toString().replace('\\', '/');
                            if (name.isEmpty()) {
                                continue;
                            }
                            if (Files.isDirectory(f)) {
                                zip.putNextEntry(new java.util.zip.ZipEntry(name + "/"));
                                zip.closeEntry();
                            } else {
                                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                                Files.copy(f, zip);
                                zip.closeEntry();
                            }
                        }
                    }
                } else {
                    String name = base.relativize(abs).toString().replace('\\', '/');
                    zip.putNextEntry(new java.util.zip.ZipEntry(name));
                    Files.copy(abs, zip);
                    zip.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new StorageException("Cannot create zip: " + e.getMessage(), e);
        }
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

    /**
     * Extracts a safe file name from a browser-supplied upload name (some
     * browsers send a full client path) and validates it.
     */
    public static String uploadFileName(String originalName) {
        String name = originalName == null ? "" : originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        validateName(name);
        return name;
    }

    /**
     * Stores an uploaded file into {@code dir} (relative to the root).
     *
     * @return the stored item, or {@code null} when it was skipped because a
     *         file with the same name exists and the policy is SKIP
     */
    public FileItem storeUpload(String dir, String originalName, java.io.InputStream in, OverwritePolicy policy) {
        String name = uploadFileName(originalName);
        Path dest = resolve(dir);
        if (!Files.isDirectory(dest)) {
            throw new StorageException("Destination is not a directory: " + dir);
        }
        Path target = dest.resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new StorageException("Invalid file name: " + name);
        }
        if (Files.exists(target)) {
            if (policy == OverwritePolicy.SKIP) {
                return null;
            }
            if (policy == OverwritePolicy.RENAME) {
                target = dest.resolve(uniqueName(dest, name));
            } else if (Files.isDirectory(target)) {
                throw new StorageException("A folder named '" + name + "' already exists");
            }
        }
        // Write to a temp file first, then move into place, so a failed upload
        // never leaves a truncated file (or destroys the one being replaced).
        Path tmp = null;
        try {
            tmp = Files.createTempFile(dest, ".upload-", ".part");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            // Temp files are created owner-only (rw-------); give the result
            // the permissions of the file it replaces, or normal ones if new.
            if (Files.exists(target)) {
                copyPermissions(target, tmp);
            } else {
                setDefaultFilePermissions(tmp);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
        } catch (IOException e) {
            throw new StorageException("Cannot upload '" + name + "': " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
        return toItem(target);
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

    /**
     * Maximum number of bytes returned by {@link #readBytes(String)} (binary /
     * image previews). Larger than the text limit since photos routinely exceed
     * a couple of megabytes.
     */
    private static final long MAX_RAW_BYTES = 25L * 1024 * 1024;

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
        if (size > MAX_RAW_BYTES) {
            throw new StorageException("File is too large to preview (" + size + " bytes, limit "
                    + MAX_RAW_BYTES + ").");
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

    /**
     * Saves text (as UTF-8) into an existing file — used by the editor. The text
     * is written to a temp file in the same directory and then moved over the
     * original, so a failure never leaves a half-written file.
     */
    public FileItem writeText(String path, String content) {
        Path target = resolve(path);
        if (!Files.exists(target)) {
            throw new StorageException("File does not exist: " + path);
        }
        if (!Files.isRegularFile(target)) {
            throw new StorageException("Not a regular file: " + path);
        }
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_VIEW_BYTES) {
            throw new StorageException("Content is too large to save (" + bytes.length + " bytes, limit "
                    + MAX_VIEW_BYTES + ").");
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile(target.getParent(), ".edit-", ".part");
            Files.write(tmp, bytes);
            copyPermissions(target, tmp);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
        } catch (IOException e) {
            throw new StorageException("Cannot save '" + target.getFileName() + "': " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
        return toItem(target);
    }

    /** Copies POSIX permissions from {@code from} to {@code to} (no-op where unsupported, e.g. Windows). */
    private static void copyPermissions(Path from, Path to) {
        try {
            Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX file system, or not permitted: keep defaults
        }
    }

    /** Gives a new file the usual rw-r--r-- permissions (no-op where unsupported). */
    private static void setDefaultFilePermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX file system, or not permitted: keep defaults
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

