package io.github.tinnet.wowfam.cli;

import io.github.tinnet.wowfam.install.Installer;
import io.github.tinnet.wowfam.install.Progress;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;

/**
 * Progress drawn in place, the way mise shows it: a status block at the bottom (a spinner, how many
 * addons are done, the current addon and its download) that is redrawn while the run goes on, with
 * finished addons and log lines printed above it, where they stay. Only for an interactive
 * terminal; everything else gets {@link Progress#lines plain lines}.
 */
final class LiveProgress implements Progress, AutoCloseable {

  private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
  private static final int BAR = 16;

  private final Terminal terminal;
  private final Display display;
  private final Function<Installer.Outcome, Optional<String>> outcomeLine;
  private final ScheduledExecutorService ticker;
  private final ScheduledFuture<?> ticking;
  private final Thread restoreCursor;
  private final long started = System.nanoTime();

  private int frame;
  private int count;
  private int done;
  private String addon = "";
  private String status = "";
  private long bytes;
  private long total;

  private LiveProgress(
      Terminal terminal, Function<Installer.Outcome, Optional<String>> outcomeLine) {
    this.terminal = terminal;
    this.outcomeLine = outcomeLine;
    this.display = new Display(terminal, false);
    // A Ctrl-C mid-run must not leave the user's cursor hidden.
    this.restoreCursor = new Thread(this::showCursor, "wowfam-cursor");
    Runtime.getRuntime().addShutdownHook(restoreCursor);
    terminal.puts(Capability.cursor_invisible);
    this.ticker =
        Executors.newSingleThreadScheduledExecutor(r -> {
          var thread = new Thread(r, "wowfam-progress");
          thread.setDaemon(true);
          return thread;
        });
    this.ticking = ticker.scheduleAtFixedRate(this::tick, 0, 100, TimeUnit.MILLISECONDS);
  }

  /**
   * Live progress if stdout is an interactive terminal that can move the cursor, else empty.
   *
   * @param outcomeLine the line a finished addon leaves behind (with ANSI colours), if any
   */
  // System.console() can still be null: without a terminal, and in a native image (see
  // WowfamCommand.newCommandLine).
  @SuppressWarnings("SystemConsoleNull")
  static Optional<LiveProgress> open(
      Map<String, String> env, Function<Installer.Outcome, Optional<String>> outcomeLine) {
    var console = System.console();
    if (console == null
        || !console.isTerminal()
        || "dumb".equals(env.get("TERM"))
        || "0".equals(env.get("WOWFAM_PROGRESS"))) {
      return Optional.empty();
    }
    try {
      var terminal = TerminalBuilder.builder().system(true).dumb(true).build();
      if (terminal.getType().startsWith(Terminal.TYPE_DUMB)
          || terminal.getStringCapability(Capability.cursor_up) == null) {
        terminal.close();
        return Optional.empty();
      }
      return Optional.of(new LiveProgress(terminal, outcomeLine));
    } catch (IOException | RuntimeException e) {
      // No usable terminal (no provider in this build, an odd TERM): plain lines will do.
      return Optional.empty();
    }
  }

  @Override
  public synchronized void log(String line) {
    printAbove(line);
  }

  @Override
  public synchronized void start(int count) {
    this.count = count;
    this.done = 0;
  }

  @Override
  public synchronized void step(String addon, String what) {
    this.addon = addon;
    this.status = what;
    this.bytes = 0;
    this.total = 0;
  }

  @Override
  public synchronized void status(String addon, String line) {
    this.addon = addon;
    // "downloading Questie-….zip (7.8 MB)" reads as "downloading Questie-….zip" next to its bar.
    this.status = line.replaceFirst(" \\([0-9.]+ [KMGTPE]?B\\)$", "");
    this.bytes = 0;
    this.total = 0;
  }

  @Override
  public synchronized void bytes(long done, long total) {
    this.bytes = done;
    this.total = total;
  }

  @Override
  public synchronized void finished(Installer.Outcome outcome) {
    done++;
    addon = "";
    status = "";
    outcomeLine.apply(outcome).ifPresent(this::printAbove);
  }

  @Override
  public synchronized void close() {
    ticking.cancel(false);
    ticker.shutdownNow();
    display.update(List.of(), 0);
    showCursor();
    terminal.flush();
    try {
      Runtime.getRuntime().removeShutdownHook(restoreCursor);
    } catch (IllegalStateException e) {
      // Already shutting down: the hook runs anyway.
    }
    try {
      terminal.close();
    } catch (IOException e) {
      // Nothing left to restore.
    }
  }

  private synchronized void tick() {
    frame = (frame + 1) % SPINNER.length;
    redraw();
  }

  /** A line that stays: the status block is cleared, the line printed, the block drawn below. */
  private void printAbove(String line) {
    display.update(List.of(), 0);
    terminal.writer().println(line);
    redraw();
  }

  private void redraw() {
    var size = terminal.getSize();
    int columns = Math.max(size.getColumns(), 20);
    display.resize(Size.of(columns, size.getRows()));
    var lines = new ArrayList<AttributedString>();
    lines.add(fit(header(), columns));
    if (!addon.isEmpty()) {
      lines.add(fit(current(), columns));
    }
    display.update(lines, 0);
    terminal.flush();
  }

  private AttributedString header() {
    var line = new AttributedStringBuilder();
    line.styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN), SPINNER[frame]);
    line.append(" wowfam");
    if (count > 0) {
      line.append(" ");
      bar(line, (double) done / count);
      line.append(" ").append(Integer.toString(done)).append("/").append(Integer.toString(count));
    }
    line.styled(
        AttributedStyle.DEFAULT.faint(), " · %.1fs".formatted((System.nanoTime() - started) / 1e9));
    return line.toAttributedString();
  }

  private AttributedString current() {
    var line = new AttributedStringBuilder();
    line.append("  ").styled(AttributedStyle.BOLD, addon).append("  ");
    line.styled(AttributedStyle.DEFAULT.faint(), status);
    if (total > 0) {
      line.append("  ");
      bar(line, (double) bytes / total);
      line.append(" ")
          .append(Installer.humanSize(bytes))
          .append("/")
          .append(Installer.humanSize(total));
    } else if (bytes > 0) {
      line.append("  ").append(Installer.humanSize(bytes));
    }
    return line.toAttributedString();
  }

  private static void bar(AttributedStringBuilder line, double fraction) {
    int filled = (int) Math.round(Math.clamp(fraction, 0, 1) * BAR);
    line.styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN), "█".repeat(filled));
    line.styled(AttributedStyle.DEFAULT.faint(), "░".repeat(BAR - filled));
  }

  /** Cut to the width: a wrapped line would throw the redraw off by a row. */
  private static AttributedString fit(AttributedString line, int columns) {
    return line.columnLength() < columns ? line : line.columnSubSequence(0, columns - 1);
  }

  private void showCursor() {
    terminal.puts(Capability.cursor_visible);
    terminal.flush();
  }
}
