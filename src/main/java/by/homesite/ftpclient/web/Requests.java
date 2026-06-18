package by.homesite.ftpclient.web;

import java.util.List;

/**
 * Request payloads used by the file operation endpoints.
 */
public final class Requests {

    private Requests() {
    }

    public record CreateDirRequest(String parent, String name) {
    }

    public record RenameRequest(String path, String newName) {
    }

    public record TransferRequest(List<String> paths, String destination, String overwrite) {
        public TransferRequest(List<String> paths, String destination) {
            this(paths, destination, null);
        }
    }

    public record DeleteRequest(List<String> paths) {
    }
}

