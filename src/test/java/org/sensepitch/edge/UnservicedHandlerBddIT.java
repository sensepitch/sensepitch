package org.sensepitch.edge;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.SanitizeHostHandler.MISSING_HOST;
import static org.sensepitch.edge.SanitizeHostHandler.UNKNOWN_HOST;
import static org.sensepitch.edge.UnservicedHandler.NOT_FOUND_URI;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import java.util.List;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.annotations.Steps;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SerenityJUnit5Extension.class)
class UnservicedHandlerBddIT {

  // aliases for status codes for better readability
  static final HttpResponseStatus STATUS_400_BAD_REQUEST = HttpResponseStatus.BAD_REQUEST;
  static final HttpResponseStatus STATUS_302_FOUND = HttpResponseStatus.FOUND;

  @Steps UnservicedSteps steps;

  @Test
  void allowedHost_passesThrough() {
    steps
        .given_a_common_example_configuration()
        .when_request_to("bar.com", "/")
        .then_request_is_passed_to_next_handler();
  }

  @Test
  void missingHost_resultsInBadRequestStatus() {
    steps
        .given_a_common_example_configuration()
        .when_request_without_host_to_uri("/")
        .then_response_status_is(STATUS_400_BAD_REQUEST)
        .then_location_header_is_absent()
        .when_request_without_host_to_uri("/something")
        .then_response_status_is(STATUS_400_BAD_REQUEST)
        .then_location_header_is_absent();
  }

  @Test
  void missingHostAfterSanitize_resultsInBadRequestStatus() {
    steps
        .given_a_common_example_configuration()
        .when_request_to(MISSING_HOST, "/")
        .then_response_status_is(STATUS_400_BAD_REQUEST)
        .then_location_header_is_absent();
  }

  @Test
  void unknownHost_resultsInBadRequestStatus() {
    steps
        .given_a_common_example_configuration()
        .when_request_to(UNKNOWN_HOST, "/")
        .then_response_status_is(STATUS_400_BAD_REQUEST)
        .then_location_header_is_absent();
  }

  @Test
  void mappedHost_redirectsToMappedTarget() {
    steps
        .given_a_common_example_configuration()
        .when_request_to("foo.com", "/")
        .then_response_status_is(STATUS_302_FOUND)
        .then_location_header_is("https://www.foo.com");
  }

  @Test
  void otherHost_redirectsToDefaultTarget() {
    steps
        .given_a_common_example_configuration()
        .when_request_to("other.com", "/")
        .then_response_status_is(STATUS_302_FOUND)
        .then_location_header_is("https://default.example");
  }

  @Test
  void otherHostWithUri_redirectsToDefaultTarget() {
    steps
        .given_a_common_example_configuration()
        .when_request_to("other.com", "/anything")
        .then_response_status_is(STATUS_302_FOUND)
        .then_location_header_is("https://default.example" + NOT_FOUND_URI);
  }

  @Test
  void secondRequestWorks() {
    steps
        .given_a_common_example_configuration()
        .when_request_without_host_to_uri("/")
        .then_response_status_is(BAD_REQUEST)
        .then_location_header_is_absent()
        .when_request_to("foo.com", "/")
        .then_response_status_is(FOUND)
        .then_location_header_is("https://www.foo.com");
  }

  public static class UnservicedSteps {

    private EmbeddedChannel channel;
    private HttpRequest request;
    private boolean passed;
    private Object passedMessage;
    private HttpResponse response;

    @Step(
        "When has common configuration: "
            + "passDomains=www.foo.com,bar.com,www.baz.com; defaultTarget=https://default.example")
    public UnservicedSteps given_a_common_example_configuration() {
      RedirectConfig cfg =
          RedirectConfig.builder()
              .passDomains(List.of("www.foo.com", "bar.com", "www.baz.com"))
              .defaultTarget("https://default.example")
              .build();
      channel = new EmbeddedChannel(new UnservicedHandler(cfg));
      return this;
    }

    @Step
    public UnservicedSteps when_request_to(String host, String uri) {
      request = new DefaultHttpRequest(HTTP_1_1, GET, uri);
      request.headers().set(HttpHeaderNames.HOST, host);
      passed = channel.writeInbound(request);
      if (passed) {
        passedMessage = channel.readInbound();
      } else {
        response = (HttpResponse) channel.readOutbound();
        consumeIngressContent();
      }
      return this;
    }

    @Step
    public UnservicedSteps when_request_without_host_to_uri(String uri) {
      request = new DefaultHttpRequest(HTTP_1_1, GET, uri);
      passed = channel.writeInbound(request);
      response = (HttpResponse) channel.readOutbound();
      consumeIngressContent();
      return this;
    }

    @Step
    public UnservicedSteps then_request_is_passed_to_next_handler() {
      assertThat(passed).isTrue();
      assertThat(passedMessage).isSameAs(request);
      return this;
    }

    @Step
    public UnservicedSteps then_response_status_is(HttpResponseStatus status) {
      assertThat(response.status()).isEqualTo(status);
      return this;
    }

    @Step
    public UnservicedSteps then_location_header_is_absent() {
      assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isNull();
      return this;
    }

    @Step
    public UnservicedSteps then_location_header_is(String expected) {
      assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isEqualTo(expected);
      return this;
    }

    private void consumeIngressContent() {
      HttpContent chunk = new DefaultHttpContent(Unpooled.buffer());
      channel.writeInbound(chunk);
      assertThat(chunk.refCnt()).isZero();
      HttpContent last = new DefaultLastHttpContent(Unpooled.buffer());
      channel.writeInbound(last);
      assertThat(last.refCnt()).isZero();
    }
  }
}
