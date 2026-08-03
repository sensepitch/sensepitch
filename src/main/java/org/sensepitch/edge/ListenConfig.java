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
    /**
     * Local IP address or hostname to bind the proxy server to.
     *
     * <p>Use {@code "0.0.0.0"} to bind on all available network interfaces (the default).
     *
     * <p><b>IPv4 example:</b> {@code "192.168.1.100"}<br>
     * <b>IPv6 example:</b> {@code "::1"} — pass the raw address without brackets. {@link
     * java.net.InetSocketAddress InetSocketAddress} handles it natively.
     *
     * <p>In YAML configuration, IPv6 addresses <b>must be quoted</b> because colons are YAML
     * special characters:
     *
     * <pre>{@code
     * listen:
     *   address: "::1"
     *   httpsPort: 17443
     * }</pre>
     *
     * Environment variable: {@code SENSEPITCH_EDGE_LISTEN_ADDRESS=192.168.1.100}
     */
    String address,
    int httpsPort) {

  public static final String DEFAULT_LETS_ENCRYPT_PREFIX = "/etc/letsencrypt/live/";
  public static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";
  public static final int DEFAULT_HTTPS_PORT = 17443;

  public static final ListenConfig DEFAULT =
      ListenConfig.builder()
          .address(DEFAULT_BIND_ADDRESS)
          .letsEncryptPrefix(DEFAULT_LETS_ENCRYPT_PREFIX)
          .httpsPort(DEFAULT_HTTPS_PORT)
          .build();
}
