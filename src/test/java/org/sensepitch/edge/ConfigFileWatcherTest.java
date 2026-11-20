package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.TestingParameters.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/// @author Jens Wilke
// alone: captures the log of all threads, the watcher logs from its own
@Isolated
class ConfigFileWatcherTest {

  @TempDir Path dir;

  final AtomicInteger reloads = new AtomicInteger();

  ConfigFileWatcher watcher(Path file) {
    return new ConfigFileWatcher(file);
  }

  /// Replaces the file by a rename, like editors and Kubernetes do
  void replace(Path file, String content) throws IOException {
    Path tmp = dir.resolve("tmp");
    Files.writeString(tmp, content);
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  @RegisterExtension LogCapture logs = LogCapture.ofAllThreads();

  final List<String> loaded = new CopyOnWriteArrayList<>();

  ReloadResult load(String content) {
    loaded.add(content);
    reloads.incrementAndGet();
    return ReloadResult.APPLIED;
  }

  static String complete(String yaml) {
    return yaml + ConfigFileWatcher.END_MARKER + "\n";
  }

  @Test
  void reloadOnlyWhenContentChanged() throws Exception {
    Path file = dir.resolve("config.yaml");
    Files.writeString(file, complete("a: 1\n"));
    ConfigFileWatcher watcher = watcher(file);
    watcher.start(this::load, Map::of);
    assertThat(reloads).hasValue(0);
    replace(file, complete("a: 2\n"));
    waitUntilReloads(1);
    assertThat(loaded).containsExactly(complete("a: 2\n"));
    replace(file, complete("a: 2\n"));
    Files.writeString(dir.resolve("unrelated.txt"), "x");
    watcher.checkForChange();
    assertThat(reloads).hasValue(1);
  }

  @Test
  void changeBeforeStartIsPickedUp() throws IOException {
    Path file = dir.resolve("config.yaml");
    Files.writeString(file, complete("a: 1\n"));
    ConfigFileWatcher watcher = watcher(file);
    Files.writeString(file, complete("a: 2\n"));
    watcher.start(this::load, Map::of);
    assertThat(reloads).hasValue(1);
  }

  @Test
  void failingReloadIsLoggedAndRetriedOnNextChange() throws Exception {
    Path file = dir.resolve("config.yaml");
    Files.writeString(file, complete("a: 1\n"));
    ConfigFileWatcher watcher = watcher(file);
    watcher.start(
        content -> {
          reloads.incrementAndGet();
          throw new IllegalArgumentException("broken");
        },
        Map::of);
    replace(file, complete("a: 2\n"));
    waitUntilReloads(1);
    replace(file, complete("a: 3\n"));
    waitUntilReloads(2);
  }

  @Test
  void incompleteFileIsNotReloadedUntilComplete() throws IOException {
    Path file = dir.resolve("config.yaml");
    Files.writeString(file, complete("a: 1\n"));
    ConfigFileWatcher watcher = watcher(file);
    watcher.start(this::load, Map::of);
    // content like a file that is written in place and read in the middle. Replaced atomically:
    // an in-place write exposes the truncated, empty file to the watcher thread, which is
    // different incomplete content and logged once more
    replace(file, "a: 2\n");
    watcher.checkForChange();
    watcher.checkForChange();
    assertThat(watcher.forceReload().message()).contains("without #END");
    assertThat(reloads).hasValue(0);
    assertThat(logs.messages(ConfigFileWatcher.class))
        .filteredOn(m -> m.startsWith("Configuration file not reloaded"))
        .hasSize(2);
    Files.writeString(file, complete("a: 2\n"));
    watcher.checkForChange();
    assertThat(loaded).containsExactly(complete("a: 2\n"));
  }

  @Test
  void forceReloadsUnchangedContent() throws IOException {
    Path file = dir.resolve("config.yaml");
    Files.writeString(file, complete("a: 1\n"));
    ConfigFileWatcher watcher = watcher(file);
    watcher.start(this::load, Map::of);
    assertThat(watcher.forceReload()).isEqualTo(ReloadResult.APPLIED);
    assertThat(loaded).containsExactly(complete("a: 1\n"));
  }

  /// The files the last applied configuration read, as [Proxy#referencedFiles()] returns them
  volatile Map<Path, ConfigFiles.Fingerprint> referenced = Map.of();

  /// Reads the file like a configuration referencing it, then applies the content
  ReloadResult loadReading(Path referencedFile, String content) {
    referenced = read(referencedFile);
    return load(content);
  }

  static Map<Path, ConfigFiles.Fingerprint> read(Path file) {
    ConfigFiles files = new ConfigFiles();
    try {
      files.read(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return files.snapshot().files();
  }

  /// Configuration file and the secret it references, the configuration read the secret
  ConfigFileWatcher startWithSecret(Path file, Path secret) throws IOException {
    Files.writeString(file, complete("a: 1\n"));
    Files.writeString(secret, "old-secret");
    ConfigFileWatcher watcher = watcher(file);
    referenced = read(secret);
    watcher.start(content -> loadReading(secret, content), () -> referenced);
    assertThat(reloads).hasValue(0);
    return watcher;
  }

  @Test
  void referencedFileChangeReloadsUnchangedConfigOnce() throws Exception {
    Path secret = dir.resolve("secret.txt");
    ConfigFileWatcher watcher = startWithSecret(dir.resolve("config.yaml"), secret);
    replace(secret, "new-secret");
    // the watcher thread may have reloaded already, then this finds no change
    watcher.checkForChange();
    watcher.checkForChange();
    assertThat(loaded).containsExactly(complete("a: 1\n"));
    assertThat(referenced).isEqualTo(read(secret));
  }

  @Test
  void referencedFileInOtherDirectoryIsWatched() throws Exception {
    Path secret = Files.createDirectory(dir.resolve("secrets")).resolve("token");
    startWithSecret(dir.resolve("config.yaml"), secret);
    Files.move(
        Files.writeString(dir.resolve("secrets/tmp"), "new-secret"),
        secret,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
    waitUntilReloads(1);
  }

  /// A rejected reload, e.g. of a renewed certificate whose key is not written yet, is tried
  /// again when the file changes again, not on every event
  @Test
  void rejectedReloadIsRetriedOnTheNextChangeOfAReferencedFile() throws Exception {
    Path file = dir.resolve("config.yaml");
    Path secret = dir.resolve("secret.txt");
    Files.writeString(file, complete("a: 1\n"));
    Files.writeString(secret, "old-secret");
    ConfigFileWatcher watcher = watcher(file);
    watcher.start(
        content -> {
          reloads.incrementAndGet();
          return ReloadResult.rejected("key does not match");
        },
        () -> read(secret));
    replace(secret, "half-written");
    watcher.checkForChange();
    watcher.checkForChange();
    assertThat(reloads).hasValue(1);
    replace(secret, "complete");
    watcher.checkForChange();
    assertThat(reloads).hasValue(2);
  }

  /// Stands in for the proxy: the configuration reads the secret file, a missing file rejects
  /// it. Reports the files like [Proxy#referencedFiles()], after a rejection with those of the
  /// rejected configuration.
  static class FakeProxy {
    final Path secret;
    final AtomicInteger attempts = new AtomicInteger();
    final AtomicInteger applied = new AtomicInteger();
    volatile Map<Path, ConfigFiles.Fingerprint> active = Map.of();
    volatile Map<Path, ConfigFiles.Fingerprint> candidate = Map.of();

    FakeProxy(Path secret) {
      this.secret = secret;
    }

    ReloadResult reload(String content) {
      attempts.incrementAndGet();
      ConfigFiles files = new ConfigFiles();
      try {
        files.read(secret);
      } catch (IOException e) {
        candidate = files.snapshot().files();
        return ReloadResult.rejected("missing secret");
      }
      active = files.snapshot().files();
      candidate = Map.of();
      applied.incrementAndGet();
      return ReloadResult.APPLIED;
    }

    Map<Path, ConfigFiles.Fingerprint> referencedFiles() {
      Map<Path, ConfigFiles.Fingerprint> files = new java.util.HashMap<>(active);
      files.putAll(candidate);
      return files;
    }
  }

  /// A configuration rejected because a file it needs is missing is tried again when the file
  /// appears, without touching the configuration file again
  @Test
  void creatingTheMissingFileOfARejectedConfigurationRetriesTheReload() throws Exception {
    Path file = dir.resolve("config.yaml");
    Files.writeString(file, complete("a: 1\n"));
    Path secret = Files.createDirectory(dir.resolve("secrets")).resolve("token");
    FakeProxy proxy = new FakeProxy(secret);
    ConfigFileWatcher watcher = watcher(file);
    watcher.start(proxy::reload, proxy::referencedFiles);
    replace(file, complete("a: 2\n"));
    watcher.checkForChange();
    watcher.checkForChange();
    assertThat(proxy.attempts).as("rejected once, not on every event").hasValue(1);
    assertThat(proxy.applied).hasValue(0);
    Files.move(
        Files.writeString(dir.resolve("secrets/tmp"), "secret"),
        secret,
        StandardCopyOption.ATOMIC_MOVE);
    await("reload applied", () -> proxy.applied.get() >= 1);
    assertThat(proxy.attempts).hasValue(2);
  }

  /// A deleted directory loses its registration, a reload attempt registers it again
  @Test
  void deletedAndRecreatedDirectoryIsWatchedAgain() throws Exception {
    Path secrets = Files.createDirectory(dir.resolve("secrets"));
    Path secret = secrets.resolve("token");
    ConfigFileWatcher watcher = startWithSecret(dir.resolve("config.yaml"), secret);
    Files.delete(secret);
    Files.delete(secrets);
    Files.createDirectory(secrets);
    Files.writeString(secret, "recreated");
    assertThat(watcher.forceReload()).isEqualTo(ReloadResult.APPLIED);
    // the events of the parent directory may have reloaded already, a later check finds no change
    int before = reloads.get();
    Files.move(
        Files.writeString(secrets.resolve("tmp"), "rotated"),
        secret,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
    waitUntilReloads(before + 1);
  }

  /// The directory of a file no longer referenced is unregistered, the configuration's is kept
  @Test
  void directoryNoLongerNeededIsUnregistered() throws Exception {
    Path first = Files.createDirectory(dir.resolve("first")).resolve("token");
    Path second = Files.createDirectory(dir.resolve("second")).resolve("token");
    Files.writeString(first, "one");
    Files.writeString(second, "two");
    Path file = dir.resolve("config.yaml");
    Files.writeString(file, complete("a: 1\n"));
    ConfigFileWatcher watcher = watcher(file);
    referenced = read(first);
    watcher.start(content -> loadReading(second, content), () -> referenced);
    assertThat(watcher.watchedDirectories())
        .containsExactlyInAnyOrder(dir.toAbsolutePath(), first.getParent().toAbsolutePath());
    assertThat(watcher.forceReload()).isEqualTo(ReloadResult.APPLIED);
    assertThat(watcher.watchedDirectories())
        .containsExactlyInAnyOrder(dir.toAbsolutePath(), second.getParent().toAbsolutePath());
  }

  @Test
  void deletedReferencedFileDoesNotReload() throws Exception {
    Path secret = dir.resolve("secret.txt");
    ConfigFileWatcher watcher = startWithSecret(dir.resolve("config.yaml"), secret);
    Files.delete(secret);
    watcher.checkForChange();
    assertThat(reloads).hasValue(0);
  }

  @Test
  void referencedFileInMissingDirectoryIsLoggedAndNotWatched() throws Exception {
    Path file = dir.resolve("config.yaml");
    Files.writeString(file, complete("a: 1\n"));
    ConfigFileWatcher watcher = watcher(file);
    Path gone = dir.resolve("gone/secret.txt");
    watcher.start(
        this::load,
        () -> Map.of(gone, new ConfigFiles.Fingerprint(FileTime.fromMillis(0), 0, null)));
    assertThat(logs.messages(ConfigFileWatcher.class))
        .anyMatch(m -> m.startsWith("Cannot watch directory of referenced file " + gone));
    watcher.checkForChange();
    assertThat(reloads).hasValue(0);
  }

  @Test
  void configMapSymlinkSwapIsDetected() throws Exception {
    Path first = Files.createDirectory(dir.resolve("..data_v1"));
    Files.writeString(first.resolve("config.yaml"), complete("a: 1\n"));
    Path current = dir.resolve("..data");
    Files.createSymbolicLink(current, Path.of("..data_v1"));
    Path file = dir.resolve("config.yaml");
    Files.createSymbolicLink(file, Path.of("..data/config.yaml"));
    ConfigFileWatcher watcher = watcher(file);
    watcher.start(this::load, Map::of);
    assertThat(reloads).hasValue(0);
    Path second = Files.createDirectory(dir.resolve("..data_v2"));
    Files.writeString(second.resolve("config.yaml"), complete("a: 2\n"));
    Path newLink = dir.resolve("..data.new");
    Files.createSymbolicLink(newLink, Path.of("..data_v2"));
    Files.move(
        newLink, current, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    waitUntilReloads(1);
    assertThat(loaded).containsExactly(complete("a: 2\n"));
  }

  @Test
  void missingConfigFileIsRejected() throws Exception {
    Path missing = dir.resolve("missing.yaml");
    ConfigFileWatcher watcher = watcher(missing);
    watcher.start(this::load, Map::of);
    assertThat(watcher.forceReload().message()).contains("Cannot read configuration file");
    assertThat(reloads).hasValue(0);
  }

  @Test
  void endMarker() {
    assertThat(ConfigFileWatcher.hasEndMarker("a: 1\n#END")).isTrue();
    assertThat(ConfigFileWatcher.hasEndMarker("a: 1\n#END\n\n  \n")).isTrue();
    assertThat(ConfigFileWatcher.hasEndMarker("a: 1\r\n#END\r\n")).isTrue();
    assertThat(ConfigFileWatcher.hasEndMarker("a: 1\n")).isFalse();
    assertThat(ConfigFileWatcher.hasEndMarker("#END\na: 1\n")).isFalse();
    assertThat(ConfigFileWatcher.hasEndMarker("a: 1 #END\n")).isFalse();
    assertThat(ConfigFileWatcher.hasEndMarker("")).isFalse();
  }

  /// Waits for the watch service, including platforms where it polls for changes.
  void waitUntilReloads(int expected) throws Exception {
    await("configuration reloaded " + expected + " times", () -> reloads.get() >= expected);
    assertThat(reloads).hasValue(expected);
  }
}
