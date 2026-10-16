package by.homesite.ftpclient.web;

import by.homesite.ftpclient.model.FileItem;
import by.homesite.ftpclient.service.Conflict;
import by.homesite.ftpclient.service.OverwritePolicy;
import by.homesite.ftpclient.service.StorageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST API exposing sandboxed file-management operations to the Angular UI.
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final StorageService storage;

    public FileController(StorageService storage) {
        this.storage = storage;
    }

    @GetMapping("/root")
    public Map<String, String> root() {
        return Map.of("root", storage.getRootDisplay());
    }

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(defaultValue = "") String path) {
        List<FileItem> items = storage.list(path);
        // Return the normalized, root-relative path (e.g. "a/../b" -> "b",
        // "/srv/ftpfiles/b" -> "b") so the UI always shows the canonical location.
        return Map.of(
                "path", storage.relativize(storage.resolve(path)),
                "items", items
        );
    }

    @PostMapping("/mkdir")
    public FileItem mkdir(@RequestBody Requests.CreateDirRequest request) {
        return storage.createDirectory(request.parent(), request.name());
    }

    /**
     * Uploads files from the browser into the directory {@code path}
     * (relative to the root). {@code overwrite} decides what happens when a
     * file with the same name exists (replace / rename / skip).
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam(defaultValue = "") String path,
                                      @RequestParam(required = false) String overwrite,
                                      @RequestParam("files") List<MultipartFile> files) throws IOException {
        OverwritePolicy policy = OverwritePolicy.parse(overwrite);
        int stored = 0;
        for (MultipartFile f : files) {
            try (java.io.InputStream in = f.getInputStream()) {
                if (storage.storeUpload(path, f.getOriginalFilename(), in, policy) != null) {
                    stored++;
                }
            }
        }
        return Map.of("uploaded", stored, "skipped", files.size() - stored);
    }

    @PostMapping("/rename")
    public FileItem rename(@RequestBody Requests.RenameRequest request) {
        return storage.rename(request.path(), request.newName());
    }

    @PostMapping("/move")
    public ResponseEntity<Void> move(@RequestBody Requests.TransferRequest request) {
        storage.move(request.paths(), request.destination(), OverwritePolicy.parse(request.overwrite()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/copy")
    public ResponseEntity<Void> copy(@RequestBody Requests.TransferRequest request) {
        storage.copy(request.paths(), request.destination(), OverwritePolicy.parse(request.overwrite()));
        return ResponseEntity.noContent().build();
    }

    /** Returns the list of items already present at {@code destination} for the given paths. */
    @PostMapping("/preflight")
    public Map<String, Object> preflight(@RequestBody Requests.TransferRequest request) {
        List<Conflict> conflicts = storage.previewConflicts(request.paths(), request.destination());
        return Map.of("conflicts", conflicts);
    }

    @PostMapping("/delete")
    public ResponseEntity<Void> delete(@RequestBody Requests.DeleteRequest request) {
        storage.delete(request.paths());
        return ResponseEntity.noContent().build();
    }

    /** Saves edited text into an existing file (used by the editor). */
    @PostMapping("/save")
    public FileItem save(@RequestBody Requests.SaveTextRequest request) {
        return storage.writeText(request.path(), request.content());
    }

    @GetMapping("/content")
    public Map<String, Object> content(@RequestParam String path) {
        return storage.readText(path);
    }

    @GetMapping("/raw")
    public ResponseEntity<byte[]> raw(@RequestParam String path) {
        StorageService.RawFile raw = storage.readBytes(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(raw.contentType()))
                .header("Content-Disposition", "inline; filename=\"" + raw.name() + "\"")
                .body(raw.bytes());
    }

    /** Downloads a single file to the browser as an attachment (streamed). */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam String path) throws IOException {
        Path file = storage.resolveForDownload(path);
        Resource resource = new FileSystemResource(file);
        String filename = file.getFileName().toString();
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .body(resource);
    }

    /** Downloads several files/folders to the browser as a streamed ZIP archive. */
    @GetMapping("/download-zip")
    public ResponseEntity<StreamingResponseBody> downloadZip(@RequestParam List<String> paths) {
        StreamingResponseBody body = out -> storage.writeZip(paths, out);
        ContentDisposition cd = ContentDisposition.attachment().filename("download.zip").build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }
}

