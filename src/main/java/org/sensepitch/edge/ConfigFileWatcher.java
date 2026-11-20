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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/// Watches the configuration file and the files the active configuration read, and runs the
/// reload action when one of them changed.
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
/// The referenced files, e.g. TLS keys and certificates, secrets and IP databases, come from the
/// proxy after each reload attempt, see [Proxy#referencedFiles()], with the fingerprints taken
/// when the configuration read them. A changed fingerprint triggers a reload of the unchanged
/// configuration, e.g. for a certificate renewed by cert-manager. After a rejected reload they
/// include the files the rejected configuration tried to read, missing ones too, so fixing or
/// creating such a file retries the reload without touching the configuration file. An active
/// file that goes missing is ignored, it may be in the middle of a replacement. Like the content
/// of the configuration file, the fingerprints count as known once a reload was tried: a rejected
/// reload, e.g. of a certificate whose key is not written yet, is tried again when a file changes
/// again, not on every event.
///
/// Directories no longer needed are unregistered. A directory whose registration became invalid,
/// e.g. because it was deleted, is registered again on the next reload attempt.
///
/// A private lock guards the reload action, the known state and the watch registrations, and
/// serializes file reads and reload callbacks. The initial check from `start` reenters the same
/// lock.
///
/// @author Jens Wilke
public class ConfigFileWatcher {

  private static final ProxyLogger LOG = ProxyLogger.get(ConfigFileWatcher.class);

  /// Last non-empty line of a complete configuration file, a YAML comment
  public static final String END_MARKER = "#END";

  private final Object lock = new Object();

  private final Path file;

  // guarded by lock after construction
  private Function<String, ReloadResult> reloadAction;
  private Supplier<Map<Path, ConfigFiles.Fingerprint>> referencedFilesSource;
  private WatchService watchService;
  private byte[] lastHash;

  /// Hash of the last content rejected as incomplete, to log it only once
  private byte[] incompleteHash;

  /// The referenced files with their known fingerprints
  private Map<Path, ConfigFiles.Fingerprint> referencedFiles = Map.of();

  /// Directory to its registration with the watch service; an invalid key, e.g. of a deleted
  /// directory, is removed, so the next reload attempt registers the directory again
  private final Map<Path, WatchKey> watchKeys = new HashMap<>();

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
  /// @param referencedFiles the files the active configuration read, with their fingerprints when
  ///   they were read, see [Proxy#referencedFiles()]. Asked at the start and after each applied
  ///   reload.
  public void start(
      Function<String, ReloadResult> reloadAction,
      Supplier<Map<Path, ConfigFiles.Fingerprint>> referencedFiles)
      throws IOException {
    synchronized (lock) {
      this.reloadAction = reloadAction;
      this.referencedFilesSource = referencedFiles;
      watchService = file.getFileSystem().newWatchService();
      watchKeys.put(
          file.getParent(),
          file.getParent().register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE));
      takeReferencedFiles(true);
      WatchService service = watchService;
      Thread.ofPlatform().name("config-file-watcher").daemon().start(() -> watch(service));
      LOG.info(
          "Watching configuration file for changes, reload requires "
              + END_MARKER
              + " as last line: "
              + file);
      checkForChange();
    }
  }

  private void watch(WatchService watchService) {
    try {
      while (true) {
        WatchKey key = watchService.take();
        key.pollEvents();
        if (!key.reset()) {
          synchronized (lock) {
            watchKeys.values().remove(key);
          }
        }
        checkForChange();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /// Reloads if the configuration file or a referenced file changed since the last reload and
  /// the configuration is complete.
  void checkForChange() {
    synchronized (lock) {
      reload(false);
    }
  }

  /// Reloads also if nothing changed, which re-reads the referenced files, e.g. via
  /// [AdminServer]. The content still needs the end marker. Call only after [#start].
  public ReloadResult forceReload() {
    synchronized (lock) {
      return reload(true);
    }
  }

  private ReloadResult reload(boolean force) {
    byte[] content = readOrNull();
    if (content == null) {
      return ReloadResult.rejected("Cannot read configuration file: " + file);
    }
    byte[] hash = hash(content);
    boolean configChanged = !Arrays.equals(hash, lastHash) && !Arrays.equals(hash, incompleteHash);
    Map<Path, ConfigFiles.Fingerprint> current = currentFingerprints();
    if (!force && !configChanged && current.equals(referencedFiles)) {
      return ReloadResult.rejected("Configuration file unchanged");
    }
    referencedFiles = current;
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
    ReloadResult result;
    try {
      result = reloadAction.apply(text);
    } catch (RuntimeException e) {
      LOG.error("Configuration reload failed, keeping the active configuration: " + e, e);
      result = ReloadResult.rejected("Configuration reload failed: " + e);
    }
    takeReferencedFiles(result.applied());
    return result;
  }

  /// The referenced files with their fingerprints now, a missing file keeps its known fingerprint,
  /// for the change check in [#reload(boolean)]
  private Map<Path, ConfigFiles.Fingerprint> currentFingerprints() {
    Map<Path, ConfigFiles.Fingerprint> current = new HashMap<>();
    referencedFiles.forEach(
        (path, known) -> {
          ConfigFiles.Fingerprint now = ConfigFiles.Fingerprint.of(path);
          current.put(path, now == ConfigFiles.Fingerprint.ABSENT ? known : now);
        });
    return current;
  }

  /// Takes the referenced files from the proxy as known state and watches their directories,
  /// after the start and after each reload attempt. The proxy reports also the files of a
  /// rejected configuration, so a fix of such a file retries the reload.
  ///
  /// @param applied after a rejected attempt, the fingerprints seen when it was tried win for the
  ///   files known before, otherwise the old fingerprints of the active configuration would
  ///   trigger the same attempt on every event
  private void takeReferencedFiles(boolean applied) {
    Map<Path, ConfigFiles.Fingerprint> taken = referencedFilesSource.get();
    if (!applied) {
      Map<Path, ConfigFiles.Fingerprint> merged = new HashMap<>(taken);
      merged.putAll(referencedFiles);
      taken = merged;
    }
    referencedFiles = taken;
    Set<Path> needed = new HashSet<>();
    needed.add(file.getParent());
    for (Path referenced : referencedFiles.keySet()) {
      Path directory = referenced.getParent();
      needed.add(directory);
      WatchKey key = watchKeys.get(directory);
      // invalid, e.g. deleted and created again, before the watch thread removed the key
      if (key == null || !key.isValid()) {
        try {
          watchKeys.put(
              directory,
              directory.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE));
        } catch (IOException e) {
          // e.g. the directory does not exist (yet); the next reload attempt tries again
          LOG.warn("Cannot watch directory of referenced file " + referenced + ": " + e);
        }
      }
    }
    watchKeys
        .entrySet()
        .removeIf(
            entry -> {
              if (needed.contains(entry.getKey())) {
                return false;
              }
              entry.getValue().cancel();
              return true;
            });
  }

  /// The directories registered with the watch service, for tests
  Set<Path> watchedDirectories() {
    synchronized (lock) {
      return Set.copyOf(watchKeys.keySet());
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
