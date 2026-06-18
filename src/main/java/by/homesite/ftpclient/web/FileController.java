package by.homesite.ftpclient.web;

import by.homesite.ftpclient.model.FileItem;
import by.homesite.ftpclient.service.Conflict;
import by.homesite.ftpclient.service.OverwritePolicy;
import by.homesite.ftpclient.service.StorageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
        return Map.of(
                "path", path,
                "items", items
        );
    }

    @PostMapping("/mkdir")
    public FileItem mkdir(@RequestBody Requests.CreateDirRequest request) {
        return storage.createDirectory(request.parent(), request.name());
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
}

