package org.sensepitch.edge;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/// Reads the body of a [ResponseConfig#file()]. An explicit `classpath:` or `file:` scheme picks
/// exactly that source; a bare (schemeless) location is tried on the filesystem first and then on
/// the classpath. Relative filesystem paths resolve against the working directory. A `file://` URI,
/// e.g. `file:///etc/page.html`, is parsed as URI.
///
/// @author Jens Wilke
public final class ResponseFiles {

  private static final String CLASSPATH_PREFIX = "classpath:";

  private static final String FILE_PREFIX = "file:";

  private ResponseFiles() {}

  /// Read the resource fully; an unresolvable location is a configuration error.
  ///
  /// @throws UncheckedIOException if the resource cannot be read
  public static byte[] read(String location) {
    try (InputStream in = open(location)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read response file: " + location, e);
    }
  }

  private static InputStream open(String location) throws IOException {
    if (location.startsWith(CLASSPATH_PREFIX)) {
      return openClasspath(location.substring(CLASSPATH_PREFIX.length()));
    }
    if (location.startsWith(FILE_PREFIX + "//")) {
      return Files.newInputStream(Path.of(URI.create(location)));
    }
    if (location.startsWith(FILE_PREFIX)) {
      return new FileInputStream(location.substring(FILE_PREFIX.length()));
    }
    // No scheme: try the filesystem first, then the classpath.
    Path filePath = Path.of(location);
    if (Files.isReadable(filePath)) {
      return Files.newInputStream(filePath);
    }
    InputStream in = findClasspath(location);
    if (in == null) {
      throw new FileNotFoundException(
          "Not found on filesystem (" + filePath.toAbsolutePath() + ") or classpath: " + location);
    }
    return in;
  }

  private static InputStream openClasspath(String path) throws IOException {
    InputStream in = findClasspath(path);
    if (in == null) {
      throw new FileNotFoundException("Classpath resource not found: " + path);
    }
    return in;
  }

  /// @return the resource stream, or `null` if not found
  private static InputStream findClasspath(String path) {
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    return ResponseFiles.class.getClassLoader().getResourceAsStream(path);
  }
}
