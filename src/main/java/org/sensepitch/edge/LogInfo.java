package org.sensepitch.edge;

import io.netty.channel.Channel;
import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder
public record LogInfo(
    System.Logger.Level level,
    Channel channel,
    Channel downstreamChannel,
    Channel upstreamChannel,
    String source,
    String operation,
    String message,
    Throwable error) {}
