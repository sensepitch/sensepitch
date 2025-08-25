package org.sensepitch.edge;

import lombok.Builder;

/**
 * Blocks access if the specified cookie is not present. The content of the cookie is not relevant.
 * If the cookie is not present, the request is responded with 404. 404 is returned intentionally so
 * that no information is leaked about possible protected content, e.g. GitHub is returning 404 as
 * well in case a private repository is accessed without being logged in.
 *
 * @param name name of the cookie to check
 * @param accessUri if this URI is requested the cookie is set
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record CookieGateConfig(String name, String accessUri) {}
