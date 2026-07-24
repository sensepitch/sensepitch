package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ResponseConfig(
    String text,
    int status,
    String location,
    String permanentRedirect,
    String temporaryRedirect,
    String contentType,
    String file) {
  /**
   * A response is exactly one of two kinds; setting fields from both is a misconfiguration:
   *
   * <ul>
   *   <li><b>redirect</b>: {@code location} (or {@code permanentRedirect}/ {@code
   *       temporaryRedirect}), no body
   *   <li><b>page</b>: {@code text} or {@code file} (with optional {@code contentType})
   * </ul>
   */
  public ResponseConfig {
    boolean redirect = location != null || permanentRedirect != null || temporaryRedirect != null;
    boolean page = text != null || file != null;
    if (redirect && page) {
      throw new IllegalArgumentException(
          """
                            response is both a redirect and a page; use ONE of:
                              redirect -> location (optionally with a 3xx status)
                              page     -> text or file (optionally with an explicit status)""");
    }
    if (redirect && status != 0 && (status < 300 || status > 399)) {
      throw new IllegalArgumentException("redirect status must be 3xx, was: " + status);
    }
  }

  /**
   * A resolved redirect.
   *
   * @param status 3xx status code
   * @param location target URL for the {@code Location} header
   */
  public record Redirect(int status, String location) {}

  public Redirect resolvedRedirect() {
    if (permanentRedirect != null) return new Redirect(308, permanentRedirect);
    if (temporaryRedirect != null) return new Redirect(307, temporaryRedirect);
    if (location != null) return new Redirect(status != 0 ? status : 302, location);
    return null;
  }
}
