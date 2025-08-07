package org.sensepitch.edge;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
import io.netty.channel.Channel;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Jens Wilke
 */
public class DownstreamProgress {

  static ProxyLogger LOG = ProxyLogger.get(DownstreamProgress.class);

  static Map<String, String> MAP = new ConcurrentHashMap<>();

  static {
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    Runnable task =
        () -> {
          print();
        };
    scheduler.scheduleAtFixedRate(task, 0, 10, TimeUnit.SECONDS);
  }

  static String localChannelId(Channel ch) {
    if (ch == null) {
      return "null";
    }
    return ch.id().asShortText();
  }

  public static void progress(Channel channel, String txt) {
    Objects.requireNonNull(channel);
    MAP.put(localChannelId(channel), txt);
  }

  public static void complete(Channel channel) {
    Objects.requireNonNull(channel);
    MAP.remove(localChannelId(channel));
  }

  public static void inactive(Channel channel) {
    Objects.requireNonNull(channel);
    String text = MAP.remove(localChannelId(channel));
    if (text != null) {
      LOG.error(
          channel,
          "Channel " + localChannelId(channel) + " is inactive, but still in progress: " + text);
      new Exception().printStackTrace();
    }
  }

  private static void print() {
    boolean memoreyStats = false;
    if (memoreyStats) {
      List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
      for (BufferPoolMXBean pool : pools) {
        System.out.printf(
            "name=%s,count=%d, used=%,d bytes, capacity=%,d bytes%n",
            pool.getName(), pool.getCount(), pool.getMemoryUsed(), pool.getTotalCapacity());
      }
      // TODO: export via prometheus
      var pool = ByteBufAllocator.DEFAULT;
      if (pool instanceof ByteBufAllocatorMetricProvider metrics) {
        System.err.println(
            "ByteBufAllocator.DEFAULT, usedDirectMemory="
                + metrics.metric().usedDirectMemory()
                + ", usedHeapMemory="
                + metrics.metric().usedHeapMemory());
      } else {
        System.err.println("ByteBufAllocator.DEFAULT, does not support metrics");
      }
    }
    if (MAP.size() == 0) {
      return;
    }
    System.err.println("Requests in flight: " + MAP.size());
    int count = 10;
    for (Map.Entry<String, String> entry : MAP.entrySet()) {
      System.err.println(entry.getKey() + ": " + entry.getValue());
      if (count-- <= 0) {
        System.err.println("--- truncated ---");
        break;
      }
    }
  }
}
