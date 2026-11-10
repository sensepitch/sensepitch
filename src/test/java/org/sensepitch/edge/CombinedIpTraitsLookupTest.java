package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
public class CombinedIpTraitsLookupTest {

  @Test
  public void test() throws IOException {
    var x = new CombinedIpTraitsLookup(IpLookupConfig.builder().build());
  }

  @Test
  public void crawlerName() throws IOException {
    var lookup = new CombinedIpTraitsLookup(IpLookupConfig.builder().build());
    IpTraits amazonbot = lookup(lookup, "3.210.223.61");
    assertThat(amazonbot.crawlerName()).isEqualTo("amazon:amazonbot");
    IpTraits googlebot = lookup(lookup, "192.178.4.1");
    assertThat(googlebot.crawlerName()).isEqualTo("google:googlebot");
    IpTraits other = lookup(lookup, "1.2.3.4");
    assertThat(other.isCrawler()).isFalse();
    assertThat(other.crawlerName()).isNull();
  }

  @Test
  public void crawlerNameIpv6() throws IOException {
    var lookup = new CombinedIpTraitsLookup(IpLookupConfig.builder().build());
    assertThat(lookup(lookup, "2001:4860:4801:10::1").crawlerName()).isEqualTo("google:googlebot");
    assertThat(lookup(lookup, "2001:db8::1").isCrawler()).isFalse();
  }

  @Test
  public void crawlerListName() {
    assertThat(CombinedIpTraitsLookup.crawlerListName("crawler-ips/google/googlebot.json"))
        .isEqualTo("google:googlebot");
    assertThat(CombinedIpTraitsLookup.crawlerListName("crawler-ips/plain")).isEqualTo("plain");
  }

  private static IpTraits lookup(CombinedIpTraitsLookup lookup, String address) throws IOException {
    var builder = IpTraits.builder();
    lookup.lookup(builder, InetAddress.getByName(address));
    return builder.build();
  }
}
