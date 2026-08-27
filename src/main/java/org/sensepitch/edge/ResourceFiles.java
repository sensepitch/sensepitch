package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.File;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/// A fixed set of static files we serve for the challenge.
///
/// @author Jens Wilke
public class ResourceFiles {

  private final Map<String, FileInfo> map = new HashMap<>();

  public ResourceFiles(String resourcePath) {
    ResourceLoader.getFileList(resourcePath).forEach(this::add);
  }

  public FileInfo getFile(String name) {
    return map.get(name);
  }

  public Set<String> getFileNames() {
    return map.keySet();
  }

  private void add(String name) {
    byte[] ba = ResourceLoader.loadBinaryFile(name);
    // direct, because BoringSSL needs the plaintext in direct memory: serving this from a heap
    // buffer would copy it into a fresh direct buffer on every response, see SslHandler.wrap().
    // Unpooled, because we hold it for the lifetime of the process and must not pin a chunk of
    // the pooling allocator.
    ByteBuf buf = Unpooled.directBuffer(ba.length, ba.length).writeBytes(ba);
    map.put(new File(name).getName(), new FileInfo(buf, deriveMimeType(name)));
  }

  private String deriveMimeType(String fileName) {
    String mimeType = URLConnection.guessContentTypeFromName(fileName);
    if (mimeType == null) {
      throw new IllegalArgumentException("unknown mime type for: " + fileName);
    }
    mimeType = mimeType.toLowerCase();
    if (mimeType.startsWith("text/")) {
      return mimeType + "; charset=utf-8";
    }
    return mimeType;
  }

  public record FileInfo(ByteBuf buf, String mimeType) {}
}
