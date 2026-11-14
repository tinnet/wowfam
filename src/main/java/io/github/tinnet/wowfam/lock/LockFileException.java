package io.github.tinnet.wowfam.lock;

/** {@code wowfam.lock} can't be read, parsed or written. */
public class LockFileException extends RuntimeException {
  public LockFileException(String message) {
    super(message);
  }

  public LockFileException(String message, Throwable cause) {
    super(message, cause);
  }
}
