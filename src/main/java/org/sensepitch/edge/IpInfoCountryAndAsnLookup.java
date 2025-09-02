package org.sensepitch.edge;

import com.maxmind.db.MaxMindDbConstructor;
import com.maxmind.db.MaxMindDbParameter;
import com.maxmind.db.Reader;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

/**
 * Lookup in IPinfo database. This is using the raw {@link com.maxmind.db.Reader}
 *
 * @author Jens Wilke
 * @see <a href="https://ipinfo.io/lite">IP info lite</a>
 */
public class IpInfoCountryAndAsnLookup implements IpTraitsLookup {

  ProxyLogger LOG = ProxyLogger.get(IpInfoCountryAndAsnLookup.class);

  private final Reader reader;

  public IpInfoCountryAndAsnLookup(String combindedDbFile) throws IOException {
    File database = new File(combindedDbFile);
    reader = new Reader(database);
    LOG.info("IPInfo database initialized, buildDate=" + reader.getMetadata().getBuildDate() + ", description=" + reader.getMetadata().getDescription().get("en"));
  }

  @Override
  public void lookup(IpTraits.Builder builder, InetAddress address) throws Exception {
    // var map = reader.get(address, Map.class);
    // output example: {continent=Europe, country=Belgium, country_code=BE, as_domain=google.com, continent_code=EU, asn=AS15169, as_name=Google LLC}
    var tuple = reader.get(address, CountryCodeAndAsnTuple.class);
    if (tuple != null) {
      if (tuple.countryCode != null) {
        builder.isoCountry(tuple.countryCode);
      }
      if (tuple.asn != null && tuple.asn.length() > 2) {
        builder.asn(Long.parseLong(tuple.asn.substring(2)));
      }
    }
  }

  public static class CountryCodeAndAsnTuple {

    String countryCode;
    String asn;
    @MaxMindDbConstructor
    public CountryCodeAndAsnTuple(@MaxMindDbParameter(name="country_code") String countryCode, @MaxMindDbParameter(name="asn") String asn) {
      this.countryCode = countryCode;
      this.asn = asn;
    }

    @Override
    public String toString() {
      return "CountryCodeAndAsnTuple{" +
        "countryCode='" + countryCode + '\'' +
        ", asn='" + asn + '\'' +
        '}';
    }
  }

}
