package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ResponseConfig(
    String text, int status, String location, String contentType, String file) {

  /**
   * Status codes a redirect may use. Deliberately narrower than the whole 3xx range: 300, 304, 305
   * and 306 are 3xx but are not redirects a {@code Location} header makes sense for.
   */
  private static final List<Integer> REDIRECT_CODES = List.of(301, 302, 303, 307, 308);

  private static final int DEFAULT_REDIRECT_STATUS = 302;

  public ResponseConfig {
    boolean isRedirect = location != null;
    boolean hasPageBody = text != null || file != null;
    if (isRedirect && hasPageBody || !isRedirect && !hasPageBody) {
      throw new IllegalArgumentException(
          """
          response should be one of:
            redirect -> location (optionally with an explicit redirect status)
            page     -> text or file (optionally with an explicit status)""");
    }
    if (isRedirect) {
      if (status == 0) {
        status = DEFAULT_REDIRECT_STATUS;
      } else if (!REDIRECT_CODES.contains(status)) {
        throw new IllegalArgumentException(
            "redirect status must be one of " + REDIRECT_CODES + ", was: " + status);
      }
    }
    if (text != null && file != null) {
      throw new IllegalArgumentException("page is text OR file, not both");
    }
    if (status != 0 && (status < 100 || status > 599)) {
      throw new IllegalArgumentException("status must be 100..599, was: " + status);
    }
  }
}
