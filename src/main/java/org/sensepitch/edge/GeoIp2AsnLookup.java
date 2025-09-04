package org.sensepitch.edge;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

/**
 * @author Jens Wilke
 * @see <a href="https://github.com/maxmind/GeoIP2-java"/>
 */
public class GeoIp2AsnLookup implements AsnLookup {

  private final DatabaseReader reader;

  public GeoIp2AsnLookup(String asnDbFile) throws IOException {
    File database = new File(asnDbFile);
    reader = new DatabaseReader.Builder(database).build();
  }

  @Override
  public long lookupAsn(InetAddress addr) throws Exception {
    try {
      return reader.asn(addr).getAutonomousSystemNumber();
    } catch (AddressNotFoundException ex) {
      return -1;
    }
  }
}
