package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.LineNumberReader;
import java.io.StringReader;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

/**
 * A fixed set of static files we serve for the challenge.
 *
 * @author Jens Wilke
 */
public class ResourceFiles {

  private final String resourcePath;
  private final Map<String, FileInfo> map = new HashMap<>();

  public ResourceFiles(String resourcePath) {
    this.resourcePath = resourcePath;
    String fileList = ResourceLoader.loadTextFile(resourcePath + "index.txt");
    LineNumberReader reader = new LineNumberReader(new StringReader(fileList));
    reader.lines().forEach(this::add);
  }

  public FileInfo getFile(String name) {
    return map.get(name);
  }

  private void add(String name) {
    byte[] ba = ResourceLoader.loadBinaryFile(resourcePath + name);
    ByteBuf buf = Unpooled.copiedBuffer(ba);
    map.put(name, new FileInfo(buf, deriveMimeType(name)));
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

  public record FileInfo(ByteBuf buf, String mimeType) { }

}
