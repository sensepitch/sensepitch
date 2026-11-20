package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Reading and recording the files a configuration references, see [ConfigFiles]
///
/// @author Jens Wilke
class ConfigFilesTest {

  @TempDir Path dir;

  final ConfigFiles files = new ConfigFiles();

  /// Replaces the file by a rename, like editors and Kubernetes do
  void replace(Path file, String content) throws IOException {
    Path tmp = dir.resolve("tmp");
    Files.writeString(tmp, content);
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  @Test
  void readFileIsRecordedWithItsFingerprint() throws IOException {
    Path file = Files.writeString(dir.resolve("x.pem"), "x");
    assertThat(files.read(file)).containsExactly('x');
    ConfigFiles.Snapshot snapshot = files.snapshot();
    assertThat(snapshot.files()).containsOnlyKeys(file.toAbsolutePath());
    assertThat(snapshot.unchanged()).isTrue();
  }

  @Test
  void openFileIsRecorded() throws IOException {
    Path file = Files.writeString(dir.resolve("x.pem"), "x");
    try (InputStream in = files.open(file.toString())) {
      assertThat(in.read()).isEqualTo('x');
    }
    assertThat(files.snapshot().files()).containsOnlyKeys(file.toAbsolutePath());
  }

  @Test
  void classpathResourceIsNotRecorded() throws IOException {
    try (InputStream in = files.open("classpath:ssl/test.crt")) {
      assertThat(in.read()).isNotNegative();
    }
    try (InputStream in = files.open("classpath:/ssl/test.crt")) {
      assertThat(in.read()).isNotNegative();
    }
    assertThat(files.snapshot().files()).isEmpty();
  }

  /// A missing file is recorded as absent, so the watcher retries a rejected configuration when
  /// the file appears; a class path resource is not recorded
  @Test
  void missingLocationIsRejectedAndRecordedAsAbsent() throws IOException {
    assertThatThrownBy(() -> files.open("classpath:ssl/missing.crt"))
        .isInstanceOf(FileNotFoundException.class)
        .hasMessageContaining("ssl/missing.crt");
    Path crt = dir.resolve("missing.crt");
    Path mmdb = dir.resolve("missing.mmdb");
    assertThatThrownBy(() -> files.open(crt.toString())).isInstanceOf(NoSuchFileException.class);
    assertThatThrownBy(() -> files.record(mmdb)).isInstanceOf(NoSuchFileException.class);
    ConfigFiles.Snapshot snapshot = files.snapshot();
    assertThat(snapshot.files())
        .containsOnly(
            Map.entry(crt.toAbsolutePath(), ConfigFiles.Fingerprint.ABSENT),
            Map.entry(mmdb.toAbsolutePath(), ConfigFiles.Fingerprint.ABSENT));
    assertThat(snapshot.unchanged()).isTrue();
    Files.writeString(crt, "x");
    assertThat(snapshot.unchanged()).isFalse();
  }

  /// If a file changes between two reads of one build, the first fingerprint is kept, so the
  /// watcher sees the change
  @Test
  void firstFingerprintIsKept() throws IOException {
    Path file = Files.writeString(dir.resolve("x.pem"), "x");
    files.record(file);
    replace(file, "changed");
    files.read(file);
    assertThat(files.snapshot().unchanged()).isFalse();
  }

  @Test
  void rewriteChangesTheFingerprint() throws IOException {
    Path file = Files.writeString(dir.resolve("x.pem"), "x");
    files.record(file);
    Files.writeString(file, "longer");
    assertThat(files.snapshot().unchanged()).isFalse();
  }

  @Test
  void deletedFileIsChanged() throws IOException {
    Path file = Files.writeString(dir.resolve("x.pem"), "x");
    files.record(file);
    Files.delete(file);
    assertThat(files.snapshot().unchanged()).isFalse();
  }

  /// A Kubernetes ConfigMap or Secret volume swaps the `..data` link to a new directory, the file
  /// path stays the same
  @Test
  void symlinkSwapChangesTheFingerprint() throws IOException {
    Path first = Files.createDirectory(dir.resolve("..data_v1"));
    Files.writeString(first.resolve("token"), "same");
    Path data = Files.createSymbolicLink(dir.resolve("..data"), Path.of("..data_v1"));
    Path token = Files.createSymbolicLink(dir.resolve("token"), Path.of("..data/token"));
    files.read(token);
    Path second = Files.createDirectory(dir.resolve("..data_v2"));
    Files.writeString(second.resolve("token"), "same");
    Path newLink = Files.createSymbolicLink(dir.resolve("..data.new"), Path.of("..data_v2"));
    Files.move(newLink, data, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    assertThat(files.snapshot().unchanged()).isFalse();
  }

  /// Files of a component taken over from the previous build are added, a file this build read
  /// itself keeps its own fingerprint
  @Test
  void recordAllAddsTakenOverFiles() throws IOException {
    Path file = Files.writeString(dir.resolve("x.pem"), "x");
    Path absolute = file.toAbsolutePath();
    files.record(file);
    ConfigFiles.Snapshot before = files.snapshot();
    ConfigFiles taken = new ConfigFiles();
    taken.recordAll(before);
    assertThat(taken.snapshot()).isEqualTo(before);
    replace(file, "changed");
    ConfigFiles next = new ConfigFiles();
    next.read(file);
    next.recordAll(before);
    assertThat(next.snapshot().files().get(absolute))
        .isEqualTo(ConfigFiles.Fingerprint.of(absolute))
        .isNotEqualTo(before.files().get(absolute));
  }

  @Test
  void inlineSecretReadsNoFile() {
    assertThat(files.secret(SecretConfig.builder().value("inline").build())).isEqualTo("inline");
    assertThat(files.snapshot().files()).isEmpty();
  }

  String secretFile(String content) throws IOException {
    Path file = Files.writeString(dir.resolve("token"), content);
    return files.secret(SecretConfig.builder().file(file.toString()).build());
  }

  /// One final line break, as `echo` or an editor adds it, is removed
  @Test
  void secretFileIsReadWithoutTheFinalLineBreakAndRecorded() throws IOException {
    assertThat(secretFile("top-secret\n")).isEqualTo("top-secret");
    assertThat(files.snapshot().files()).containsOnlyKeys(dir.resolve("token").toAbsolutePath());
    assertThat(secretFile("top-secret\r\n")).isEqualTo("top-secret");
    assertThat(secretFile("top-secret")).isEqualTo("top-secret");
  }

  /// Other characters are kept, so a secret moved from inline to a file keeps its value
  @Test
  void secretFileKeepsOtherWhitespace() throws IOException {
    assertThat(secretFile("top secret \t\n")).isEqualTo("top secret \t");
    assertThat(secretFile("top-secret\n\n")).isEqualTo("top-secret\n");
  }

  /// A broken rotation is rejected, so the last working secret stays active
  @Test
  void emptySecretFileIsRejected() {
    for (String content : new String[] {"", "\n", "\r\n", "  \n"}) {
      assertThatThrownBy(() -> secretFile(content))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Secret file is empty: " + dir.resolve("token"));
    }
  }

  @Test
  void missingSecretFileIsRejected() {
    String missing = dir.resolve("missing").toString();
    assertThatThrownBy(() -> files.secret(SecretConfig.builder().file(missing).build()))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Cannot read secret file: " + missing);
  }

  // secret digests, for the config id

  static List<String> digests(SecretConfig... secrets) {
    ConfigFiles files = new ConfigFiles();
    for (SecretConfig secret : secrets) {
      files.secret(secret);
    }
    return files.snapshot().secretDigests();
  }

  static SecretConfig value(String value) {
    return SecretConfig.builder().value(value).build();
  }

  @Test
  void digestsInTheOrderOfResolution() {
    assertThat(digests()).isEmpty();
    assertThat(digests(value("a"), value("b")))
        .containsExactly(EdgeState.sha256Hex("a"), EdgeState.sha256Hex("b"));
  }

  @Test
  void secretFileCountsLikeTheInlineValue() throws IOException {
    Path token = Files.writeString(dir.resolve("token"), "top-secret\n");
    SecretConfig file = SecretConfig.builder().file(token.toString()).build();
    assertThat(digests(file)).isEqualTo(digests(value("top-secret")));
    replace(token, "rotated");
    assertThat(digests(file)).isEqualTo(digests(value("rotated")));
  }

  /// A component kept over a reload, e.g. a request log destination with a secret header, does
  /// not read its secret again, its snapshot carries the digest; the snapshot holds no secret
  @Test
  void takenOverSnapshotCarriesItsDigests() {
    files.secret(value("top-secret"));
    ConfigFiles.Snapshot kept = files.snapshot();
    assertThat(kept.secretDigests()).singleElement().asString().doesNotContain("top-secret");
    ConfigFiles next = new ConfigFiles();
    next.secret(value("other"));
    next.recordAll(kept);
    assertThat(next.snapshot().secretDigests())
        .containsExactly(EdgeState.sha256Hex("other"), EdgeState.sha256Hex("top-secret"));
  }
}
