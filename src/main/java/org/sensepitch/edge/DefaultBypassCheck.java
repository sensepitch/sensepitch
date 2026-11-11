package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/// @author Jens Wilke
public class DefaultBypassCheck implements BypassCheck {

  private static final ProxyLogger LOG = ProxyLogger.get(DefaultBypassCheck.class);

  private final Map<String, BypassCheck> uriMatch = new HashMap<>();
  private final NavigableMap<String, BypassCheck> uriPrefixList = new TreeMap<>();
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
                  uriPrefixList.put(uri.substring(0, uri.length() - 1), BypassCheck.DO_BYPASS);
                } else {
                  uriMatch.put(uri, BypassCheck.DO_BYPASS);
                }
              });
    }
    if (cfg.remotes() != null) {
      for (String remote : cfg.remotes()) {
        try {
          InetAddress addr = InetAddress.getByName(remote);
          remoteAddressMap.put(addr.getHostAddress(), BypassCheck.DO_BYPASS);
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
    // the closest entry is not necessarily a prefix, e.g. "/ab" for "/ac" when "/a" and "/ab"
    // are configured, so look further down
    Map.Entry<String, BypassCheck> candidate = uriPrefixList.floorEntry(uri);
    while (candidate != null) {
      if (uri.startsWith(candidate.getKey())) {
        return candidate.getValue().allowBypass(channel, request);
      }
      candidate = uriPrefixList.lowerEntry(candidate.getKey());
    }
    return false;
  }
}
