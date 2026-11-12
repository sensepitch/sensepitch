package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/// @author Jens Wilke
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

  @RegisterExtension LogCapture logs = new LogCapture();

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
  void reloadOnlyWhenContentChanged() throws IOException {
    Path file = dir.resolve("config.yaml");
    Files.writeString(file, complete("a: 1\n"));
    ConfigFileWatcher watcher = watcher(file);
    watcher.start(this::load);
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
    watcher.start(this::load);
    assertThat(reloads).hasValue(1);
  }

  @Test
  void failingReloadIsLoggedAndRetriedOnNextChange() throws IOException {
    Path file = dir.resolve("config.yaml");
    Files.writeString(file, complete("a: 1\n"));
    ConfigFileWatcher watcher = watcher(file);
    watcher.start(
        content -> {
          reloads.incrementAndGet();
          throw new IllegalArgumentException("broken");
        });
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
    watcher.start(this::load);
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
    watcher.start(this::load);
    assertThat(watcher.forceReload()).isEqualTo(ReloadResult.APPLIED);
    assertThat(loaded).containsExactly(complete("a: 1\n"));
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

  /// The watch service is polling on some platforms, so allow a generous time
  void waitUntilReloads(int expected) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (reloads.get() < expected && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    assertThat(reloads).hasValue(expected);
  }
}
