package org.sensepitch.edge.experiments;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.nio.charset.Charset;

/**
 * @author Jens Wilke
 */
public class SslServer {

  public static void main(String[] args) throws Exception {
    SslContext sslContext =
        SslContextBuilder.forServer(
                new File("performance-test/ssl/nginx.crt"),
                new File("performance-test/ssl/nginx.key"))
            .clientAuth(ClientAuth.NONE)
            .build();
    EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    ServerBootstrap sb = new ServerBootstrap();
    sb.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                ch.pipeline()
                    .addLast(
                        new ChannelDuplexHandler() {
                          @Override
                          public void channelActive(ChannelHandlerContext ctx) throws Exception {
                            ByteBuf buf = Unpooled.buffer();
                            buf.writeCharSequence("HELLO\n", Charset.defaultCharset());
                            ctx.writeAndFlush(buf)
                                .addListener(
                                    future -> {
                                      if (future.isSuccess()) {
                                        System.out.println("write completed successfully");
                                      } else {
                                        System.out.println("write error: " + future.cause());
                                      }
                                    });
                            super.channelActive(ctx);
                          }
                        });
              }
            });
    int port = 12345;
    ChannelFuture f = sb.bind(port).sync();
    System.out.println("Proxy listening on port " + port);
  }
}
