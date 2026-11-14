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

  private static final ProxyLogger LOG = ProxyLogger.get(CombinedIpTraitsLookup.class);

  private final List<IpTraitsCollector> ipAttributesLookups;
  private final IpLabelLookup ipLabelLookup;

  public CombinedIpTraitsLookup(IpLookupConfig ipLookupConfig) throws IOException {
    this(databaseLookups(ipLookupConfig));
  }

  /// @param ipAttributesLookups queried in order for each address, a later one overwrites what an
  ///   earlier one found. Tests pass fakes here instead of databases.
  CombinedIpTraitsLookup(List<IpTraitsCollector> ipAttributesLookups) throws IOException {
    this.ipAttributesLookups = List.copyOf(ipAttributesLookups);
    ipLabelLookup = readCrawlerIpLists();
    LOG.info("IP lookup nodes: " + ((TrieIpLabelLookup) ipLabelLookup).getNodeCount());
  }

  /// Opens the databases configured
  private static List<IpTraitsCollector> databaseLookups(IpLookupConfig cfg) throws IOException {
    List<IpTraitsCollector> lookups = new ArrayList<>();
    if (cfg.geoIp2() != null) {
      if (cfg.geoIp2().asnDbPath() != null) {
        lookups.add(asnTraits(new GeoIp2AsnLookup(cfg.geoIp2().asnDbPath())));
      }
      if (cfg.geoIp2().countryDbPath() != null) {
        lookups.add(countryTraits(new GeoIp2CountryLookup(cfg.geoIp2().countryDbPath())));
      }
    }
    if (cfg.ipInfoPath() != null) {
      lookups.add(new IpInfoCountryAndAsnLookup(cfg.ipInfoPath()));
    }
    return lookups;
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

  /// Sets the ASN, if found
  static IpTraitsCollector asnTraits(AsnLookup asnLookup) {
    return (builder, address) -> {
      long asn = asnLookup.lookupAsn(address);
      if (asn >= 0) {
        builder.asn(asn);
      }
    };
  }

  /// Sets the country, if found
  static IpTraitsCollector countryTraits(CountryLookup countryLookup) {
    return (builder, address) -> {
      var country = countryLookup.lookupCountry(address);
      if (country != null) {
        builder.isoCountry(country);
      }
    };
  }

  private void lookupException(Exception e) {
    LOG.error(e.toString());
  }

  @Override
  public IpTraits lookup(InetAddress address) {
    var builder = IpTraits.builder();
    for (IpTraitsCollector db : ipAttributesLookups) {
      try {
        db.collect(builder, address);
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
    return builder.build();
  }
}
