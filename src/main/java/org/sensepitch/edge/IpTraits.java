package org.sensepitch.edge;

import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/// Typical properties we want to look up for an IP address. `crawlerName` names the published
/// crawler IP list the address belongs to, e.g. `google:googlebot` or `amazon:amazonbot`, `unknown`
/// if the crawler has no name, and is `null` if the address is not a known crawler.
/// Custom `keyValue` labels are appended to `Sp-Ip-Traits` in key order after these properties,
/// see [Builder#addLabel] for what a label may contain.
///
/// @author Jens Wilke
/// @see com.maxmind.geoip2.record.Traits
@lombok.EqualsAndHashCode
@lombok.ToString
public final class IpTraits {
  private final long asn;
  private final String isoCountry;
  private final String crawlerName;
  private final Map<String, String> keyValue;
  private final String header;

  @lombok.Builder(builderClassName = "Builder")
  public IpTraits(long asn, String isoCountry, String crawlerName, Map<String, String> keyValue) {
    this.asn = asn;
    this.isoCountry = isoCountry;
    this.crawlerName = crawlerName;
    Map<String, String> labels = keyValue == null ? Map.of() : keyValue;
    if (!labels.isEmpty()
        && (!(labels instanceof SortedMap<?, ?> sorted) || sorted.comparator() != null)) {
      labels = new TreeMap<>(labels);
    }
    StringBuilder value = new StringBuilder();
    if (asn >= 0) {
      appendTrait(value, "asn", Long.toString(asn));
    }
    if (isoCountry != null) {
      appendTrait(value, "country", isoCountry);
    }
    if (crawlerName != null) {
      appendTrait(value, "crawler", crawlerName);
    }
    labels.forEach((key, label) -> appendTrait(value, key, label));
    header = value.toString();
    this.keyValue = Map.copyOf(labels);
  }

  /// Appends a trait to the cached upstream header.
  private static void appendTrait(StringBuilder header, String key, String value) {
    if (!header.isEmpty()) {
      header.append(", ");
    }
    header.append(key).append('=').append(value);
  }

  public long asn() {
    return asn;
  }

  public String isoCountry() {
    return isoCountry;
  }

  public String crawlerName() {
    return crawlerName;
  }

  public Map<String, String> keyValue() {
    return keyValue;
  }

  /// Returns the precomputed value of `Sp-Ip-Traits`.
  public String header() {
    return header;
  }

  public boolean isAsnKnown() {
    return asn >= 0;
  }

  public boolean isCrawler() {
    return crawlerName != null;
  }

  /// Keys of the built-in traits in `Sp-Ip-Traits`, not usable as custom label keys.
  static final Set<String> RESERVED_KEYS = Set.of("asn", "country", "crawler");

  /// Visible ASCII without the separators of `Sp-Ip-Traits`, so the header stays parseable.
  private static final Pattern LABEL_TOKEN = Pattern.compile("[!-~&&[^,=]]+");

  public static class Builder {
    private long asn = -1;
    Map<String, String> keyValue = new TreeMap<>();

    /// Adds a custom label, a later one with the same key replaces an earlier one.
    ///
    /// @throws IllegalArgumentException if the key is reserved for a built-in trait, or the key or
    ///   value is empty or contains whitespace, control characters, `,` or `=`
    public void addLabel(String key, String value) {
      checkToken("key", key);
      checkToken("value", value);
      if (RESERVED_KEYS.contains(key)) {
        throw new IllegalArgumentException("Reserved IP label key: " + key);
      }
      keyValue.put(key, value);
    }

    /// Rejects what would break the `key=value, ...` format of `Sp-Ip-Traits`.
    private static void checkToken(String what, String token) {
      if (!LABEL_TOKEN.matcher(token).matches()) {
        throw new IllegalArgumentException("Invalid IP label " + what + ": '" + token + "'");
      }
    }
  }
}
