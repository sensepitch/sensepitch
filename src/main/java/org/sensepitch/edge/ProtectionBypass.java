package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/// @author Jens Wilke
public class ProtectionBypass {

  private static final ProxyLogger LOG = ProxyLogger.get(ProtectionBypass.class);

  private final Map<String, Boolean> uriMatch = new HashMap<>();
  private final NavigableMap<String, Boolean> uriPrefixList = new TreeMap<>();
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
        return true;
      }
    }
    String uri = request.uri();
    if (uriMatch.containsKey(uri)) {
      return true;
    }
    // the closest entry is not necessarily a prefix, e.g. "/ab" for "/ac" when "/a" and "/ab"
    // are configured, so look further down
    Map.Entry<String, Boolean> candidate = uriPrefixList.floorEntry(uri);
    while (candidate != null) {
      if (uri.startsWith(candidate.getKey())) {
        return true;
      }
      candidate = uriPrefixList.lowerEntry(candidate.getKey());
    }
    return false;
  }
}
