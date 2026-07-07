package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@ChannelHandler.Sharable
public class FallbackHandler extends ChannelOutboundHandlerAdapter {

    static ProxyLogger LOG = ProxyLogger.get(FallbackHandler.class);

    private final byte[] unavailableContent;

    private final byte[] errorContent;

    public FallbackHandler(FallbackConfig fallbackConfig) {
        unavailableContent = loadOrDefault(fallbackConfig.unavailablePage(), fallbackConfig.unavailableText());
        errorContent = loadOrDefault(fallbackConfig.errorPage(), fallbackConfig.errorText());
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof FullHttpResponse response) {
            int status = response.status().code();
            if (status == 503 || status == 500) {
                byte[] content = (status == 503) ? unavailableContent : errorContent;
                FullHttpResponse fallbackResponse = new DefaultFullHttpResponse(
                        response.protocolVersion(),
                        response.status(),
                        ctx.alloc().buffer().writeBytes(content)
                );
                if (response.headers().contains(HttpHeaderNames.CONNECTION)) {
                    // keep close/keep-alive
                    fallbackResponse.headers().set(HttpHeaderNames.CONNECTION, response.headers().get(HttpHeaderNames.CONNECTION));
                }
                fallbackResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                fallbackResponse.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
                ReferenceCountUtil.release(response);
                ctx.write(fallbackResponse, promise);
            } else {
                super.write(ctx, msg, promise);
            }

        } else {
            super.write(ctx, msg, promise);
        }
    }

    /**
     * Load an HTML page for the fallback response. Tries, in order:
     * <ol>
     *     <li> a file on disk at {@code path} (operator-provided override)</li>
     *     <li> a classpath resource at {@code path} (bundled default page)</li>
     *     <li> {@code defaultText} as plain UTF-8 (last-resort)</li>
     * </ol>
     */
    private static byte[] loadOrDefault(String path, String defaultText) {
        // in case a file is provided
        if (path != null) {
            try {
                Path filePath = Path.of(path);
                if (Files.isReadable(filePath)) {
                    return Files.readAllBytes(filePath);
                }
            } catch (IOException e) {
                LOG.error("Failed to read fallback page from file: " + path, e);
            }

            try (InputStream in = FallbackHandler.class.getClassLoader().getResourceAsStream(path)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            } catch (IOException e) {
                LOG.error("Failed to read fallback page from classpath: " + path, e);
            }
        }

        // in case a file is not provided or not found
        String text = defaultText != null ? defaultText : "Unknown problem occurred";
        return text.getBytes(StandardCharsets.UTF_8);
    }

}
