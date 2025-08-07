package org.sensepitch.edge.experiments;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;

/**
 * For every request, send a big amount of data to understand how to throttle writes when the output
 * queue is not draining fast enough.
 *
 * @author Jens Wilke
 */
public class HttpsServerWithThrottlingResponding10Mb {

  private static final int TOTAL_BYTES = 10 * 1024 * 1024;
  private static final int CHUNK_SIZE = 1 * 1024;

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
              protected void initChannel(SocketChannel ch) throws InterruptedException {
                ch.pipeline()
                    .addLast(
                        new ChannelInboundHandlerAdapter() {
                          @Override
                          public void channelWritabilityChanged(ChannelHandlerContext ctx)
                              throws Exception {
                            // System.err.println("Top received writabilityChanged " +
                            // ctx.channel().isWritable());
                            super.channelWritabilityChanged(ctx);
                          }
                        });
                ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                ch.pipeline().addLast(new HttpServerCodec());
                ch.pipeline().addLast(new HttpObjectAggregator(65536));
                ch.pipeline().addLast(new ChunkedResponseHandler());
              }
            });
    int port = 12345;
    ChannelFuture f = sb.bind(port).sync();
    System.out.println("Proxy listening on port " + port);
  }

  private static class ChunkedResponseHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    int writtenChunks = 0;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
      // 1) Build the initial HTTP response:
      HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
      HttpUtil.setTransferEncodingChunked(response, true);
      ctx.write(response);
      writeContentChunks(ctx);
    }

    private void writeContentChunks(ChannelHandlerContext ctx) {
      int numChunks = (int) Math.ceil(TOTAL_BYTES / (double) CHUNK_SIZE);
      for (; writtenChunks < numChunks; writtenChunks++) {
        int thisChunkSize = CHUNK_SIZE;
        // last chunk may be smaller
        if (writtenChunks == numChunks - 1) {
          thisChunkSize = TOTAL_BYTES - (CHUNK_SIZE * (numChunks - 1));
        }
        ByteBuf buf = ctx.alloc().buffer(thisChunkSize);
        buf.writeZero(thisChunkSize);
        HttpContent chunk = new DefaultHttpContent(buf);
        ctx.write(chunk);
        if (!ctx.channel().isWritable()) {
          System.err.println("isWritable=false, bytes written=" + (writtenChunks * CHUNK_SIZE));
          ctx.flush();
          return;
        }
      }
      ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
      if (ctx.channel().isWritable()) {
        System.err.println("isWritable=true");
        writeContentChunks(ctx);
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      cause.printStackTrace();
      ctx.close();
    }
  }
}
