package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/// Custom IP labels accompany built-in traits on each request. Built-in traits and connections
/// without a client address are covered by [IpTraitsHandlerTest].
@ExtendWith(SerenityJUnit5Extension.class)
class IpTraitsLabelsBDDTest {

  final Steps steps = new Steps();

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void customLabelsAreForwardedInKeyOrder(boolean withBuiltInTraits) {
    String expected =
        (withBuiltInTraits ? "asn=64496, country=DE, crawler=example:bot, " : "")
            + "network=hosting, risk=low";
    steps
        .given_a_lookup_with_custom_labels(withBuiltInTraits)
        .when_a_request_with_forged_labels_arrives()
        .then_traits_are(expected)
        .when_a_request_with_forged_labels_arrives()
        .then_traits_are(expected)
        .then_the_address_was_looked_up_once();
  }

  /// A label must not break the `key=value, ...` format or pose as a built-in trait.
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "crawler | google:googlebot",
        "asn | 15169",
        "country | US",
        "'' | low",
        "risk | ''",
        "risk | 'low, crawler=x'",
        "risk | low=high",
        "ri sk | low",
        "risk | 'low\u0000'"
      })
  void invalidLabelsAreRejected(String key, String value) {
    steps.when_a_label_is_added(key, value).then_it_is_rejected();
  }

  @Test
  void headerAndLabelsStayStableAfterConstruction() {
    Map<String, String> labels = new HashMap<>();
    labels.put("z", "last");
    labels.put("a", "first");
    IpTraits traits = new IpTraits(-1, null, null, labels);
    labels.put("a", "changed");
    assertThat(traits.header()).isEqualTo("a=first, z=last");
    assertThat(traits.keyValue()).containsEntry("a", "first");
    assertThatThrownBy(() -> traits.keyValue().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @AfterEach
  void releaseChannel() {
    if (steps.channel != null) {
      steps.channel.finishAndReleaseAll();
    }
  }

  public static class Steps {

    private EmbeddedChannel channel;
    private HttpRequest forwarded;
    private final AtomicInteger lookups = new AtomicInteger();
    private ThrowingCallable addLabel;

    @Step
    public Steps given_a_lookup_with_custom_labels(boolean withBuiltInTraits) {
      IpTraitsLookup lookup =
          IpTraitsLookup.fromCollector(
              (builder, address) -> {
                lookups.incrementAndGet();
                if (withBuiltInTraits) {
                  builder.asn(64496).isoCountry("DE").crawlerName("example:bot");
                }
                builder.addLabel("risk", "high");
                builder.addLabel("network", "hosting");
                builder.addLabel("risk", "low");
              });
      channel =
          new EmbeddedChannel(new IpTraitsHandler(lookup)) {
            @Override
            protected SocketAddress remoteAddress0() {
              return new InetSocketAddress("192.0.2.7", 4711);
            }
          };
      return this;
    }

    @Step
    public Steps when_a_request_with_forged_labels_arrives() {
      HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
      request.headers().add(IpTraitsHandler.TRAITS_HEADER, "network=residential");
      request.headers().add(IpTraitsHandler.TRAITS_HEADER, "risk=trusted");
      channel.writeInbound(request);
      forwarded = channel.readInbound();
      assertThat(forwarded).isSameAs(request);
      return this;
    }

    @Step
    public Steps then_traits_are(String expected) {
      assertThat(forwarded.headers().getAll(IpTraitsHandler.TRAITS_HEADER))
          .containsExactly(expected);
      return this;
    }

    @Step
    public Steps then_the_address_was_looked_up_once() {
      assertThat(lookups.get()).isEqualTo(1);
      return this;
    }

    @Step
    public Steps when_a_label_is_added(String key, String value) {
      addLabel = () -> IpTraits.builder().addLabel(key, value);
      return this;
    }

    @Step
    public Steps then_it_is_rejected() {
      assertThatThrownBy(addLabel).isInstanceOf(IllegalArgumentException.class);
      return this;
    }
  }
}
