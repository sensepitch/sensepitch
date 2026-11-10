package org.sensepitch.edge;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/// Creates the upstreams of one configuration and takes over unchanged upstreams from the
/// previous configuration, so connection pools survive a reload.
///
/// An upstream is identified by its name, which is its path in the configuration, e.g.
/// `upstreams.backend`, `upstream` or `sites.example.paths./api/*.upstream`. Upstreams are only
/// shared via a `ref` to a named upstream, never because of an identical target.
///
/// @author Jens Wilke
public class UpstreamRegistry {

  private final BiFunction<String, UpstreamConfig, Upstream> factory;
  private final Map<String, Entry> previous;
  private final Map<String, Entry> entries = new HashMap<>();

  /// @param previous registry of the active configuration, or `null` at startup
  public UpstreamRegistry(ProxyContext ctx, UpstreamRegistry previous) {
    this((name, cfg) -> new DefaultUpstream(ctx, name, cfg), previous);
  }

  UpstreamRegistry(
      BiFunction<String, UpstreamConfig, Upstream> factory, UpstreamRegistry previous) {
    this.factory = factory;
    this.previous = previous != null ? previous.entries : Map.of();
  }

  /// Returns the previous instance if name and configuration are unchanged, otherwise creates a
  /// new upstream.
  public Upstream get(String name, UpstreamConfig cfg) {
    if (entries.containsKey(name)) {
      throw new IllegalArgumentException("Duplicate upstream name: " + name);
    }
    Entry entry = previous.get(name);
    if (entry == null || !Objects.equals(entry.config, cfg)) {
      entry = new Entry(cfg, factory.apply(name, cfg));
    }
    entries.put(name, entry);
    return entry.upstream;
  }

  /// Returns the upstream with that name, or `null`
  Upstream lookup(String name) {
    Entry entry = entries.get(name);
    return entry != null ? entry.upstream : null;
  }

  /// Closes the upstreams of the previous registry that this registry did not take over.
  public void closeUnusedFrom(UpstreamRegistry previous) {
    if (previous == null) {
      return;
    }
    previous.entries.forEach(
        (name, entry) -> {
          Entry current = entries.get(name);
          if (current == null || current.upstream != entry.upstream) {
            entry.upstream.close();
          }
        });
  }

  private record Entry(UpstreamConfig config, Upstream upstream) {}
}
