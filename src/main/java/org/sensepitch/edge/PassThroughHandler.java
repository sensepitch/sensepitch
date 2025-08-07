package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * @author Jens Wilke
 */
@ChannelHandler.Sharable
public class PassThroughHandler extends ChannelInboundHandlerAdapter {}
