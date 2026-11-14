package org.sensepitch.edge;

/// The admission token is issued after passing the PoW challenge.
///
/// @author Jens Wilke
public interface AdmissionTokenGenerator {

  /// Generate a unique, non guessable and verifiable cookie value
  String newAdmission();

  /// Check whether admission is valid. This is done for every incoming request and should be fast.
  /// Interface is prepared to indicate admission expiry.
  ///
  /// @return the creation time of the token in epoch milliseconds if valid, or 0 if invalid
  long checkAdmission(String token);
}
