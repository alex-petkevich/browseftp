package by.homesite.ftpclient.service;

/**
 * Thrown when a storage operation fails or a path violates the sandbox.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

