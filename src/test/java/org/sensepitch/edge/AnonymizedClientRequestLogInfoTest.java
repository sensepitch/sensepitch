package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.HttpMethod;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Set;
import org.junit.jupiter.api.Test;

/// Anonymization of the client address, per destination
///
/// @author Jens Wilke
class AnonymizedClientRequestLogInfoTest {

  static String anonymize(String ip) throws Exception {
    return AnonymizedClientRequestLogInfo.anonymize(InetAddress.getByName(ip)).getHostAddress();
  }

  @Test
  void ipv4KeepsTheSlash24Network() throws Exception {
    assertThat(anonymize("192.0.2.7")).isEqualTo("192.0.2.0");
    assertThat(anonymize("127.0.0.1")).isEqualTo("127.0.0.0");
  }

  @Test
  void ipv6KeepsTheSlash48Network() throws Exception {
    assertThat(anonymize("2001:db8:1234:5678:9abc:def0:1234:5678"))
        .isEqualTo("2001:db8:1234:0:0:0:0:0");
    assertThat(anonymize("::1")).isEqualTo("0:0:0:0:0:0:0:0");
  }

  /// The JDK turns a mapped address into IPv4 already, the bytes of an IPv6 socket address may
  /// still carry one
  @Test
  void ipv4MappedIsHandledAsIpv4() throws Exception {
    byte[] mapped = new byte[16];
    mapped[10] = (byte) 0xff;
    mapped[11] = (byte) 0xff;
    mapped[12] = (byte) 192;
    mapped[14] = 2;
    mapped[15] = 7;
    InetAddress address = java.net.Inet6Address.getByAddress(null, mapped, -1);
    assertThat(AnonymizedClientRequestLogInfo.anonymize(address).getHostAddress())
        .isEqualTo("192.0.2.0");
  }

  @Test
  void nullStaysNull() {
    assertThat(AnonymizedClientRequestLogInfo.anonymize(null)).isNull();
    assertThat(
            new AnonymizedClientRequestLogInfo(TestRequestLogInfo.of(HttpMethod.GET, 200))
                .clientAddress())
        .isNull();
  }

  @Test
  void clientPortIsRemoved() {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 200).withClientAddress("192.0.2.7");
    assertThat(info.clientPort()).isPositive();
    assertThat(new AnonymizedClientRequestLogInfo(info).clientPort()).isZero();
  }

  /// Everything except the client address and port is passed through
  @Test
  void otherValuesAreDelegated() throws Exception {
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 502)
            .withClientAddress("192.0.2.7")
            .withTls("TLSv1.3")
            .withError(new IllegalStateException());
    AnonymizedClientRequestLogInfo anonymized = new AnonymizedClientRequestLogInfo(info);
    Set<String> changed = Set.of("clientAddress", "clientPort");
    for (Method method : RequestLogInfo.class.getMethods()) {
      if (method.getParameterCount() > 0 || changed.contains(method.getName())) {
        continue;
      }
      assertThat(method.invoke(anonymized)).as(method.getName()).isEqualTo(method.invoke(info));
    }
  }

  /// The wrapper works for any format, the same request is logged complete and anonymized
  @Test
  void anonymizingLoggerWrapsAnyFormat() throws Exception {
    RequestLogCapture full = new RequestLogCapture();
    RequestLogCapture anonymized = new RequestLogCapture();
    RequestLogger logger =
        new DistributingRequestLogger(
            new JsonRequestLogger(full),
            new AnonymizingRequestLogger(new JsonRequestLogger(anonymized)));
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200).withClientAddress("192.0.2.7"));
    ObjectMapper mapper = new ObjectMapper();
    JsonNode fullRecord = mapper.readTree(full.lines().getFirst());
    JsonNode anonymizedRecord = mapper.readTree(anonymized.lines().getFirst());
    assertThat(fullRecord.get("client.address").asText()).isEqualTo("192.0.2.7");
    assertThat(fullRecord.get("client.port").asInt()).isEqualTo(4711);
    assertThat(anonymizedRecord.get("client.address").asText()).isEqualTo("192.0.2.0");
    assertThat(anonymizedRecord.has("client.port")).isFalse();
  }

  /// One wrapper per thread is reused, the anonymized address is computed once per address object
  @Test
  void wrapperIsReusedAndCachesTheAddress() {
    TestRequestLogInfo first =
        TestRequestLogInfo.of(HttpMethod.GET, 200).withClientAddress("192.0.2.7");
    TestRequestLogInfo second =
        TestRequestLogInfo.of(HttpMethod.GET, 200).withClientAddress("198.51.100.9");
    AnonymizedClientRequestLogInfo wrapper = new AnonymizedClientRequestLogInfo(null);
    InetAddress anonymized = wrapper.wrap(first).clientAddress();
    assertThat(anonymized.getHostAddress()).isEqualTo("192.0.2.0");
    assertThat(wrapper.wrap(first).clientAddress()).as("cached").isSameAs(anonymized);
    assertThat(wrapper.wrap(second).clientAddress().getHostAddress()).isEqualTo("198.51.100.0");
  }
}
