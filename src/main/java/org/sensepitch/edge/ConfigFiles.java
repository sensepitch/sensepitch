package org.sensepitch.edge;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The files a configuration references, read while the configuration is applied: TLS keys and
/// certificates, secrets, response pages and the IP databases. All reads of such files go through
/// here, a file a library opens itself, e.g. an IP database, is announced with [#record(Path)].
///
/// Each file is recorded with its [Fingerprint] taken before it is read, so a change during or
/// after the read is visible later. [ConfigFileWatcher] watches the recorded files and reloads
/// when one changes, a component kept over a reload because its configuration is unchanged checks
/// [Snapshot#unchanged()] on its files, see [EdgeState] and [ReloadableRequestLogger].
///
/// The secrets resolved by [#secret(SecretConfig)] are kept as digests, in the order of
/// resolution, for the config id, see [EdgeState#configId(ProxyConfig, List)]. The order follows
/// the configuration, sites first, then the request log destinations.
///
/// One instance collects the files of one build of the configuration, on one thread. Locations
/// with a `classpath:` prefix are resources inside the jar and are not recorded. Relative paths
/// resolve against the working directory.
///
/// @author Jens Wilke
public final class ConfigFiles {

  /// Prefix of a location inside the jar
  static final String CLASSPATH_PREFIX = "classpath:";

  /// Line breaks removed once from the end of a secret file
  private static final String LF = "\n";

  private static final String CRLF = "\r\n";

  /// Absolute path to its fingerprint, the first one recorded wins: if the file changed between
  /// two reads of the same build, the older fingerprint lets the watcher see the change
  private final Map<Path, Fingerprint> files = new LinkedHashMap<>();

  /// SHA-256 of each resolved secret in the order of resolution, for the config id
  private final List<String> secretDigests = new ArrayList<>();

  /// The files and secret digests of a build or of one component of it, see [#snapshot()]
  ///
  /// @param files absolute path to the fingerprint taken when the file was read
  /// @param secretDigests SHA-256 of each resolved secret, never the secret itself
  public record Snapshot(Map<Path, Fingerprint> files, List<String> secretDigests) {

    /// Nothing read
    static final Snapshot EMPTY = new Snapshot(Map.of(), List.of());

    /// Whether a component built from this snapshot can be kept: `true` if every file still has
    /// its fingerprint
    boolean unchanged() {
      for (Map.Entry<Path, Fingerprint> entry : files.entrySet()) {
        if (!Objects.equals(Fingerprint.of(entry.getKey()), entry.getValue())) {
          return false;
        }
      }
      return true;
    }
  }

  /// Last modification time, size and file key (the inode on Linux) of a file, following symbolic
  /// links. The file key changes when a Kubernetes ConfigMap or Secret volume swaps its `..data`
  /// link, the time and size when a file is rewritten in place. Cheaper than hashing the content,
  /// an IP database has tens of megabytes.
  ///
  /// @param fileKey `null` if the file system has none
  public record Fingerprint(FileTime lastModified, long size, Object fileKey) {

    /// A file that does not exist or cannot be read, e.g. in the middle of a replacement. A
    /// missing file is recorded with it, so the watcher notices when it appears.
    static final Fingerprint ABSENT = new Fingerprint(null, -1, null);

    /// @return the fingerprint, or [#ABSENT]
    static Fingerprint of(Path path) {
      try {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return new Fingerprint(
            attributes.lastModifiedTime(), attributes.size(), attributes.fileKey());
      } catch (IOException e) {
        return ABSENT;
      }
    }
  }

  /// Reads a file fully and records it
  ///
  /// @throws IOException if the file cannot be read
  byte[] read(Path path) throws IOException {
    Path absolute = path.toAbsolutePath();
    recordAbsolute(absolute);
    return Files.readAllBytes(absolute);
  }

  /// Opens a location, a `classpath:` resource or a file, which is recorded. A file is read fully,
  /// the locations are small: keys, certificates and pages.
  ///
  /// @throws IOException if the location cannot be read
  InputStream open(String location) throws IOException {
    if (location.startsWith(CLASSPATH_PREFIX)) {
      String path = location.substring(CLASSPATH_PREFIX.length());
      if (path.startsWith("/")) {
        path = path.substring(1);
      }
      InputStream in = ConfigFiles.class.getClassLoader().getResourceAsStream(path);
      if (in == null) {
        throw new FileNotFoundException("Class path resource not found: " + path);
      }
      return in;
    }
    return new ByteArrayInputStream(read(Path.of(location)));
  }

  /// The secret: the value as configured, or the content of the file, which is recorded, without
  /// one final line break (`\n` or `\r\n`), as editors and `echo` add it. Other characters,
  /// also trailing spaces, are kept, so a secret moved from inline to a file keeps its value.
  ///
  /// @throws UncheckedIOException if the file cannot be read
  /// @throws IllegalArgumentException if the file is empty or holds only whitespace, so a broken
  ///   rotation is rejected and the last working secret stays active
  String secret(SecretConfig secret) {
    String value = secret.value() != null ? secret.value() : readSecretFile(secret.file());
    secretDigests.add(EdgeState.sha256Hex(value));
    return value;
  }

  /// Reads a secret file, for [#secret(SecretConfig)]
  private String readSecretFile(String file) {
    String content;
    try {
      content = new String(read(Path.of(file)), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read secret file: " + file, e);
    }
    if (content.endsWith(CRLF)) {
      content = content.substring(0, content.length() - CRLF.length());
    } else if (content.endsWith(LF)) {
      content = content.substring(0, content.length() - LF.length());
    }
    if (content.isBlank()) {
      throw new IllegalArgumentException("Secret file is empty: " + file);
    }
    return content;
  }

  /// Records a file that a library opens itself, e.g. an IP database
  ///
  /// @throws IOException if the file does not exist, so a wrong path is reported like a file read
  ///   here. It is recorded as [Fingerprint#ABSENT] nevertheless.
  void record(Path path) throws IOException {
    Path absolute = path.toAbsolutePath();
    if (recordAbsolute(absolute) == Fingerprint.ABSENT) {
      throw new NoSuchFileException(absolute.toString());
    }
  }

  /// Takes the fingerprint before the file is read, for [#read(Path)] and [#record(Path)]. A
  /// missing file is recorded as well, so a rejected configuration that needs it is tried again
  /// when it appears, see [Proxy#referencedFiles()].
  ///
  /// @return the recorded fingerprint
  private Fingerprint recordAbsolute(Path absolute) {
    return files.computeIfAbsent(absolute, Fingerprint::of);
  }

  /// Records the files and secrets of a component taken over from the previous build, at the
  /// position the component has in this build, see [Snapshot#unchanged()]
  void recordAll(Snapshot taken) {
    taken.files().forEach(files::putIfAbsent);
    secretDigests.addAll(taken.secretDigests());
  }

  /// @return the files and secret digests recorded so far
  Snapshot snapshot() {
    return new Snapshot(Map.copyOf(files), List.copyOf(secretDigests));
  }
}
