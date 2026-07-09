package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Raid Thabet
 */
@Builder(toBuilder = true)
public record FallbackConfig(
        String unavailablePage,
        String unavailableText,
        String errorPage,
        String errorText
) {
    public static final String DEFAULT_UNAVAILABLE_PAGE = "fallback/unavailable.html";
    public static final String DEFAULT_UNAVAILABLE_TEXT = "Service Unavailable";
    public static final String DEFAULT_ERROR_PAGE = "fallback/error.html";
    public static final String DEFAULT_ERROR_TEXT = "Internal Server Error";

    public static final FallbackConfig DEFAULT =
            FallbackConfig.builder()
                    .unavailablePage(DEFAULT_UNAVAILABLE_PAGE)
                    .unavailableText(DEFAULT_UNAVAILABLE_TEXT)
                    .errorPage(DEFAULT_ERROR_PAGE)
                    .errorText(DEFAULT_ERROR_TEXT)
                    .build();

    public FallbackConfig merge(FallbackConfig o) {
        if (o == null) return this;
        return FallbackConfig.builder()
                .unavailablePage(o.unavailablePage() != null ? o.unavailablePage() : unavailablePage)
                .unavailableText(o.unavailableText() != null ? o.unavailableText() : unavailableText)
                .errorPage(o.errorPage() != null ? o.errorPage() : errorPage)
                .errorText(o.errorText() != null ? o.errorText() : errorText)
                .build();
    }
}
