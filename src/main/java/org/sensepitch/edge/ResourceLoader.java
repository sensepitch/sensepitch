package org.sensepitch.edge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Jens Wilke
 */
public class ResourceLoader {

  public static String loadTextFile(String resourcePath) {
    try (InputStream in = ResourceLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IOException("Resource not found on classpath: " + resourcePath);
      }
      // Wrap in a reader and collect all lines into one String
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        return reader.lines().collect(Collectors.joining(System.lineSeparator()));
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  public static byte[] loadBinaryFile(String resourcePath) {
    try (InputStream in = ResourceLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IOException("Resource not found on classpath: " + resourcePath);
      }
      return in.readAllBytes();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  public static Set<String> getFileList(String directoryWithIndex) {
    String fileList = ResourceLoader.loadTextFile(directoryWithIndex + "index.txt");
    LineNumberReader reader = new LineNumberReader(new StringReader(fileList));
    return reader.lines().map(s -> directoryWithIndex + s).collect(Collectors.toSet());
  }

}
