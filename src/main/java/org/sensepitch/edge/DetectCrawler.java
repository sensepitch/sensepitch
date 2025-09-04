package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * @author Jens Wilke
 */
public class DetectCrawler implements BypassCheck {

  private final Map<String, BypassCheck> agentMatch = new HashMap<>();
  private final Map<String, BypassCheck> fragmentAgentMatch = new HashMap<>();

  static final BypassCheck AGENT_MATCH_BYPASS =
      (ctx, request) -> {
        BypassCheck.setBypassReason(request, "crawler-agent-match");
        return true;
      };

  static final BypassCheck FRAGMENT_AGENT_MATCH_BYPASS =
      (ctx, request) -> {
        BypassCheck.setBypassReason(request, "crawler-fragment-agent-match");
        return true;
      };

  public DetectCrawler(DetectCrawlerConfig cfg) {
    if (!cfg.disableDefault()) {
      try {
        readTsv(this.getClass().getResourceAsStream("/crawlers.tsv"));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    if (cfg.crawlerTsv() != null) {
      try {
        readTsv((new FileInputStream(cfg.crawlerTsv())));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  int readTsv(InputStream input) throws IOException {
    int count = 0;
    try (Reader in = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      CSVFormat format =
          CSVFormat.TDF
              .builder()
              .setIgnoreEmptyLines(true)
              .setHeader()
              .setSkipHeaderRecord(true)
              .get();
      CSVParser parser = new CSVParser(in, format);
      for (CSVRecord record : parser) {
        try {
          String agent = record.get("agent-match");
          agentMatch.put(agent, AGENT_MATCH_BYPASS);
          String fragment = record.get("fragment-agent-match");
          if (fragment != null && !fragment.isEmpty()) {
            fragmentAgentMatch.put(fragment, FRAGMENT_AGENT_MATCH_BYPASS);
          }
          count++;
        } catch (Exception e) {
          throw new RuntimeException("Error reading TSV row " + count, e);
        }
      }
    }
    return count;
  }

  @Override
  public boolean allowBypass(Channel channel, HttpRequest request) {
    String agent = request.headers().get(HttpHeaderNames.USER_AGENT);
    if (agent == null) {
      return false;
    }
    BypassCheck bypassCheck = agentMatch.get(agent);
    if (bypassCheck != null && bypassCheck.allowBypass(channel, request)) {
      request.headers().set(DeflectorHandler.TRAFFIC_FLAVOR_HEADER, DeflectorHandler.FLAVOR_CRAWLER);
      return true;
    }
    String ipLabels = IpTraitsHandler.extract(request);
    if (ipLabels != null && ipLabels.contains("crawler")) {
      request.headers().set(DeflectorHandler.TRAFFIC_FLAVOR_HEADER, DeflectorHandler.FLAVOR_CRAWLER);
      return true;
    }
    for (Map.Entry<String, BypassCheck> entry : fragmentAgentMatch.entrySet()) {
      if (agent.contains(entry.getKey()) && entry.getValue().allowBypass(channel, request)) {
        request.headers().set(DeflectorHandler.TRAFFIC_FLAVOR_HEADER, DeflectorHandler.FLAVOR_CRAWLER);
        return true;
      }
    }
    return false;
  }
}
