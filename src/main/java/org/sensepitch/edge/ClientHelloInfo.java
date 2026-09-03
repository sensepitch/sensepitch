package org.sensepitch.edge;

/// The parts of a TLS ClientHello that are needed to build a JA4 fingerprint. GREASE values are
/// already filtered out by {@link ClientHelloParser}.
///
/// @param version highest version the client offers, taken from the supported_versions extension if
///   present, otherwise the legacy version of the handshake, e.g. {@code 0x0303} for TLS 1.2
/// @param sni whether the server_name extension is present
/// @param ciphers offered cipher suites in the order sent by the client
/// @param extensions extension types in the order sent by the client
/// @param signatureAlgorithms content of the signature_algorithms extension, empty if absent
/// @param alpn first protocol of the ALPN extension, {@code null} if absent. Bytes are mapped one
///   to one to chars, so a protocol name may contain non printable characters.
public record ClientHelloInfo(
    int version,
    boolean sni,
    int[] ciphers,
    int[] extensions,
    int[] signatureAlgorithms,
    String alpn) {}
