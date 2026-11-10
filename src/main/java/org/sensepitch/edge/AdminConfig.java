package org.sensepitch.edge;

import lombok.Builder;

/// Admin HTTP endpoint, see [AdminServer]. Present in the configuration means enabled, in YAML
/// `admin: {}` enables it with the defaults.
///
/// @param address address to bind to. Default is {@value #DEFAULT_ADDRESS}, so only local
///   processes can reach it. Binding another address exposes the endpoint to that network.
/// @param port port to listen on, default is {@value #DEFAULT_PORT}
/// @author Jens Wilke
@Builder(toBuilder = true)
public record AdminConfig(String address, int port) {

  public static final String DEFAULT_ADDRESS = "127.0.0.1";
  public static final int DEFAULT_PORT = 9401;
}
