package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * @author Jens Wilke
 */
@Builder
public record ListenConfig (
  ConnectionConfig connection,
  SslConfig ssl,
  boolean etcLetsEncrypt,
  List<String> hosts,
  List<SniConfig> sni,
  int httpsPort) { }
