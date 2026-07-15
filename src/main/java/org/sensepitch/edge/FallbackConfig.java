package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Raid Thabet
 */
@Builder(toBuilder = true)
public record FallbackConfig(
        ResponseConfig unavailableResponse,
        ResponseConfig errorResponse
) {
    public static final String DEFAULT_UNAVAILABLE_FILE = "fallback/unavailable.html";
    public static final String DEFAULT_UNAVAILABLE_TEXT = "Service Unavailable";
    public static final String DEFAULT_ERROR_FILE = "fallback/error.html";
    public static final String DEFAULT_ERROR_TEXT = "Internal Server Error";
    public static final String DEFAULT_CONTENT_TYPE = "text/html; charset=UTF-8";

    public static final FallbackConfig DEFAULTS =
            FallbackConfig.builder()
                    .unavailableResponse(
                            ResponseConfig.builder()
                                    .file(DEFAULT_UNAVAILABLE_FILE)
                                    .text(DEFAULT_UNAVAILABLE_TEXT)
                                    .contentType(DEFAULT_CONTENT_TYPE)
                                    .build()
                    )
                    .errorResponse(
                            ResponseConfig.builder()
                                    .file(DEFAULT_ERROR_FILE)
                                    .text(DEFAULT_ERROR_TEXT)
                                    .contentType(DEFAULT_CONTENT_TYPE)
                                    .build()
                    )
                    .build();

    public FallbackConfig merge(FallbackConfig o) {
        if (o == null) return this;
        return FallbackConfig.builder()
                .unavailableResponse(o.unavailableResponse != null ? o.unavailableResponse : unavailableResponse)
                .errorResponse(o.errorResponse != null ? o.errorResponse : errorResponse)
                .build();
    }
}
