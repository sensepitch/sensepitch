package org.sensepitch.edge;

import io.netty.channel.Channel;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * @author Jens Wilke
 */
public interface LogTarget {

  LogTarget INSTANCE = new StreamOutput(System.out);

  static String localChannelId(Channel ch) {
    if (ch == null) {
      return "null";
    }
    return ch.id().asShortText();
  }


  void log(String source, LogInfo info);

  class StreamOutput implements LogTarget {

    private final PrintStream output;

    public StreamOutput(PrintStream output) {
      this.output = output;
    }

   private static final DateTimeFormatter TSTAMP = new DateTimeFormatterBuilder()
      .appendValue(ChronoField.SECOND_OF_MINUTE, 2)   // two-digit seconds (00–59)
      .appendLiteral('.')
      .appendValue(ChronoField.MILLI_OF_SECOND, 3)   // three-digit millis (000–999)
      .toFormatter();

    String prefix() {
      String time  = LocalTime.now().format(TSTAMP);
      String tid   = Long.toString(Thread.currentThread().getId(), 36);
      return time + "/Thread#" + tid;
    }

    @Override
    public void log(String source, LogInfo record) {
      StringBuilder sb = new StringBuilder();
      sb.append(prefix());
      sb.append(" ");
      sb.append(source);
      sb.append(" ");
      boolean needSpace = false;
      if (record.channel() != null) {
        sb.append(localChannelId(record.channel()));
        needSpace = true;
      }
      if (record.downstreamChannel() != null) {
        sb.append(localChannelId(record.downstreamChannel()));
        needSpace = true;
      }
      if (record.upstreamChannel() != null) {
        sb.append('>');
        sb.append(localChannelId(record.upstreamChannel()));
        needSpace = true;
      }
      if (needSpace) {
        sb.append(' ');
        needSpace = false;
      }
      if (record.operation() != null) {
        sb.append(record.operation());
        sb.append(" ");
      }
      if (record.message() != null) {
        sb.append(record.message());
        sb.append(" ");
      }
      if (record.error() != null) {
        StringWriter writer  = new StringWriter();
        record.error().printStackTrace(new PrintWriter(writer));
        sb.append(writer.toString());
      }
      output.println(sb.toString().trim());
    }

  }

}
