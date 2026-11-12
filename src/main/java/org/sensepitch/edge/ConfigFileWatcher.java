package org.sensepitch.edge;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.function.Function;

/// Watches the configuration file and runs the reload action when its content changed.
///
/// A reload requires the end marker [#END_MARKER] as the last non-empty line, like APISIX does.
/// This detects a file that is read while it is still being written: a truncated YAML document
/// is often still valid, but misses sites or routes. A file without the marker is not reloaded,
/// so adding the marker enables live reload. Starting the proxy does not require it.
///
/// The parent directory is watched, not the file, because editors and Kubernetes ConfigMap
/// volumes replace the file by a rename or a symlink swap. The check runs right after an event,
/// without waiting for further events. A reload only happens if the content hash differs from
/// the last loaded content, so unrelated files in the directory, a touch without changes, or the
/// several events of one replacement trigger at most one reload.
///
/// @author Jens Wilke
public class ConfigFileWatcher {

  private static final ProxyLogger LOG = ProxyLogger.get(ConfigFileWatcher.class);

  /// Last non-empty line of a complete configuration file, a YAML comment
  public static final String END_MARKER = "#END";

  private final Path file;
  private Function<String, ReloadResult> reloadAction;
  private byte[] lastHash;

  /// Hash of the last content rejected as incomplete, to log it only once
  private byte[] incompleteHash;

  /// Reads the current content as the known state, so construct this before loading the
  /// configuration for the first time. A change in between triggers a reload after [#start].
  public ConfigFileWatcher(Path file) {
    this.file = file.toAbsolutePath();
    byte[] content = readOrNull();
    lastHash = content != null ? hash(content) : null;
  }

  /// Starts watching in a daemon thread and checks for a change since construction.
  ///
  /// @param reloadAction parses the passed file content and activates the configuration
  public synchronized void start(Function<String, ReloadResult> reloadAction) throws IOException {
    this.reloadAction = reloadAction;
    WatchService watchService = file.getFileSystem().newWatchService();
    file.getParent().register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
    Thread.ofPlatform().name("config-file-watcher").daemon().start(() -> watch(watchService));
    LOG.info(
        "Watching configuration file for changes, reload requires "
            + END_MARKER
            + " as last line: "
            + file);
    checkForChange();
  }

  private void watch(WatchService watchService) {
    try {
      while (true) {
        WatchKey key = watchService.take();
        key.pollEvents();
        key.reset();
        checkForChange();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /// Reloads if the file content changed since the last reload and is complete.
  synchronized void checkForChange() {
    reload(false);
  }

  /// Reloads also if the content is unchanged, which re-reads the certificate files, e.g. via
  /// [AdminServer]. The content still needs the end marker. Call only after [#start].
  public synchronized ReloadResult forceReload() {
    return reload(true);
  }

  private ReloadResult reload(boolean force) {
    byte[] content = readOrNull();
    if (content == null) {
      return ReloadResult.rejected("Cannot read configuration file: " + file);
    }
    byte[] hash = hash(content);
    if (!force && (Arrays.equals(hash, lastHash) || Arrays.equals(hash, incompleteHash))) {
      return ReloadResult.rejected("Configuration file unchanged");
    }
    String text = new String(content, StandardCharsets.UTF_8);
    if (!hasEndMarker(text)) {
      incompleteHash = hash;
      String reason =
          "Configuration file not reloaded, incomplete or without "
              + END_MARKER
              + " as last line: "
              + file;
      LOG.info(reason);
      return ReloadResult.rejected(reason);
    }
    lastHash = hash;
    try {
      return reloadAction.apply(text);
    } catch (RuntimeException e) {
      LOG.error("Configuration reload failed, keeping the active configuration: " + e, e);
      return ReloadResult.rejected("Configuration reload failed: " + e);
    }
  }

  /// @return `true` if the last non-empty line is the [#END_MARKER]
  static boolean hasEndMarker(String content) {
    String[] lines = content.split("\n");
    for (int i = lines.length - 1; i >= 0; i--) {
      String line = lines[i].strip();
      if (!line.isEmpty()) {
        return line.equals(END_MARKER);
      }
    }
    return false;
  }

  /// @return the content, or `null` if the file cannot be read, e.g. in the middle of a
  ///   replacement. A later event triggers a check again.
  private byte[] readOrNull() {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      LOG.info("Cannot read configuration file: " + e);
      return null;
    }
  }

  private static byte[] hash(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
