package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

/// @author Jens Wilke
public class DefaultBypassCheck implements BypassCheck {

  private static final ProxyLogger LOG = ProxyLogger.get(DefaultBypassCheck.class);

  private final Map<String, BypassCheck> uriMatch = new HashMap<>();
  private final PrefixTree<BypassCheck> uriPrefixList = new PrefixTree<>();
  private final Map<String, BypassCheck> remoteAddressMap = new HashMap<>();
  private final DetectCrawler detectCrawler;

  public DefaultBypassCheck(BypassConfig cfg) {
    if (cfg.uris() != null) {
      cfg.uris()
          .forEach(
              uri -> {
                if (uri.startsWith("*")) {
                  throw new UnsupportedOperationException("URI suffix not supported: " + uri);
                } else if (uri.endsWith("*")) {
                  uriPrefixList.put(
                      uri.substring(0, uri.length() - 1), BypassCheck.CONFIG_URI_MATCH_BYPASS);
                } else {
                  uriMatch.put(uri, BypassCheck.CONFIG_URI_MATCH_BYPASS);
                }
              });
    }
    if (cfg.remotes() != null) {
      for (String remote : cfg.remotes()) {
        try {
          InetAddress addr = InetAddress.getByName(remote);
          remoteAddressMap.put(addr.getHostAddress(), BypassCheck.CONFIG_IP_MATCH_BYPASS);
        } catch (UnknownHostException e) {
          // only report error, if there is a temporary DNS problem when we start,
          // we still want to start
          LOG.error("Cannot resolve bypass remote " + remote, e);
        }
      }
    }
    if (cfg.detectCrawler() != null) {
      detectCrawler = new DetectCrawler(cfg.detectCrawler());
    } else {
      detectCrawler = new DetectCrawler(DetectCrawlerConfig.builder().build());
    }
  }

  @Override
  public boolean allowBypass(Channel channel, HttpRequest request) {
    if (channel.remoteAddress() instanceof InetSocketAddress addr) {
      String remoteHost = addr.getAddress().getHostAddress();
      BypassCheck remoteCheck = remoteAddressMap.get(remoteHost);
      if (remoteCheck != null) {
        return remoteCheck.allowBypass(channel, request);
      }
    }
    if (detectCrawler != null && detectCrawler.allowBypass(channel, request)) {
      return true;
    }
    String uri = request.uri();
    BypassCheck check = uriMatch.get(uri);
    if (check != null) {
      return check.allowBypass(channel, request);
    }
    check = uriPrefixList.lookup(uri);
    return check != null && check.allowBypass(channel, request);
  }
}
