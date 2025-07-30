package org.sensepitch.edge;

/**
 * @author Jens Wilke
 */
public record CookieAdmissionConfig(
  String cookieName,
  String uri,
  String redirectLocation
) {
}
