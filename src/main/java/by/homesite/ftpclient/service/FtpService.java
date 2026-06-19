package by.homesite.ftpclient.service;
import by.homesite.ftpclient.model.FileItem;
import org.apache.commons.net.ProtocolCommandEvent;
import org.apache.commons.net.ProtocolCommandListener;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
/**
 * Stateless FTP operations. Every public method opens a fresh connection,
 * performs the work and closes it again, so no server-side session state is
 * kept between REST calls. Connection parameters are supplied with each call.
 */
@Service
public class FtpService {
    private static final long MAX_VIEW_BYTES = 2L * 1024 * 1024;
    /** Larger limit for binary/image previews (photos routinely exceed a few MB). */
    private static final long MAX_RAW_BYTES = 25L * 1024 * 1024;
    /** Buffer size for streamed transfers (also the granularity for progress / cancel). */
    private static final int TRANSFER_BUF = 64 * 1024;
    private final StorageService storage;
    public FtpService(StorageService storage) {
        this.storage = storage;
    }
    /** Connection parameters supplied by the client. */
    public record FtpConn(String host, int port, String user, String password, boolean passive) {
    }
    // ---- connection lifecycle ----
    private FTPClient open(FtpConn conn) {
        return open(conn, null);
    }
    private FTPClient open(FtpConn conn, List<String> log) {
        if (conn == null || conn.host() == null || conn.host().isBlank()) {
            throw new StorageException("FTP host is required");
        }
        FTPClient c = new FTPClient();
        c.setConnectTimeout(15000);
        c.setDefaultTimeout(15000);
        // Control-channel charset MUST be set BEFORE connect(), otherwise the
        // client's internal encoder is initialised with the JVM default and
        // non-ASCII paths (e.g. Cyrillic) get mangled into '?'.
        c.setControlEncoding("UTF-8");
        c.setAutodetectUTF8(true);
        if (log != null) {
            c.addProtocolCommandListener(new CollectingListener(log));
        }
        try {
            int port = conn.port() > 0 ? conn.port() : 21;
            c.connect(conn.host(), port);
            int reply = c.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                safeDisconnect(c);
                throw new StorageException("FTP server refused connection (code " + reply + ")");
            }
            boolean ok = c.login(
                    conn.user() == null ? "anonymous" : conn.user(),
                    conn.password() == null ? "" : conn.password());
            if (!ok) {
                safeDisconnect(c);
                throw new StorageException("FTP login failed");
            }
            // Best-effort: ask the server to use UTF-8 for path names. Most
            // modern servers support this; ignore failure for the others.
            try {
                c.sendCommand("OPTS", "UTF8 ON");
            } catch (IOException ignored) {
                // not critical
            }
            if (conn.passive()) {
                c.enterLocalPassiveMode();
            } else {
                c.enterLocalActiveMode();
            }
            c.setFileType(FTP.BINARY_FILE_TYPE);
            return c;
        } catch (IOException e) {
            safeDisconnect(c);
            throw new StorageException("FTP connection error: " + e.getMessage(), e);
        }
    }
    /** Collects sent commands and received replies into a log list (passwords masked). */
    private static final class CollectingListener implements ProtocolCommandListener {
        private final List<String> log;
        CollectingListener(List<String> log) {
            this.log = log;
        }
        @Override
        public void protocolCommandSent(ProtocolCommandEvent event) {
            String msg = event.getMessage();
            if (msg != null && msg.toUpperCase().startsWith("PASS")) {
                msg = "PASS ******\r\n";
            }
            add("> ", msg);
        }
        @Override
        public void protocolReplyReceived(ProtocolCommandEvent event) {
            add("< ", event.getMessage());
        }
        private void add(String prefix, String msg) {
            if (msg == null) {
                return;
            }
            for (String line : msg.split("\\r?\\n")) {
                if (!line.isBlank()) {
                    log.add(prefix + line.trim());
                }
            }
        }
    }
    private void close(FTPClient c) {
        if (c == null) {
            return;
        }
        try {
            if (c.isConnected()) {
                c.logout();
            }
        } catch (IOException ignored) {
            // ignore
        } finally {
            safeDisconnect(c);
        }
    }
    private void safeDisconnect(FTPClient c) {
        try {
            if (c.isConnected()) {
                c.disconnect();
            }
        } catch (IOException ignored) {
            // ignore
        }
    }
    private <T> T withClient(FtpConn conn, Function<FTPClient, T> work) {
        FTPClient c = open(conn);
        try {
            return work.apply(c);
        } finally {
            close(c);
        }
    }
    // ---- operations ----
    /**
     * Connects, lists the working (or given) directory, and returns the protocol
     * log so the UI can show connection progress. Never throws: on failure it
     * returns a map with an {@code error} field plus whatever log was collected.
     */
    public Map<String, Object> connectWithLog(FtpConn conn, String path) {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        int port = conn != null && conn.port() > 0 ? conn.port() : 21;
        String host = conn != null ? conn.host() : null;
        log.add("Connecting to " + host + ":" + port + " ...");
        FTPClient c = null;
        try {
            c = open(conn, log);
            log.add("Login successful, mode " + (conn.passive() ? "PASSIVE" : "ACTIVE") + ".");
            String dir = (path == null || path.isBlank()) ? c.printWorkingDirectory() : path;
            if (dir == null || dir.isBlank()) {
                dir = "/";
            }
            List<FileItem> items = listItems(c, dir);
            log.add("Listed " + items.size() + " item(s) in " + dir + ".");
            Map<String, Object> result = new HashMap<>();
            result.put("path", dir);
            result.put("items", items);
            result.put("log", new ArrayList<>(log));
            return result;
        } catch (StorageException | IOException e) {
            log.add("ERROR: " + e.getMessage());
            Map<String, Object> result = new HashMap<>();
            result.put("error", e.getMessage());
            result.put("path", path == null ? "" : path);
            result.put("items", List.of());
            result.put("log", new ArrayList<>(log));
            return result;
        } finally {
            close(c);
        }
    }
    /** Lists a remote directory. When {@code path} is blank, the login working directory is used. */
    public Map<String, Object> list(FtpConn conn, String path) {
        return withClient(conn, c -> {
            try {
                String dir = (path == null || path.isBlank()) ? c.printWorkingDirectory() : path;
                if (dir == null || dir.isBlank()) {
                    dir = "/";
                }
                List<FileItem> items = listItems(c, dir);
                return Map.of("path", dir, "items", items);
            } catch (IOException e) {
                throw new StorageException("Cannot list FTP directory: " + e.getMessage(), e);
            }
        });
    }
    private List<FileItem> listItems(FTPClient c, String dir) throws IOException {
        FTPFile[] files = c.listFiles(dir);
        List<FileItem> items = new ArrayList<>();
        for (FTPFile f : files) {
            if (f == null) {
                continue;
            }
            String name = f.getName();
            if (name == null || name.equals(".") || name.equals("..")) {
                continue;
            }
            long modified = f.getTimestamp() != null ? f.getTimestamp().getTimeInMillis() : 0;
            items.add(new FileItem(name, joinRemote(dir, name), f.isDirectory(), f.getSize(), modified));
        }
        items.sort(Comparator
                .comparing(FileItem::directory).reversed()
                .thenComparing(i -> i.name().toLowerCase()));
        return items;
    }
    public FileItem mkdir(FtpConn conn, String parent, String name) {
        validateName(name);
        return withClient(conn, c -> {
            try {
                String target = joinRemote(parent, name);
                if (!c.makeDirectory(target)) {
                    throw new StorageException("Cannot create directory: " + c.getReplyString());
                }
                return new FileItem(name, target, true, 0, 0);
            } catch (IOException e) {
                throw new StorageException("Cannot create FTP directory: " + e.getMessage(), e);
            }
        });
    }
    public void rename(FtpConn conn, String path, String newName) {
        validateName(newName);
        withClient(conn, c -> {
            try {
                String target = joinRemote(parentRemote(path), newName);
                if (!c.rename(path, target)) {
                    throw new StorageException("Cannot rename: " + c.getReplyString());
                }
                return null;
            } catch (IOException e) {
                throw new StorageException("Cannot rename on FTP: " + e.getMessage(), e);
            }
        });
    }
    public void delete(FtpConn conn, List<String> paths) {
        delete(conn, paths, null);
    }

    public void delete(FtpConn conn, List<String> paths, JobService.Job job) {
        FTPClient c = open(conn, job == null ? null : job.logRef());
        if (job != null) job.setCancelAction(() -> safeDisconnect(c));
        try {
            if (job != null) {
                int total = 0;
                for (String p : paths) total += countRemoteFiles(c, p);
                job.setTotal(total);
            }
            for (String p : paths) {
                checkCancel(job);
                deleteRemote(c, p, job);
            }
        } finally {
            close(c);
        }
    }

    /** Counts files (not bytes) under a remote path. Used for delete progress. */
    private int countRemoteFiles(FTPClient c, String path) {
        try {
            if (!isDirectory(c, path)) return 1;
            int n = 0;
            for (FTPFile f : c.listFiles(path)) {
                String name = f.getName();
                if (name == null || name.equals(".") || name.equals("..")) continue;
                n += countRemoteFiles(c, joinRemote(path, name));
            }
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    private void deleteRemote(FTPClient c, String path) { deleteRemote(c, path, null); }

    private void deleteRemote(FTPClient c, String path, JobService.Job job) {
        try {
            if (isDirectory(c, path)) {
                for (FTPFile f : c.listFiles(path)) {
                    String name = f.getName();
                    if (name == null || name.equals(".") || name.equals("..")) {
                        continue;
                    }
                    checkCancel(job);
                    deleteRemote(c, joinRemote(path, name), job);
                }
                if (!c.removeDirectory(path)) {
                    throw new StorageException("Cannot remove directory " + path + ": " + c.getReplyString());
                }
            } else {
                if (job != null) job.setCurrentItem("Deleting " + path);
                if (!c.deleteFile(path)) {
                    throw new StorageException("Cannot delete file " + path + ": " + c.getReplyString());
                }
                if (job != null) job.incProcessed();
            }
        } catch (IOException e) {
            throw new StorageException("Cannot delete on FTP: " + e.getMessage(), e);
        }
    }
    /** Reads a remote text file (UTF-8) for previewing. */
    public Map<String, Object> readText(FtpConn conn, String path) {
        return withClient(conn, c -> {
            try (InputStream in = c.retrieveFileStream(path)) {
                if (in == null) {
                    throw new StorageException("Cannot open remote file: " + c.getReplyString());
                }
                byte[] bytes = in.readNBytes((int) MAX_VIEW_BYTES);
                c.completePendingCommand();
                return Map.of(
                        "name", basename(path),
                        "path", path,
                        "content", new String(bytes, StandardCharsets.UTF_8)
                );
            } catch (IOException e) {
                throw new StorageException("Cannot read remote file: " + e.getMessage(), e);
            }
        });
    }

    /** Reads a remote file as raw bytes for binary previews (e.g. images). */
    public StorageService.RawFile readBytes(FtpConn conn, String path) {
        return withClient(conn, c -> {
            try (InputStream in = c.retrieveFileStream(path)) {
                if (in == null) {
                    throw new StorageException("Cannot open remote file: " + c.getReplyString());
                }
                byte[] bytes = in.readNBytes((int) MAX_RAW_BYTES);
                c.completePendingCommand();
                String name = basename(path);
                String type = guessContentType(name);
                return new StorageService.RawFile(name, type, bytes);
            } catch (IOException e) {
                throw new StorageException("Cannot read remote file: " + e.getMessage(), e);
            }
        });
    }

    /** Streams a single remote file to the given output (browser download). */
    public void streamFile(FtpConn conn, String path, java.io.OutputStream out) {
        FTPClient c = open(conn);
        try (InputStream in = c.retrieveFileStream(path)) {
            if (in == null) {
                throw new StorageException("Cannot open remote file: " + c.getReplyString());
            }
            in.transferTo(out);
            c.completePendingCommand();
        } catch (IOException e) {
            throw new StorageException("Cannot download remote file: " + e.getMessage(), e);
        } finally {
            close(c);
        }
    }

    /** Streams the given remote paths (files/folders) as a ZIP to the output. */
    public void writeZip(FtpConn conn, List<String> paths, java.io.OutputStream out) {
        FTPClient c = open(conn);
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            for (String p : paths) {
                addToZip(c, p, parentRemote(p), zip);
            }
        } catch (IOException e) {
            throw new StorageException("Cannot create zip: " + e.getMessage(), e);
        } finally {
            close(c);
        }
    }

    private void addToZip(FTPClient c, String remote, String base,
                          java.util.zip.ZipOutputStream zip) throws IOException {
        String entryName = relativeRemote(base, remote);
        if (isDirectory(c, remote)) {
            if (!entryName.isEmpty()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entryName + "/"));
                zip.closeEntry();
            }
            for (FTPFile f : c.listFiles(remote)) {
                String name = f.getName();
                if (name == null || name.equals(".") || name.equals("..")) {
                    continue;
                }
                addToZip(c, joinRemote(remote, name), base, zip);
            }
        } else {
            zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
            try (InputStream in = c.retrieveFileStream(remote)) {
                if (in != null) {
                    in.transferTo(zip);
                }
            }
            c.completePendingCommand();
            zip.closeEntry();
        }
    }

    /** Path of {@code full} relative to {@code base} (forward slashes, no leading slash). */
    private static String relativeRemote(String base, String full) {
        String prefix = base.equals("/") ? "/" : base + "/";
        if (full.startsWith(prefix)) {
            return full.substring(prefix.length());
        }
        return basename(full);
    }

    private static String guessContentType(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
    /** Uploads local files/folders into a remote directory (local -> ftp). */
    public void upload(FtpConn conn, List<String> localPaths, String remoteDir) {
        upload(conn, localPaths, remoteDir, null, OverwritePolicy.REPLACE);
    }

    /** Job-aware variant that reports progress and protocol log into {@code job}. */
    public void upload(FtpConn conn, List<String> localPaths, String remoteDir, JobService.Job job) {
        upload(conn, localPaths, remoteDir, job, OverwritePolicy.REPLACE);
    }

    /** Job-aware variant with explicit overwrite policy. */
    public void upload(FtpConn conn, List<String> localPaths, String remoteDir,
                       JobService.Job job, OverwritePolicy policy) {
        FTPClient c = open(conn, job == null ? null : job.logRef());
        if (job != null) job.setCancelAction(() -> safeDisconnect(c));
        try {
            if (job != null) {
                long total = 0;
                for (String lp : localPaths) total += countLocalBytes(storage.resolve(lp));
                job.setTotal(total);
            }
            for (String lp : localPaths) {
                checkCancel(job);
                uploadPath(c, storage.resolve(lp), remoteDir, job, policy, true);
            }
        } finally {
            close(c);
        }
    }

    private void uploadPath(FTPClient c, Path local, String remoteParent, JobService.Job job,
                            OverwritePolicy policy, boolean topLevel) {
        String desired = local.getFileName().toString();
        String remote = joinRemote(remoteParent, desired);
        try {
            // Top-level conflict resolution. Inside recursion (topLevel=false) we
            // always merge directories and use the same per-file policy.
            if (topLevel) {
                boolean exists = remoteExists(c, remote);
                if (exists) {
                    if (policy == OverwritePolicy.SKIP) {
                        return;
                    }
                    if (policy == OverwritePolicy.RENAME) {
                        remote = joinRemote(remoteParent, uniqueRemoteName(c, remoteParent, desired));
                    }
                    // REPLACE / RESUME -> just overwrite/merge into existing
                }
            }
            if (Files.isDirectory(local)) {
                c.makeDirectory(remote); // ignore error if already there
                try (var children = Files.list(local)) {
                    for (Path child : children.toList()) {
                        checkCancel(job);
                        uploadPath(c, child, remote, job, policy, false);
                    }
                }
            } else {
                if (job != null) job.setCurrentItem("Uploading " + remote);
                if (!topLevel && remoteExists(c, remote)) {
                    if (policy == OverwritePolicy.SKIP) return;
                    if (policy == OverwritePolicy.RENAME) {
                        remote = joinRemote(remoteParent, uniqueRemoteName(c, remoteParent, desired));
                    }
                    // REPLACE / RESUME -> overwrite (FTP STOR overwrites by default)
                }
                try (InputStream in = Files.newInputStream(local);
                     OutputStream out = c.storeFileStream(remote)) {
                    if (out == null) {
                        throw new StorageException("Upload failed for " + remote + ": " + c.getReplyString());
                    }
                    pump(in, out, job);
                }
                if (!c.completePendingCommand()) {
                    throw new StorageException("Upload failed for " + remote + ": " + c.getReplyString());
                }
            }
        } catch (IOException e) {
            throw new StorageException("Upload error: " + e.getMessage(), e);
        }
    }

    private long countLocalBytes(Path p) {
        if (Files.isRegularFile(p)) {
            try { return Files.size(p); } catch (IOException e) { return 0; }
        }
        if (!Files.isDirectory(p)) return 0;
        long n = 0;
        try (var s = Files.walk(p)) {
            for (var x : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(x)) {
                    try { n += Files.size(x); } catch (IOException ignored) { /* skip */ }
                }
            }
        } catch (IOException ignored) { /* best-effort */ }
        return n;
    }

    /** Downloads remote files/folders into a local directory (ftp -> local). */
    public void download(FtpConn conn, List<String> remotePaths, String localDir) {
        download(conn, remotePaths, localDir, null, OverwritePolicy.REPLACE);
    }

    public void download(FtpConn conn, List<String> remotePaths, String localDir, JobService.Job job) {
        download(conn, remotePaths, localDir, job, OverwritePolicy.REPLACE);
    }

    /** Job-aware variant with explicit overwrite policy (RESUME is supported here via FTP REST). */
    public void download(FtpConn conn, List<String> remotePaths, String localDir,
                         JobService.Job job, OverwritePolicy policy) {
        Path base = storage.resolve(localDir);
        FTPClient c = open(conn, job == null ? null : job.logRef());
        if (job != null) job.setCancelAction(() -> safeDisconnect(c));
        try {
            if (job != null) {
                long total = 0;
                for (String rp : remotePaths) total += countRemoteBytes(c, rp);
                job.setTotal(total);
            }
            for (String rp : remotePaths) {
                checkCancel(job);
                downloadPath(c, rp, base, job, policy, true);
            }
        } finally {
            close(c);
        }
    }

    private void downloadPath(FTPClient c, String remote, Path localParent, JobService.Job job,
                              OverwritePolicy policy, boolean topLevel) {
        String desired = basename(remote);
        Path local = localParent.resolve(desired);
        try {
            if (topLevel && Files.exists(local)) {
                if (policy == OverwritePolicy.SKIP) {
                    return;
                }
                if (policy == OverwritePolicy.RENAME) {
                    local = localParent.resolve(StorageService.uniqueName(localParent, desired));
                }
                // REPLACE / RESUME handled below per-file
            }
            if (isDirectory(c, remote)) {
                Files.createDirectories(local);
                for (FTPFile f : c.listFiles(remote)) {
                    String name = f.getName();
                    if (name == null || name.equals(".") || name.equals("..")) {
                        continue;
                    }
                    checkCancel(job);
                    downloadPath(c, joinRemote(remote, name), local, job, policy, false);
                }
            } else {
                if (local.getParent() != null) {
                    Files.createDirectories(local.getParent());
                }
                // Per-file policy when descending into a merged directory.
                if (!topLevel && Files.exists(local)) {
                    if (policy == OverwritePolicy.SKIP) return;
                    if (policy == OverwritePolicy.RENAME) {
                        local = local.getParent().resolve(
                                StorageService.uniqueName(local.getParent(), desired));
                    }
                    // REPLACE / RESUME continue below
                }
                long resumeFrom = 0L;
                if (policy == OverwritePolicy.RESUME && Files.isRegularFile(local)) {
                    resumeFrom = Files.size(local);
                    long remoteSize = remoteFileSize(c, remote);
                    if (remoteSize > 0 && resumeFrom >= remoteSize) {
                        // Already have everything — count it as processed and skip transfer.
                        if (job != null) job.addProcessed(remoteSize);
                        if (job != null) job.addLog("Skipping " + remote + " (already complete)");
                        return;
                    }
                    if (resumeFrom > 0) {
                        c.setRestartOffset(resumeFrom);
                        if (job != null) {
                            job.addLog("Resuming " + remote + " from offset " + resumeFrom);
                            job.addProcessed(resumeFrom);
                        }
                    }
                }
                if (job != null) job.setCurrentItem("Downloading " + remote);
                boolean append = (resumeFrom > 0);
                try (InputStream in = c.retrieveFileStream(remote);
                     OutputStream out = append
                             ? Files.newOutputStream(local, java.nio.file.StandardOpenOption.APPEND)
                             : Files.newOutputStream(local)) {
                    if (in == null) {
                        throw new StorageException("Download failed for " + remote + ": " + c.getReplyString());
                    }
                    pump(in, out, job);
                }
                if (!c.completePendingCommand()) {
                    throw new StorageException("Download failed for " + remote + ": " + c.getReplyString());
                }
            }
        } catch (IOException e) {
            throw new StorageException("Download error: " + e.getMessage(), e);
        }
    }

    /** Returns the size of a remote regular file, or -1 if it cannot be determined. */
    private long remoteFileSize(FTPClient c, String path) {
        try {
            String parent = parentRemote(path);
            String name = basename(path);
            for (FTPFile f : c.listFiles(parent)) {
                if (f != null && name.equals(f.getName())) {
                    return f.getSize();
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return -1;
    }

    /** True if the given remote path exists (file or directory). */
    private boolean remoteExists(FTPClient c, String path) {
        try {
            String parent = parentRemote(path);
            String name = basename(path);
            FTPFile[] files = c.listFiles(parent);
            if (files != null) {
                for (FTPFile f : files) {
                    if (f != null && name.equals(f.getName())) {
                        return true;
                    }
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return isDirectory(c, path);
    }

    /** Picks a unique remote name in {@code parent} by appending " 1", " 2", ... */
    private String uniqueRemoteName(FTPClient c, String parent, String desired) {
        if (!remoteExists(c, joinRemote(parent, desired))) {
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
            if (!remoteExists(c, joinRemote(parent, candidate))) {
                return candidate;
            }
        }
        throw new StorageException("Cannot find a unique remote name for '" + desired + "'");
    }

    private long countRemoteBytes(FTPClient c, String path) {
        try {
            if (!isDirectory(c, path)) {
                // For a single file we need its size. listFiles on the parent and find ours.
                String parent = parentRemote(path);
                String name = basename(path);
                for (FTPFile f : c.listFiles(parent)) {
                    if (f != null && name.equals(f.getName())) {
                        return f.getSize();
                    }
                }
                return 0;
            }
            long n = 0;
            for (FTPFile f : c.listFiles(path)) {
                String name = f.getName();
                if (name == null || name.equals(".") || name.equals("..")) continue;
                if (f.isDirectory()) {
                    n += countRemoteBytes(c, joinRemote(path, name));
                } else {
                    n += f.getSize();
                }
            }
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Streams bytes from {@code in} to {@code out} in {@link #TRANSFER_BUF}-sized
     * chunks. Between chunks: updates {@code job.processed} for live progress and
     * checks {@code job.isCancelled()} so a cancel request stops the transfer
     * promptly instead of waiting for the whole file to finish.
     */
    private void pump(InputStream in, OutputStream out, JobService.Job job) throws IOException {
        byte[] buf = new byte[TRANSFER_BUF];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (job != null && job.isCancelled()) {
                throw new StorageException("Operation cancelled");
            }
            out.write(buf, 0, n);
            if (job != null) job.addProcessed(n);
        }
    }

    /** Copies remote files/folders from one server to another (ftp -> ftp), streaming through the app. */
    public void copyRemote(FtpConn src, FtpConn dst, List<String> remotePaths, String remoteDir) {
        copyRemote(src, dst, remotePaths, remoteDir, null, OverwritePolicy.REPLACE);
    }

    public void copyRemote(FtpConn src, FtpConn dst, List<String> remotePaths, String remoteDir, JobService.Job job) {
        copyRemote(src, dst, remotePaths, remoteDir, job, OverwritePolicy.REPLACE);
    }

    public void copyRemote(FtpConn src, FtpConn dst, List<String> remotePaths, String remoteDir,
                           JobService.Job job, OverwritePolicy policy) {
        FTPClient sCli = open(src, job == null ? null : job.logRef());
        FTPClient dCli = open(dst, job == null ? null : job.logRef());
        if (job != null) job.setCancelAction(() -> { safeDisconnect(sCli); safeDisconnect(dCli); });
        try {
            if (job != null) {
                long total = 0;
                for (String rp : remotePaths) total += countRemoteBytes(sCli, rp);
                job.setTotal(total);
            }
            for (String rp : remotePaths) {
                checkCancel(job);
                copyRemotePath(sCli, dCli, rp, remoteDir, job, policy, true);
            }
        } finally {
            close(dCli);
            close(sCli);
        }
    }

    private void copyRemotePath(FTPClient s, FTPClient d, String remote, String destParent,
                                JobService.Job job, OverwritePolicy policy, boolean topLevel) {
        String desired = basename(remote);
        String target = joinRemote(destParent, desired);
        try {
            if (topLevel && remoteExists(d, target)) {
                if (policy == OverwritePolicy.SKIP) {
                    return;
                }
                if (policy == OverwritePolicy.RENAME) {
                    target = joinRemote(destParent, uniqueRemoteName(d, destParent, desired));
                }
                // REPLACE / RESUME -> overwrite/merge
            }
            if (isDirectory(s, remote)) {
                d.makeDirectory(target);
                for (FTPFile f : s.listFiles(remote)) {
                    String name = f.getName();
                    if (name == null || name.equals(".") || name.equals("..")) {
                        continue;
                    }
                    checkCancel(job);
                    copyRemotePath(s, d, joinRemote(remote, name), target, job, policy, false);
                }
            } else {
                if (!topLevel && remoteExists(d, target)) {
                    if (policy == OverwritePolicy.SKIP) return;
                    if (policy == OverwritePolicy.RENAME) {
                        target = joinRemote(destParent, uniqueRemoteName(d, destParent, desired));
                    }
                    // REPLACE / RESUME -> overwrite
                }
                if (job != null) job.setCurrentItem("Copying " + remote + " -> " + target);
                try (InputStream in = s.retrieveFileStream(remote);
                     OutputStream out = d.storeFileStream(target)) {
                    if (in == null) {
                        throw new StorageException("Cannot read " + remote + ": " + s.getReplyString());
                    }
                    if (out == null) {
                        throw new StorageException("Cannot write " + target + ": " + d.getReplyString());
                    }
                    pump(in, out, job);
                }
                if (!s.completePendingCommand()) {
                    throw new StorageException("Cannot read " + remote + ": " + s.getReplyString());
                }
                if (!d.completePendingCommand()) {
                    throw new StorageException("Cannot write " + target + ": " + d.getReplyString());
                }
            }
        } catch (IOException e) {
            throw new StorageException("FTP-to-FTP copy error: " + e.getMessage(), e);
        }
    }

    // ---- preflight: detect destination collisions BEFORE starting a transfer ----

    /** Top-level conflicts for an upload (local -> remote). */
    public List<Conflict> previewUploadConflicts(FtpConn conn, List<String> localPaths, String remoteDir) {
        return withClient(conn, c -> {
            List<Conflict> out = new ArrayList<>();
            for (String lp : localPaths) {
                Path local = storage.resolve(lp);
                String name = local.getFileName().toString();
                String target = joinRemote(remoteDir, name);
                if (!remoteExists(c, target)) {
                    continue;
                }
                boolean dir = isDirectory(c, target);
                long srcSize = -1;
                long dstSize = -1;
                try {
                    if (Files.isRegularFile(local)) srcSize = Files.size(local);
                } catch (IOException ignored) { /* informational */ }
                if (!dir) dstSize = remoteFileSize(c, target);
                out.add(new Conflict(name, lp, target, srcSize, dstSize, dir));
            }
            return out;
        });
    }

    /** Top-level conflicts for a download (remote -> local). */
    public List<Conflict> previewDownloadConflicts(FtpConn conn, List<String> remotePaths, String localDir) {
        return withClient(conn, c -> {
            Path base = storage.resolve(localDir);
            List<Conflict> out = new ArrayList<>();
            for (String rp : remotePaths) {
                String name = basename(rp);
                Path target = base.resolve(name);
                if (!Files.exists(target)) {
                    continue;
                }
                boolean dir = Files.isDirectory(target);
                long srcSize = -1;
                long dstSize = -1;
                if (!isDirectory(c, rp)) srcSize = remoteFileSize(c, rp);
                try {
                    if (!dir) dstSize = Files.size(target);
                } catch (IOException ignored) { /* informational */ }
                out.add(new Conflict(name, rp, storage.relativize(target), srcSize, dstSize, dir));
            }
            return out;
        });
    }

    /** Top-level conflicts for a remote-to-remote copy. */
    public List<Conflict> previewCopyRemoteConflicts(FtpConn src, FtpConn dst,
                                                     List<String> remotePaths, String remoteDir) {
        FTPClient sCli = open(src);
        FTPClient dCli = open(dst);
        try {
            List<Conflict> out = new ArrayList<>();
            for (String rp : remotePaths) {
                String name = basename(rp);
                String target = joinRemote(remoteDir, name);
                if (!remoteExists(dCli, target)) {
                    continue;
                }
                boolean dir = isDirectory(dCli, target);
                long srcSize = -1;
                long dstSize = -1;
                if (!isDirectory(sCli, rp)) srcSize = remoteFileSize(sCli, rp);
                if (!dir) dstSize = remoteFileSize(dCli, target);
                out.add(new Conflict(name, rp, target, srcSize, dstSize, dir));
            }
            return out;
        } finally {
            close(dCli);
            close(sCli);
        }
    }

    // ---- helpers ----
    private static void checkCancel(JobService.Job job) {
        if (job != null && job.isCancelled()) {
            throw new StorageException("Operation cancelled");
        }
    }
    private boolean isDirectory(FTPClient c, String path) {
        try {
            String pwd = c.printWorkingDirectory();
            boolean dir = c.changeWorkingDirectory(path);
            if (dir && pwd != null) {
                c.changeWorkingDirectory(pwd);
            }
            return dir;
        } catch (IOException e) {
            return false;
        }
    }
    private static String joinRemote(String dir, String name) {
        if (dir == null || dir.isEmpty() || dir.equals("/")) {
            return "/" + name;
        }
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }
    private static String parentRemote(String path) {
        if (path == null) {
            return "/";
        }
        int idx = path.lastIndexOf('/');
        return idx <= 0 ? "/" : path.substring(0, idx);
    }
    private static String basename(String path) {
        if (path == null) {
            return "";
        }
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = p.lastIndexOf('/');
        return idx < 0 ? p : p.substring(idx + 1);
    }
    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new StorageException("Name must not be empty");
        }
        if (name.contains("/") || name.contains("\\")) {
            throw new StorageException("Invalid name: " + name);
        }
    }
}
