package org.sensepitch.edge;

import lombok.Builder;

/**
 *
 * @param geoIp2 MaxMind GeoIp2 databases for IP lookup
 * @param ipInfoPath IPInfo database for IP lookups, this database contains a combination of
 *                   ASN and country code. The free IPinfo lite dataset is more accurate than
 *                   MaxMind Geoliteand only one database and lookup is needed.
 *                   See: <a href="https://ipinfo.io/dashboard/lite>IPInfo lite</a>
 *
 * @author Jens Wilke
 */
@Builder
public record IpLookupConfig(GeoIp2Config geoIp2, String ipInfoPath) {}
