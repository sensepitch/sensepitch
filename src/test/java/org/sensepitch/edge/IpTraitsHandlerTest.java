package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The traits of the client address are looked up once per connection and sent upstream with every
/// request.
///
/// @author Jens Wilke
public class IpTraitsHandlerTest {

  static final InetSocketAddress CLIENT = new InetSocketAddress("192.0.2.7", 4711);

  List<InetAddress> lookedUp = new ArrayList<>();

  EmbeddedChannel channel(SocketAddress remote, IpTraitsCollector lookup) {
    IpTraitsCollector recording =
        (builder, address) -> {
          lookedUp.add(address);
          lookup.collect(builder, address);
        };
    return new EmbeddedChannel(new IpTraitsHandler(IpTraitsLookup.fromCollector(recording))) {
      @Override
      protected SocketAddress remoteAddress0() {
        return remote;
      }
    };
  }

  HttpRequest read(EmbeddedChannel channel, HttpRequest request) {
    channel.writeInbound(request);
    return channel.readInbound();
  }

  static HttpRequest request() {
    return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
  }

  @Test
  public void allTraits() {
    EmbeddedChannel channel =
        channel(
            CLIENT,
            (builder, address) -> builder.asn(15169).isoCountry("US").crawlerName("google:bot"));
    HttpRequest request = read(channel, request());
    assertThat(IpTraitsHandler.extract(request))
        .isEqualTo("asn=15169, country=US, crawler=google:bot");
    assertThat(request.headers().get(IpTraitsHandler.COUNTRY_HEADER)).isEqualTo("US");
    assertThat(request.headers().get(IpTraitsHandler.ASN_HEADER)).isEqualTo("15169");
    assertThat(lookedUp).containsExactly(CLIENT.getAddress());
  }

  @Test
  public void countryOnly() {
    EmbeddedChannel channel = channel(CLIENT, (builder, address) -> builder.isoCountry("DE"));
    HttpRequest request = read(channel, request());
    assertThat(IpTraitsHandler.extract(request)).isEqualTo("country=DE");
    assertThat(request.headers().contains(IpTraitsHandler.ASN_HEADER)).isFalse();
  }

  @Test
  public void crawlerOnly() {
    EmbeddedChannel channel = channel(CLIENT, (builder, address) -> builder.crawlerName("unknown"));
    assertThat(IpTraitsHandler.extract(read(channel, request()))).isEqualTo("crawler=unknown");
  }

  /// Nothing known about the address, the traits header is sent but empty.
  @Test
  public void nothingKnown() {
    EmbeddedChannel channel = channel(CLIENT, (builder, address) -> {});
    HttpRequest request = read(channel, request());
    assertThat(IpTraitsHandler.extract(request)).isEmpty();
    assertThat(request.headers().contains(IpTraitsHandler.COUNTRY_HEADER)).isFalse();
    assertThat(request.headers().contains(IpTraitsHandler.ASN_HEADER)).isFalse();
  }

  /// Looked up once per connection, not per request.
  @Test
  public void lookupOncePerConnection() {
    EmbeddedChannel channel = channel(CLIENT, (builder, address) -> builder.isoCountry("DE"));
    read(channel, request());
    HttpRequest second = read(channel, request());
    assertThat(IpTraitsHandler.extract(second)).isEqualTo("country=DE");
    assertThat(lookedUp).hasSize(1);
  }

  @Test
  public void noInetAddressNoLookup() {
    EmbeddedChannel channel =
        new EmbeddedChannel(
            new IpTraitsHandler(IpTraitsLookup.fromCollector((b, a) -> lookedUp.add(a))));
    HttpRequest request = read(channel, request());
    assertThat(IpTraitsHandler.extract(request)).isNull();
    assertThat(lookedUp).isEmpty();
  }

  /// Headers sent by the client are replaced or removed, so a client cannot pass as a crawler.
  @Test
  public void clientSuppliedHeadersAreNotForwarded() {
    for (IpTraitsCollector lookup :
        new IpTraitsCollector[] {(b, a) -> {}, (b, a) -> b.asn(1).isoCountry("DE")}) {
      HttpRequest forged = request();
      forged.headers().set(IpTraitsHandler.TRAITS_HEADER, "crawler=google:googlebot");
      forged.headers().set(IpTraitsHandler.COUNTRY_HEADER, "US");
      forged.headers().set(IpTraitsHandler.ASN_HEADER, "15169");
      HttpRequest request = read(channel(CLIENT, lookup), forged);
      assertThat(request.headers().getAll(IpTraitsHandler.TRAITS_HEADER))
          .singleElement()
          .asString()
          .doesNotContain("crawler");
      assertThat(request.headers().getAll(IpTraitsHandler.COUNTRY_HEADER))
          .doesNotContain("US")
          .hasSizeLessThanOrEqualTo(1);
      assertThat(request.headers().getAll(IpTraitsHandler.ASN_HEADER))
          .doesNotContain("15169")
          .hasSizeLessThanOrEqualTo(1);
    }
    HttpRequest forged = request();
    forged.headers().set(IpTraitsHandler.TRAITS_HEADER, "crawler=google:googlebot");
    EmbeddedChannel noAddress =
        new EmbeddedChannel(new IpTraitsHandler(IpTraitsLookup.fromCollector((b, a) -> {})));
    assertThat(IpTraitsHandler.extract(read(noAddress, forged))).isNull();
  }
}
