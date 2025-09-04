package org.sensepitch.edge.experiments;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.sensepitch.edge.CombinedIpTraitsLookup;
import org.sensepitch.edge.GeoIp2Config;
import org.sensepitch.edge.IpLookupConfig;
import org.sensepitch.edge.IpTraits;
import org.sensepitch.edge.IpTraitsLookup;

/**
 * Play with GeoLite2 and see what responses we get.
 *
 * @author Jens Wilke
 */
public class GeoLite2 {

  IpTraitsLookup traitsLookup;

  public static void main(String[] args) throws Exception {
    new GeoLite2().test();
  }

  public void test() throws Exception {
    IpLookupConfig cfg =
        IpLookupConfig.builder()
            .geoIp2(
                GeoIp2Config.builder()
                    .asnDbPath(
                        System.getenv("HOME")
                            + "/proj/maxmind-geolite2/GeoLite2-ASN-latest/GeoLite2-ASN.mmdb")
                    .countryDbPath(
                        System.getenv("HOME")
                            + "/proj/maxmind-geolite2/GeoLite2-Country-latest/GeoLite2-Country.mmdb")
                    .build())
//          .ipInfoPath( System.getenv("HOME")
//          + "/proj/ipinfo-lite/ipinfo_lite.mmdb")
            .build();
    traitsLookup = new CombinedIpTraitsLookup(cfg);
    final String address = "80.187.82.121";
    lookup(address);
    lookup("93.189.29.26");
    lookup("66.249.76.77");
    lookup("66.249.64.69");
  }

  private void lookup(String address) throws Exception {
    InetAddress ip = InetAddress.getByName(address);
    IpTraits.Builder builder = IpTraits.builder();
    traitsLookup.lookup(builder, ip);
    IpTraits traits = builder.build();
    System.out.println(address + " " + traits);
  }
}
