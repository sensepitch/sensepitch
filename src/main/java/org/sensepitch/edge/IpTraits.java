package org.sensepitch.edge;

import java.util.HashMap;
import java.util.Map;
import lombok.Builder;

/**
 * Typical properties we want to look up for an IP address.
 *
 * @author Jens Wilke
 * @see com.maxmind.geoip2.record.Traits
 */
@Builder(builderClassName = "Builder")
public record IpTraits(long asn, String isoCountry, boolean crawler, Map<String, String> keyValue) {

  public boolean isAsnKnown() {
    return asn >= 0;
  }

  public static class Builder {
    long asn = -1;
    Map<String, String> keyValue = new HashMap<String, String>();

    public void addLabel(String key, String value) {
      keyValue.put(key, value);
    }
  }
}
