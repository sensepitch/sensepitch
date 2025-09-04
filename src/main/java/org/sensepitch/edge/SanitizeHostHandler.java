package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Augments the host header and sets only well-known values.
 *
 * <p>All valid clients are expected to set a correct host header according to the host they want to
 * contact. However, clients might leave the header out or just put garbage in there. We don't want
 * to have that further polluting our systems, or do defensive coding every time the host header is
 * needed.
 *
 * @see RequestLogInfo#requestHeaderHost()
 * @author Jens Wilke
 */
@ChannelHandler.Sharable
public class SanitizeHostHandler extends ChannelInboundHandlerAdapter {

  /**
   * Alternative host that is set when the host from the client does not match any of the serviced
   * hosts.
   */
  public static final String UNKNOWN_HOST = "unknown_host";

  /** Alternative host that is set in case the host header was absent. */
  public static final String MISSING_HOST = "missing_host";

  /**
   * Alternative host that is set if we never received a header. This value is used within the
   * {@link RequestLoggingHandler} but defined here for completeness.
   */
  public static final String NIL_HOST = "nil_host";

  public static final Set<String> SPECIAL_HOSTS = Set.of(UNKNOWN_HOST, MISSING_HOST, NIL_HOST);

  private final Set<String> knownHosts;

  public SanitizeHostHandler(Collection<String> knownHosts) {
    this.knownHosts =
        knownHosts.stream().filter(s -> !SPECIAL_HOSTS.contains(s)).collect(Collectors.toSet());
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      String host = request.headers().get(HttpHeaderNames.HOST);
      if (host == null) {
        request.headers().set(HttpHeaderNames.HOST, MISSING_HOST);
      } else {
        if (host.contains(":")) {
          String[] sa = host.split(":");
          host = sa[0];
          request.headers().set(HttpHeaderNames.HOST, host);
        }
        if (!knownHosts.contains(host)) {
          request.headers().set(HttpHeaderNames.HOST, UNKNOWN_HOST);
        }
      }
    }
    super.channelRead(ctx, msg);
  }
}
