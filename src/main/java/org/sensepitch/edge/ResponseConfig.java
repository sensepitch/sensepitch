package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/// A configured response, either a page or a redirect, never a mix of both:
///
/// <ul>
///   <li>page: {@code text} (plain body) or {@code file} (see {@link Fallback} for how a file
///       location is resolved), with an optional {@code contentType} (default {@link
///       FallbackConfig#DEFAULT_CONTENT_TYPE}). Setting both {@code text} and {@code file} is
///       rejected.
///   <li>redirect: {@code location}, optionally with {@code status} </ul>
///
/// @param status response status code, {@code 0} if unset. A redirect defaults to {@link
///   #DEFAULT_REDIRECT_STATUS} and must otherwise be one of {@link #REDIRECT_STATUS_CODES}. A page
///   may use any code in 100..599.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record ResponseConfig(
    String text, int status, String location, String contentType, String file) {

  public static final List<Integer> REDIRECT_STATUS_CODES = List.of(301, 302, 303, 307, 308);

  public static final int DEFAULT_REDIRECT_STATUS = 302;

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
      } else if (!REDIRECT_STATUS_CODES.contains(status)) {
        throw new IllegalArgumentException(
            "redirect status must be one of " + REDIRECT_STATUS_CODES + ", was: " + status);
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
