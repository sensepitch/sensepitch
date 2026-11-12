package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

/// @author Jens Wilke
public class ProtectionBypass {

  private static final ProxyLogger LOG = ProxyLogger.get(ProtectionBypass.class);

  private final Map<String, Boolean> uriMatch = new HashMap<>();
  private final PrefixTree<Boolean> uriPrefixList = new PrefixTree<>();
  private final Map<String, Boolean> remoteAddressMap = new HashMap<>();

  public ProtectionBypass(ProtectionBypassConfig cfg) {
    if (cfg.uris() != null) {
      cfg.uris()
          .forEach(
              uri -> {
                if (uri.startsWith("*")) {
                  throw new UnsupportedOperationException("URI suffix not supported: " + uri);
                } else if (uri.endsWith("*")) {
                  uriPrefixList.put(uri.substring(0, uri.length() - 1), true);
                } else {
                  uriMatch.put(uri, true);
                }
              });
    }
    if (cfg.remotes() != null) {
      for (String remote : cfg.remotes()) {
        try {
          InetAddress addr = InetAddress.getByName(remote);
          remoteAddressMap.put(addr.getHostAddress(), true);
        } catch (UnknownHostException e) {
          LOG.error("Cannot resolve bypass remote " + remote, e);
        }
      }
    }
  }

  public boolean allowBypass(ChannelHandlerContext ctx, HttpRequest request) {
    if (ctx.channel().remoteAddress() instanceof InetSocketAddress addr) {
      String remoteHost = addr.getAddress().getHostAddress();
      if (remoteAddressMap.containsKey(remoteHost)) {
        BypassCheck.setBypassReason(request, BypassCheck.REASON_CONFIG_IP_MATCH);
        return true;
      }
    }
    String uri = request.uri();
    if (uriMatch.containsKey(uri) || uriPrefixList.lookup(uri) != null) {
      BypassCheck.setBypassReason(request, BypassCheck.REASON_CONFIG_URI_MATCH);
      return true;
    }
    return false;
  }
}
