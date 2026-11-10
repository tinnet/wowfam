package io.github.tinnet.wowfam.backend;

public class BackendException extends RuntimeException {
    public BackendException(String message) {
        super(message);
    }

    public BackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
