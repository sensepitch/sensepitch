package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.File;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A fixed set of static files we serve for the challenge.
 *
 * @author Jens Wilke
 */
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
    ByteBuf buf = Unpooled.copiedBuffer(ba);
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
