package org.sensepitch.edge;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

/// Opens a new connection to the upstream for each request. There is no connection pool, the
/// [DownstreamHandler] closes the connection after the response.
///
/// @author Jens Wilke
public class DefaultUpstream implements Upstream {

  private final String name;
  private final String target;
  private final Bootstrap bootstrap;

  /// @param name identifies the upstream within the configuration, see [UpstreamRegistry]
  public DefaultUpstream(ProxyContext ctx, String name, UpstreamConfig cfg) {
    this.name = name;
    this.target = cfg.target();
    String[] sa = cfg.target().split(":");
    int port = 80;
    String target = sa[0];
    if (sa.length > 2) {
      throw new IllegalArgumentException("Target: " + cfg.target());
    }
    if (sa.length > 1) {
      port = Integer.parseInt(sa[1]);
    }
    bootstrap =
        new Bootstrap()
            .group(ctx.eventLoopGroup())
            .channel(NioSocketChannel.class)
            // deliberately no ChannelOption.ALLOCATOR, so this uses ByteBufAllocator.DEFAULT
            // like the ingress side does, see Proxy.start()
            .option(ChannelOption.SO_KEEPALIVE, true)
            .remoteAddress(target, port);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String target() {
    return target;
  }

  void addHttpHandler(ChannelPipeline pipeline) {
    pipeline.addLast(new HttpClientCodec());
  }

  @Override
  public Future<Channel> connect(ChannelHandlerContext ingressCtx) {
    ChannelFuture upstreamFuture = connectToUpstream(ingressCtx.channel());
    Promise<Channel> promise = ingressCtx.executor().newPromise();
    upstreamFuture.addListener(
        (ChannelFutureListener)
            cf -> {
              if (cf.isSuccess()) {
                promise.setSuccess(cf.channel());
              } else {
                promise.setFailure(cf.cause());
              }
            });
    return promise;
  }

  private ChannelFuture connectToUpstream(Channel ingress) {
    Bootstrap bs =
        bootstrap
            .clone()
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  public void initChannel(SocketChannel ch) {
                    addHttpHandler(ch.pipeline());
                    ch.pipeline().addLast("forward", new ForwardHandler(ingress));
                  }
                });
    return bs.connect();
  }
}
