package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Helper to build protection handlers from config.
 *
 * @author Jens Wilke
 */
public class Protection {

  static Supplier<ChannelHandler> handlerSupplier(ProtectionConfig protection) {
    if (protection == null) {
      return null;
    }
    if (protection.disable()) {
      PassThroughHandler passThroughHandler = new PassThroughHandler();
      return () -> passThroughHandler;
    }
    List<ProtectionPlugin> plugins = new ArrayList<>();
    if (protection.cookieGates() != null) {
      CookieGate gate = new CookieGate(protection.cookieGates());
      plugins.add(gate.newPlugin());
    }
    if (protection.deflector() != null) {
      Deflector deflector = new Deflector(protection.deflector());
      plugins.add(new DeflectorHandler(deflector));
    }
    if (!plugins.isEmpty()) {
      ProtectionPlugin plugin =
          plugins.size() == 1 ? plugins.get(0) : new ProtectionChain(plugins);
      return () -> new ProtectionHandler(plugin);
    }
    return null;
  }

  static class ProtectionChain implements ProtectionPlugin {
    private final List<ProtectionPlugin> plugins;

    ProtectionChain(List<ProtectionPlugin> plugins) {
      this.plugins = List.copyOf(plugins);
    }

    @Override
    public boolean mightIntercept(HttpRequest request, ChannelHandlerContext ctx) {
      for (ProtectionPlugin plugin : plugins) {
        if (plugin.mightIntercept(request, ctx)) {
          return true;
        }
      }
      return false;
    }
  }
}
