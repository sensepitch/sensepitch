package org.sensepitch.edge;

import lombok.Builder;

/// What the edge sends instead of an upstream 5xx. Each response is either a page (inline {@code
/// text} or a {@code file} resource) or a redirect ({@code location}), see {@link ResponseConfig}.
/// Unset responses fall back to {@link #DEFAULTS} via {@link #merge}.
///
/// <p>Configuration is layered, {@code built-in defaults > global fallback > site fallback}.
/// Merging happens <b>per slot, as a whole object</b>, not per field: if a site sets {@code
/// unavailableResponse}, that entire slot (including any inherited {@code file}/{@code
/// contentType}) is replaced by what the site specified, while an {@code errorResponse} the site
/// doesn't mention keeps inheriting from global or default config.
///
/// @param unavailableResponse replaces an upstream 503, i.e. the site is reachable but not serving
/// @param errorResponse replaces an upstream 500, i.e. the site failed on the request
/// @author Raid Thabet
@Builder(toBuilder = true)
public record FallbackConfig(ResponseConfig unavailableResponse, ResponseConfig errorResponse) {
  public static final String DEFAULT_UNAVAILABLE_FILE = "classpath:fallback/unavailable.html";
  public static final String DEFAULT_ERROR_FILE = "classpath:fallback/error.html";
  public static final String DEFAULT_CONTENT_TYPE = "text/html; charset=UTF-8";

  public static final FallbackConfig DEFAULTS =
      FallbackConfig.builder()
          .unavailableResponse(
              ResponseConfig.builder()
                  .file(DEFAULT_UNAVAILABLE_FILE)
                  .contentType(DEFAULT_CONTENT_TYPE)
                  .build())
          .errorResponse(
              ResponseConfig.builder()
                  .file(DEFAULT_ERROR_FILE)
                  .contentType(DEFAULT_CONTENT_TYPE)
                  .build())
          .build();

  public FallbackConfig merge(FallbackConfig o) {
    if (o == null) return this;
    return FallbackConfig.builder()
        .unavailableResponse(
            o.unavailableResponse != null ? o.unavailableResponse : unavailableResponse)
        .errorResponse(o.errorResponse != null ? o.errorResponse : errorResponse)
        .build();
  }
}
