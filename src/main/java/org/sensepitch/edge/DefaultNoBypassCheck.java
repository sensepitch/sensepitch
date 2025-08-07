package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Jens Wilke
 */
public class DefaultNoBypassCheck implements NoBypassCheck {

  private final Map<String, Object> uriMatch = new HashMap<>();

  public DefaultNoBypassCheck(NoBypassConfig cfg) {
    if (cfg.uris() != null) {
      cfg.uris()
          .forEach(
              uri -> {
                if (uri.startsWith("*") || uri.endsWith("*")) {
                  throw new IllegalArgumentException(
                      "noBypass/uri: No prefix or suffix supported: " + uri);
                }
                uriMatch.put(uri, this);
              });
    }
  }

  @Override
  public boolean skipBypass(ChannelHandlerContext ctx, HttpRequest request) {
    String uri = request.uri();
    if (uriMatch.containsKey(uri)) {
      return true;
    }
    return false;
  }
}
