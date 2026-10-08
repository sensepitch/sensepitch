package org.sensepitch.edge;

import java.util.HashMap;
import java.util.Map;
import lombok.Builder;

/// Typical properties we want to look up for an IP address. `crawlerName` names the published
/// crawler IP list the address belongs to, e.g. `google:googlebot` or `amazon:amazonbot`, `unknown`
/// if the crawler has no name, and is `null` if the address is not a known crawler.
///
/// @author Jens Wilke
/// @see com.maxmind.geoip2.record.Traits
@Builder(builderClassName = "Builder")
public record IpTraits(
    long asn, String isoCountry, String crawlerName, Map<String, String> keyValue) {

  public boolean isAsnKnown() {
    return asn >= 0;
  }

  public boolean isCrawler() {
    return crawlerName != null;
  }

  public static class Builder {
    private long asn = -1;
    Map<String, String> keyValue = new HashMap<String, String>();

    public void addLabel(String key, String value) {
      keyValue.put(key, value);
    }
  }
}
