package org.sensepitch.edge;

import com.maxmind.geoip2.DatabaseReader;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

/**
 * @author Jens Wilke
 */
public class GeoIp2CountryLookup {

  private final DatabaseReader reader;

  public GeoIp2CountryLookup(String countryDbFile) throws IOException {
    File database = new File(countryDbFile);
    reader = new DatabaseReader.Builder(database).build();
  }

  public String lookupCountry(InetAddress addr) throws Exception {
    var optional = reader.tryCountry(addr);
    if (optional.isEmpty()) {
      return null;
    }
    var response = optional.get();
    var country = response.getCountry();
    if (country == null) {
      return null;
    }
    var isoCode = country.getIsoCode();
    return isoCode;
  }
}
