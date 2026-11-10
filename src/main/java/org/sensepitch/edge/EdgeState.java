package org.sensepitch.edge;

import io.netty.handler.ssl.SslContext;
import io.netty.util.Mapping;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Everything built from the configuration that a reload replaces: routing, known hosts,
/// certificates and the IP lookup. Immutable, [Proxy] swaps the complete state, and the handlers
/// read the current state once per request.
///
/// @param config the configuration the state was built from, with map keys injected
/// @param certificates description of the loaded certificates by host, for logging changes
/// @param knownHosts hosts accepted by [SanitizeHostHandler], already filtered
/// @param upstreams the upstreams in use, handed to the next state to take over unchanged ones
/// @author Jens Wilke
record EdgeState(
    ProxyConfig config,
    SiteSelector siteSelector,
    Mapping<String, SslContext> sniMapping,
    Map<String, String> certificates,
    Set<String> knownHosts,
    UnservicedHost unservicedHost,
    IpTraitsLookup ipTraitsLookup,
    UpstreamRegistry upstreams) {

  /// Builds and validates a complete state. Nothing of the previous state is modified or closed,
  /// so on an exception the previous state can stay active.
  ///
  /// @param previous the active state, or `null` at startup. Unchanged upstreams and an
  ///   unchanged IP lookup are taken over from it.
  static EdgeState build(ProxyContext ctx, ProxyConfig config, EdgeState previous) {
    UpstreamRegistry upstreams =
        new UpstreamRegistry(ctx, previous != null ? previous.upstreams : null);
    SiteSelector siteSelector = new SiteSelector(ctx, config, upstreams);
    Set<String> servicedHosts = siteSelector.getServicedHosts();
    Map<String, String> certificates = new HashMap<>();
    Mapping<String, SslContext> sniMapping =
        Proxy.initializeSniMapping(config.listen(), servicedHosts, certificates);
    UnservicedHostConfig unservicedHostConfig =
        config.unservicedHost() != null
            ? config.unservicedHost()
            : UnservicedHostConfig.builder().build();
    if (unservicedHostConfig.servicedDomains() == null) {
      unservicedHostConfig =
          unservicedHostConfig.toBuilder().servicedDomains(servicedHosts).build();
    }
    UnservicedHost unservicedHost = new UnservicedHost(unservicedHostConfig);
    Set<String> knownHosts = new HashSet<>(servicedHosts);
    if (config.listen().hosts() != null) {
      knownHosts.addAll(config.listen().hosts());
    }
    IpTraitsLookup ipTraitsLookup;
    if (previous != null && Objects.equals(previous.config.ipLookup(), config.ipLookup())) {
      ipTraitsLookup = previous.ipTraitsLookup;
    } else {
      ipTraitsLookup = buildIpTraitsLookup(config.ipLookup());
    }
    return new EdgeState(
        config,
        siteSelector,
        sniMapping,
        Map.copyOf(certificates),
        SanitizeHostHandler.filterKnownHosts(knownHosts),
        unservicedHost,
        ipTraitsLookup,
        upstreams);
  }

  private static IpTraitsLookup buildIpTraitsLookup(IpLookupConfig cfg) {
    if (cfg == null) {
      return (builder, address) -> {};
    }
    try {
      return new CombinedIpTraitsLookup(cfg);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /// Releases what the next state did not take over. Called after the swap.
  void closeReplacedBy(EdgeState next) {
    next.upstreams.closeUnusedFrom(upstreams);
    if (next.ipTraitsLookup != ipTraitsLookup && ipTraitsLookup instanceof AutoCloseable c) {
      try {
        c.close();
      } catch (Exception e) {
        ProxyLogger.get(EdgeState.class).error("Closing IP lookup failed", e);
      }
    }
  }
}
