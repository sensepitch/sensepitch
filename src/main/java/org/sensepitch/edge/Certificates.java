package org.sensepitch.edge;

import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/// Describes the loaded certificates for the log, so a renewal or a changed certificate
/// assignment is visible after a configuration reload.
///
/// @author Jens Wilke
public class Certificates {

  /// Key of the certificate used when no SNI host matches
  public static final String DEFAULT = "default";

  /// @return subject, serial number and expiry of the first certificate in the file, which is the
  ///   server certificate in a chain like Let's Encrypt `fullchain.pem`, plus the file location
  static String describe(SslConfig cfg) {
    try (InputStream in = Proxy.open(cfg.certPath())) {
      X509Certificate cert =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
      return "subject="
          + cert.getSubjectX500Principal().getName()
          + ", serial="
          + cert.getSerialNumber().toString(16)
          + ", notAfter="
          + cert.getNotAfter().toInstant()
          + ", file="
          + cfg.certPath();
    } catch (IOException | CertificateException e) {
      return "unreadable: " + e + ", file=" + cfg.certPath();
    }
  }

  /// One line per host, sorted, each starting with a line break
  static String list(Map<String, String> certificates) {
    StringBuilder sb = new StringBuilder();
    for (String host : new TreeSet<>(certificates.keySet())) {
      sb.append("\n  ").append(host).append(": ").append(certificates.get(host));
    }
    return sb.toString();
  }

  /// Lines for added, removed and changed certificates, sorted by host, or an empty string if
  /// nothing changed
  static String changes(Map<String, String> previous, Map<String, String> current) {
    TreeSet<String> hosts = new TreeSet<>(previous.keySet());
    hosts.addAll(current.keySet());
    StringBuilder sb = new StringBuilder();
    for (String host : hosts) {
      String was = previous.get(host);
      String now = current.get(host);
      if (Objects.equals(was, now)) {
        continue;
      }
      if (was == null) {
        sb.append("\n  added ").append(host).append(": ").append(now);
      } else if (now == null) {
        sb.append("\n  removed ").append(host).append(": ").append(was);
      } else {
        sb.append("\n  changed ").append(host).append(": ").append(now);
        sb.append("\n    was: ").append(was);
      }
    }
    return sb.toString();
  }
}
