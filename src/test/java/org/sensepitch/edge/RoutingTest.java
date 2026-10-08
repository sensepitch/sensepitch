package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Future;
import java.util.List;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class RoutingTest {

  static final Upstream SHOP = new NamedUpstream("shop");
  static final Upstream API = new NamedUpstream("api");
  static final Upstream API_V2 = new NamedUpstream("api-v2");
  static final Upstream SHOP_BETA = new NamedUpstream("shop-beta");
  static final Upstream API_BETA = new NamedUpstream("api-beta");
  static final Upstream SHOP_GAMMA = new NamedUpstream("shop-gamma");

  /// Same setup as the README example, plus a second override.
  final Routing<Upstream> routing =
      new Routing<>(
          SHOP,
          paths("/api/", API, "/api/v2/", API_V2),
          List.of(
              new Routing.CookieOverride<>(
                  "beta", new Routing<>(SHOP_BETA, paths("/api/", API_BETA), List.of())),
              new Routing.CookieOverride<>(
                  "gamma", new Routing<>(SHOP_GAMMA, paths(), List.of()))));

  @Test
  void pathRouting() {
    assertThat(select("/products", null)).isSameAs(SHOP);
    assertThat(select("/api/x", null)).isSameAs(API);
    assertThat(select("/api/v2/x", null)).isSameAs(API_V2);
  }

  @Test
  void overrideReplacesWholeRouting() {
    assertThat(select("/products", "beta=1")).isSameAs(SHOP_BETA);
    assertThat(select("/api/x", "beta=1")).isSameAs(API_BETA);
    // site paths are not inherited
    assertThat(select("/api/v2/x", "beta=1")).isSameAs(API_BETA);
  }

  @Test
  void cookieMatchedByNameAmongOthers() {
    assertThat(select("/products", "session=abc; beta=; other=x")).isSameAs(SHOP_BETA);
    assertThat(select("/products", "session=abc; betaX=1")).isSameAs(SHOP);
  }

  @Test
  void firstConfiguredOverrideWins() {
    assertThat(select("/products", "gamma=1; beta=1")).isSameAs(SHOP_BETA);
    assertThat(select("/products", "gamma=1")).isSameAs(SHOP_GAMMA);
  }

  private Upstream select(String uri, String cookie) {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    if (cookie != null) {
      request.headers().set(HttpHeaderNames.COOKIE, cookie);
    }
    return routing.select(request);
  }

  private static PrefixTree<Upstream> paths(Object... prefixAndUpstream) {
    PrefixTree<Upstream> tree = new PrefixTree<>();
    for (int i = 0; i < prefixAndUpstream.length; i += 2) {
      tree.put((String) prefixAndUpstream[i], (Upstream) prefixAndUpstream[i + 1]);
    }
    return tree;
  }

  record NamedUpstream(String name) implements Upstream {

    @Override
    public Future<Channel> connect(ChannelHandlerContext downstreamContext) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void release(Channel ch) {}
  }
}
