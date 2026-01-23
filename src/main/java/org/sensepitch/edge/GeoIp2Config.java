package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record GeoIp2Config(String asnDbPath, String countryDbPath) {}
