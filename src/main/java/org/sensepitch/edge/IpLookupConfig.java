package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder
public record IpLookupConfig(GeoIp2Config geoIp2) {}
