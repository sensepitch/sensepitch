package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/// Removes the headers of the edge, see [SensepitchHeaders], from incoming requests, so a client
/// cannot pretend to be a crawler, human traffic or admitted. The handlers further down add the
/// headers with the values the edge determined.
///
/// Requests without such a header are only scanned, nothing is allocated except the Netty header
/// iterator.
///
/// @author Jens Wilke
@ChannelHandler.Sharable
public class StripEdgeHeadersHandler extends ChannelInboundHandlerAdapter {

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      strip(request.headers());
    }
    super.channelRead(ctx, msg);
  }

  static void strip(HttpHeaders headers) {
    List<CharSequence> names = null;
    Iterator<Map.Entry<CharSequence, CharSequence>> entries = headers.iteratorCharSequence();
    while (entries.hasNext()) {
      CharSequence name = entries.next().getKey();
      if (SensepitchHeaders.isEdgeHeader(name)) {
        if (names == null) {
          names = new ArrayList<>(2);
        }
        names.add(name);
      }
    }
    if (names != null) {
      for (CharSequence name : names) {
        headers.remove(name);
      }
    }
  }
}
