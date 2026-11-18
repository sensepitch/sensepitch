package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// @author Jens Wilke
public class CombinedIpTraitsLookupTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  ProxyMetrics metrics = new ProxyMetrics();

  @Test
  public void test() throws IOException {
    var x = new CombinedIpTraitsLookup(IpLookupConfig.builder().build(), metrics);
  }

  @Test
  public void crawlerName() throws IOException {
    var lookup = new CombinedIpTraitsLookup(IpLookupConfig.builder().build(), metrics);
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
    var lookup = new CombinedIpTraitsLookup(IpLookupConfig.builder().build(), metrics);
    assertThat(lookup(lookup, "2001:4860:4801:10::1").crawlerName()).isEqualTo("google:googlebot");
    assertThat(lookup(lookup, "2001:db8::1").isCrawler()).isFalse();
  }

  @Test
  public void crawlerListName() {
    assertThat(CombinedIpTraitsLookup.crawlerListName("crawler-ips/google/googlebot.json"))
        .isEqualTo("google:googlebot");
    assertThat(CombinedIpTraitsLookup.crawlerListName("crawler-ips/plain")).isEqualTo("plain");
  }

  /// Country and ASN from the databases are combined with the crawler label from the lists.
  @Test
  public void databaseResultsAreCombined() throws IOException {
    var lookup =
        new CombinedIpTraitsLookup(
            List.of(
                CombinedIpTraitsLookup.asnTraits(addr -> 15169),
                CombinedIpTraitsLookup.countryTraits(addr -> "US")),
            metrics);
    IpTraits traits = lookup(lookup, "192.178.4.1");
    assertThat(traits.asn()).isEqualTo(15169);
    assertThat(traits.isoCountry()).isEqualTo("US");
    assertThat(traits.crawlerName()).isEqualTo("google:googlebot");
  }

  /// Addresses not in a database, e.g. private ranges, leave the traits unset.
  @Test
  public void notFoundLeavesTraitsUnset() throws IOException {
    var lookup =
        new CombinedIpTraitsLookup(
            List.of(
                CombinedIpTraitsLookup.asnTraits(addr -> -1),
                CombinedIpTraitsLookup.countryTraits(addr -> null)),
            metrics);
    IpTraits traits = lookup(lookup, "10.0.0.1");
    assertThat(traits.isAsnKnown()).isFalse();
    assertThat(traits.isoCountry()).isNull();
  }

  /// A later database overwrites what an earlier one found, the configuration order decides.
  @Test
  public void laterLookupWins() throws IOException {
    var lookup =
        new CombinedIpTraitsLookup(
            List.of(
                CombinedIpTraitsLookup.countryTraits(addr -> "DE"),
                CombinedIpTraitsLookup.countryTraits(addr -> "AT")),
            metrics);
    assertThat(lookup(lookup, "1.2.3.4").isoCountry()).isEqualTo("AT");
  }

  /// A broken database must not fail the request: the error is counted and logged at debug, the
  /// other lookups and the crawler lists still apply.
  @Test
  public void failingLookupIsLoggedAndSkipped() throws IOException {
    var lookup =
        new CombinedIpTraitsLookup(
            List.of(
                CombinedIpTraitsLookup.asnTraits(
                    addr -> {
                      throw new IOException("database corrupt");
                    }),
                CombinedIpTraitsLookup.countryTraits(addr -> "US")),
            metrics);
    IpTraits traits = lookup(lookup, "192.178.4.1");
    assertThat(traits.isAsnKnown()).isFalse();
    assertThat(traits.isoCountry()).isEqualTo("US");
    assertThat(traits.crawlerName()).isEqualTo("google:googlebot");
    assertThat(metrics.ipLookupErrorCounter.collect().getDataPoints().getFirst().getValue())
        .isOne();
    assertThat(logs.errors()).isEmpty();
    assertThat(logs.entries())
        .filteredOn(e -> e.info().level() == System.Logger.Level.DEBUG)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.source()).isEqualTo(CombinedIpTraitsLookup.class.getName());
              assertThat(e.error()).hasMessage("database corrupt");
            });
  }

  private static IpTraits lookup(CombinedIpTraitsLookup lookup, String address) throws IOException {
    return lookup.lookup(InetAddress.getByName(address));
  }
}
