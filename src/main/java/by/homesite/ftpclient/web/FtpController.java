package by.homesite.ftpclient.web;
import by.homesite.ftpclient.model.FileItem;
import by.homesite.ftpclient.service.Conflict;
import by.homesite.ftpclient.service.FtpService;
import by.homesite.ftpclient.service.FtpService.FtpConn;
import by.homesite.ftpclient.service.JobService;
import by.homesite.ftpclient.service.JobService.Job;
import by.homesite.ftpclient.service.OverwritePolicy;
import by.homesite.ftpclient.service.StorageService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
/**
 * REST API for FTP operations. Connection parameters travel with every request
 * (the server keeps no FTP session between calls). Long-running operations
 * (upload, download, delete, copy-remote) are dispatched as asynchronous jobs;
 * the controller returns {@code {"jobId": "..."}} and the UI polls
 * {@code /api/jobs/{id}} for progress.
 */
@RestController
@RequestMapping("/api/ftp")
public class FtpController {
    private final FtpService ftp;
    private final JobService jobs;
    public FtpController(FtpService ftp, JobService jobs) {
        this.ftp = ftp;
        this.jobs = jobs;
    }
    public record ListReq(FtpConn conn, String path) {}
    public record MkdirReq(FtpConn conn, String parent, String name) {}
    public record RenameReq(FtpConn conn, String path, String newName) {}
    public record DeleteReq(FtpConn conn, List<String> paths) {}
    public record ContentReq(FtpConn conn, String path) {}
    public record UploadReq(FtpConn conn, List<String> localPaths, String remoteDir, String overwrite) {}
    public record DownloadReq(FtpConn conn, List<String> remotePaths, String localDir, String overwrite) {}
    public record CopyRemoteReq(FtpConn srcConn, FtpConn destConn, List<String> remotePaths, String remoteDir, String overwrite) {}
    public record DownloadFileReq(FtpConn conn, String path) {}
    public record DownloadZipReq(FtpConn conn, List<String> paths) {}
    @PostMapping("/connect")
    public Map<String, Object> connect(@RequestBody ListReq req) {
        return ftp.connectWithLog(req.conn(), req.path());
    }
    @PostMapping("/list")
    public Map<String, Object> list(@RequestBody ListReq req) {
        return ftp.list(req.conn(), req.path());
    }
    @PostMapping("/mkdir")
    public FileItem mkdir(@RequestBody MkdirReq req) {
        return ftp.mkdir(req.conn(), req.parent(), req.name());
    }
    @PostMapping("/rename")
    public ResponseEntity<Void> rename(@RequestBody RenameReq req) {
        ftp.rename(req.conn(), req.path(), req.newName());
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody DeleteReq req) {
        Job job = jobs.createJob("ftp-delete", "Delete " + req.paths().size() + " item(s) on FTP");
        jobs.run(job, () -> ftp.delete(req.conn(), req.paths(), job));
        return Map.of("jobId", job.getId());
    }
    @PostMapping("/content")
    public Map<String, Object> content(@RequestBody ContentReq req) {
        return ftp.readText(req.conn(), req.path());
    }
    @PostMapping("/raw")
    public ResponseEntity<byte[]> raw(@RequestBody ContentReq req) {
        StorageService.RawFile r = ftp.readBytes(req.conn(), req.path());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(r.contentType()))
                .header("Content-Disposition", "inline; filename=\"" + r.name() + "\"")
                .body(r.bytes());
    }

    /** Streams a single remote file to the browser as an attachment. */
    @PostMapping("/download-file")
    public ResponseEntity<StreamingResponseBody> downloadFile(@RequestBody DownloadFileReq req) {
        String p = req.path();
        String filename = p.substring(p.lastIndexOf('/') + 1);
        StreamingResponseBody body = out -> ftp.streamFile(req.conn(), p, out);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    /** Streams several remote files/folders to the browser as a ZIP archive. */
    @PostMapping("/download-zip")
    public ResponseEntity<StreamingResponseBody> downloadZip(@RequestBody DownloadZipReq req) {
        StreamingResponseBody body = out -> ftp.writeZip(req.conn(), req.paths(), out);
        ContentDisposition cd = ContentDisposition.attachment().filename("download.zip").build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestBody UploadReq req) {
        OverwritePolicy policy = OverwritePolicy.parse(req.overwrite());
        Job job = jobs.createJob("upload",
                "Upload " + req.localPaths().size() + " item(s) to " + req.remoteDir());
        jobs.run(job, () -> ftp.upload(req.conn(), req.localPaths(), req.remoteDir(), job, policy));
        return Map.of("jobId", job.getId());
    }
    @PostMapping("/download")
    public Map<String, Object> download(@RequestBody DownloadReq req) {
        OverwritePolicy policy = OverwritePolicy.parse(req.overwrite());
        Job job = jobs.createJob("download",
                "Download " + req.remotePaths().size() + " item(s) to " + req.localDir());
        jobs.run(job, () -> ftp.download(req.conn(), req.remotePaths(), req.localDir(), job, policy));
        return Map.of("jobId", job.getId());
    }
    @PostMapping("/copy-remote")
    public Map<String, Object> copyRemote(@RequestBody CopyRemoteReq req) {
        OverwritePolicy policy = OverwritePolicy.parse(req.overwrite());
        Job job = jobs.createJob("copy-remote",
                "Copy " + req.remotePaths().size() + " item(s) ftp -> ftp");
        jobs.run(job, () -> ftp.copyRemote(req.srcConn(), req.destConn(),
                req.remotePaths(), req.remoteDir(), job, policy));
        return Map.of("jobId", job.getId());
    }
    @PostMapping("/upload/preflight")
    public Map<String, Object> uploadPreflight(@RequestBody UploadReq req) {
        List<Conflict> c = ftp.previewUploadConflicts(req.conn(), req.localPaths(), req.remoteDir());
        return Map.of("conflicts", c);
    }
    @PostMapping("/download/preflight")
    public Map<String, Object> downloadPreflight(@RequestBody DownloadReq req) {
        List<Conflict> c = ftp.previewDownloadConflicts(req.conn(), req.remotePaths(), req.localDir());
        return Map.of("conflicts", c);
    }
    @PostMapping("/copy-remote/preflight")
    public Map<String, Object> copyRemotePreflight(@RequestBody CopyRemoteReq req) {
        List<Conflict> c = ftp.previewCopyRemoteConflicts(req.srcConn(), req.destConn(),
                req.remotePaths(), req.remoteDir());
        return Map.of("conflicts", c);
    }
}
