package org.sensepitch.edge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * @author Jens Wilke
 */
public class CsvReader {

  public static Stream<String[]> readAll(String path) throws IOException {
    try (Stream<String> lines = Files.lines(Paths.get(path))) {
      return lines.map(CsvReader::parseLine);
    }
  }

  // Regex to split on commas that are _not_ inside quotes
  private static final String CSV_SPLIT_REGEX = ",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)";

  private static String[] parseLine(String line) {
    String[] tokens = line.split(CSV_SPLIT_REGEX, -1);
    for (int i = 0; i < tokens.length; i++) {
      String field = tokens[i].trim();
      // remove surrounding quotes, if any
      if (field.startsWith("\"") && field.endsWith("\"")) {
        field =
            field.substring(1, field.length() - 1).replace("\"\"", "\""); // un-escape double quotes
      }
      tokens[i] = field;
    }
    return tokens;
  }
}
