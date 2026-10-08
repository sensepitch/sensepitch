package org.sensepitch.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/// @author Jens Wilke
public class CombinedIpTraitsLookup implements IpTraitsLookup {

  static final String CRAWLER_IPS_DIRECTORY = "crawler-ips/";
  static final String CRAWLER_LABEL_PREFIX = "crawler:";
  static final String UNKNOWN_CRAWLER_NAME = "unknown";

  ProxyLogger LOG = ProxyLogger.get(CombinedIpTraitsLookup.class);

  private final List<IpTraitsLookup> ipAttributesLookups;
  private final IpLabelLookup ipLabelLookup;

  public CombinedIpTraitsLookup(IpLookupConfig ipLookupConfig) throws IOException {
    ipAttributesLookups = new ArrayList<>();
    if (ipLookupConfig.geoIp2() != null) {
      if (ipLookupConfig.geoIp2().asnDbPath() != null) {
        addAsnLookup(new GeoIp2AsnLookup(ipLookupConfig.geoIp2().asnDbPath()));
      }
      if (ipLookupConfig.geoIp2().countryDbPath() != null) {
        addCountryLookup(new GeoIp2CountryLookup(ipLookupConfig.geoIp2().countryDbPath()));
      }
    }
    if (ipLookupConfig.ipInfoPath() != null) {
      IpInfoCountryAndAsnLookup ipLookup =
          new IpInfoCountryAndAsnLookup(ipLookupConfig.ipInfoPath());
      ipAttributesLookups.add(ipLookup);
    }
    ipLabelLookup = readCrawlerIpLists();
    LOG.info("IP lookup nodes: " + ((TrieIpLabelLookup) ipLabelLookup).getNodeCount());
  }

  /// Reads the published crawler IP lists named in `crawler-ips/index.txt`. Each address is labeled
  /// `crawler:<list name>`. The list name is the path below `crawler-ips/` without `.json`, with
  /// the directory as namespace separated by `:`, e.g. `google/googlebot.json` becomes
  /// `google:googlebot`. Entries without a prefix length are single addresses.
  public static TrieIpLabelLookup readCrawlerIpLists() throws IOException {
    TrieIpLabelLookup lookup = new TrieIpLabelLookup();
    ObjectMapper mapper = new ObjectMapper();
    for (var file : ResourceLoader.getFileList(CRAWLER_IPS_DIRECTORY)) {
      String label = CRAWLER_LABEL_PREFIX + crawlerListName(file);
      JsonNode root = mapper.readTree(Proxy.class.getResource("/" + file));
      for (JsonNode prefix : root.path("prefixes")) {
        if (prefix.has("ipv4Prefix")) {
          lookup.insertIpv4(withPrefixLength(prefix.get("ipv4Prefix").asText(), 32), label);
        } else if (prefix.has("ipv6Prefix")) {
          lookup.insertIpv6(withPrefixLength(prefix.get("ipv6Prefix").asText(), 128), label);
        }
      }
    }
    return lookup;
  }

  static String crawlerListName(String file) {
    String name = file.substring(CRAWLER_IPS_DIRECTORY.length());
    if (name.endsWith(".json")) {
      name = name.substring(0, name.length() - ".json".length());
    }
    return name.replace('/', ':');
  }

  private static String withPrefixLength(String prefix, int addressBits) {
    return prefix.contains("/") ? prefix : prefix + "/" + addressBits;
  }

  private void addAsnLookup(AsnLookup asnLookup) {
    ipAttributesLookups.add(
        (builder, address) -> {
          long asn = asnLookup.lookupAsn(address);
          if (asn >= 0) {
            builder.asn(asn);
          }
        });
  }

  private void addCountryLookup(GeoIp2CountryLookup countryLookup) {
    ipAttributesLookups.add(
        (builder, address) -> {
          var country = countryLookup.lookupCountry(address);
          if (country != null) {
            builder.isoCountry(country);
          }
        });
  }

  private void lookupException(Exception e) {
    LOG.error(e.toString());
  }

  @Override
  public void lookup(IpTraits.Builder builder, InetAddress address) {
    for (IpTraitsLookup db : ipAttributesLookups) {
      try {
        db.lookup(builder, address);
      } catch (Exception e) {
        lookupException(e);
      }
    }
    byte[] addressBytes = address.getAddress();
    List<String> labelList;
    if (addressBytes.length == 4) {
      labelList = ipLabelLookup.lookupIpv4(addressBytes);
    } else {
      labelList = ipLabelLookup.lookupIpv6(addressBytes);
    }
    if (labelList != null) {
      for (String label : labelList) {
        if (label.startsWith(CRAWLER_LABEL_PREFIX)) {
          String name = label.substring(CRAWLER_LABEL_PREFIX.length());
          builder.crawlerName(name.isEmpty() ? UNKNOWN_CRAWLER_NAME : name);
        }
      }
    }
  }
}
