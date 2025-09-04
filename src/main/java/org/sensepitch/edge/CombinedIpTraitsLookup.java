package org.sensepitch.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Jens Wilke
 */
public class CombinedIpTraitsLookup implements IpTraitsLookup {

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
    ipLabelLookup = readGoogleBotList();
    LOG.info("IP lookup nodes: " + ((TrieIpLabelLookup) ipLabelLookup).getNodeCount());
  }

  public static TrieIpLabelLookup readGoogleBotList() throws IOException {
    TrieIpLabelLookup lookup = new TrieIpLabelLookup();
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(Proxy.class.getResource("/googlebot.json"));
    for (JsonNode prefix : root.path("prefixes")) {
      if (prefix.has("ipv4Prefix")) {
        final String ipv4Prefix = prefix.get("ipv4Prefix").asText();
        lookup.insertIpv4(ipv4Prefix, "crawler:googlebot");
      } else if (prefix.has("ipv6Prefix")) {
        lookup.insertIpv6(prefix.get("ipv6Prefix").asText(), "crawler:googlebot");
      }
    }
    return lookup;
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
        if (label.startsWith("crawler:")) {
          builder.crawler(true);
        }
      }
    }
  }
}
