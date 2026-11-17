package io.github.tinnet.wowfam.install;

/**
 * What the installer reports while it works. {@link #log} lines are the record of the run; the rest
 * lets a terminal show what is happening now, and may be ignored.
 */
public interface Progress {

  /** A line to keep in the output. */
  void log(String line);

  /** The run works on {@code count} addons. */
  default void start(int count) {}

  /** {@code addon} is now doing {@code what}, e.g. resolving; plain output shows nothing. */
  default void step(String addon, String what) {}

  /**
   * {@code addon} is now doing what {@code line} says ("downloading Questie-….zip (7.8 MB)"). Plain
   * output keeps the line; a terminal may show it only while it is current.
   */
  default void status(String addon, String line) {
    log(line);
  }

  /** The current download has {@code done} of {@code total} bytes; {@code total} may be 0. */
  default void bytes(long done, long total) {}

  /** {@code outcome}'s addon is finished. */
  default void finished(Installer.Outcome outcome) {}

  /** Plain lines, as wowfam always printed them. */
  static Progress lines(java.util.function.Consumer<String> out) {
    return out::accept;
  }
}
