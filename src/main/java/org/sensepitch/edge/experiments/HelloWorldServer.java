package org.sensepitch.edge.experiments;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

public class HelloWorldServer {
  private final int port;

  public HelloWorldServer(int port) {
    this.port = port;
  }

  public void start() throws InterruptedException {

    EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    try {
      ServerBootstrap b = new ServerBootstrap();
      b.group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel.class)
          .childHandler(
              new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                  ch.pipeline().addLast(new HttpServerCodec());
                  ch.pipeline().addLast(new HttpObjectAggregator(512 * 1024));
                  ch.pipeline()
                      .addLast(
                          new SimpleChannelInboundHandler<FullHttpRequest>() {
                            @Override
                            protected void channelRead0(
                                ChannelHandlerContext ctx, FullHttpRequest req) {
                              // System.out.println(req.uri());
                              FullHttpResponse response =
                                  new DefaultFullHttpResponse(
                                      HttpVersion.HTTP_1_1,
                                      HttpResponseStatus.OK,
                                      Unpooled.copiedBuffer("Hello, World!", CharsetUtil.UTF_8));
                              response
                                  .headers()
                                  .set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                              response
                                  .headers()
                                  .setInt(
                                      HttpHeaderNames.CONTENT_LENGTH,
                                      response.content().readableBytes());
                              ctx.writeAndFlush(response);
                            }

                            @Override
                            public void exceptionCaught(
                                ChannelHandlerContext ctx, Throwable cause) {
                              cause.printStackTrace();
                              ctx.close();
                            }
                          });
                }
              });

      Channel ch = b.bind(port).sync().channel();
      System.out.println("HTTP server started on port " + port);
      ch.closeFuture().sync();
    } finally {
      bossGroup.shutdownGracefully();
      workerGroup.shutdownGracefully();
    }
  }

  public static void main(String[] args) throws InterruptedException {
    int port = 7080;
    if (args.length > 0) {
      port = Integer.parseInt(args[0]);
    }
    new HelloWorldServer(port).start();
  }
}
