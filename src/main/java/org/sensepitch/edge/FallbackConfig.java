package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Raid Thabet
*/
@Builder(toBuilder = true)
public record FallbackConfig(
        String pathToUnavailablePage,
        String unavailableText,
        String pathToErrorPage,
        String errorText
) {
    public static final String DEFAULT_PATH_TO_UNAVAILABLE_PAGE = "/unavailable.html";
    public static final String DEFAULT_UNAVAILABLE_TEXT = "Service Unavailable";
    public static final String DEFAULT_PATH_TO_ERROR_PAGE = "/error.html";
    public static final String DEFAULT_ERROR_TEXT = "Internal Server Error";

    public static final FallbackConfig DEFAULT =
            FallbackConfig.builder()
                    .pathToUnavailablePage(DEFAULT_PATH_TO_UNAVAILABLE_PAGE)
                    .unavailableText(DEFAULT_UNAVAILABLE_TEXT)
                    .pathToErrorPage(DEFAULT_PATH_TO_ERROR_PAGE)
                    .errorText(DEFAULT_ERROR_TEXT)
                    .build();
}
