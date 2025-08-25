package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ResponseConfig(
    String text, int status, String location, String permanentRedirect, String temporaryRedirect) {}
