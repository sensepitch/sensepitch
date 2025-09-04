package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/**
 * @param ssl Default certificate to use if certificate is present that matches the host.
 * @param letsEncrypt expect keys and certificate be present in the file system using let's encrypt
 *     directory layout. It is expected that there is a key and certificate for all known host
 *     names.
 * @param letsEncryptPrefix Directory prefix for the let's encrypt keys and certificates. Default is
 *     {@value #DEFAULT_LETS_ENCRYPT_PREFIX}.
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ListenConfig(
    ConnectionConfig connection,
    SslConfig ssl,
    boolean letsEncrypt,
    String letsEncryptPrefix,
    List<String> hosts,
    List<SniConfig> snis,
    int httpsPort) {

  public static final String DEFAULT_LETS_ENCRYPT_PREFIX = "/etc/letsencrypt/live/";
  public static final int DEFAULT_HTTPS_PORT = 17443;

  public static final ListenConfig DEFAULT =
      ListenConfig.builder()
          .letsEncryptPrefix(DEFAULT_LETS_ENCRYPT_PREFIX)
          .httpsPort(DEFAULT_HTTPS_PORT)
          .build();
}
