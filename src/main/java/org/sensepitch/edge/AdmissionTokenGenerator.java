package org.sensepitch.edge;

/**
 * @author Jens Wilke
 */
public interface AdmissionTokenGenerator {

  /** Generate a unique, non guessable and verifiable cookie value */
  String newAdmission();

  /**
   * Check whether admission is valid. This is done for every incoming request and should be fast.
   * Interface is prepared to indicate admission expiry.
   *
   * @return {@code ADMISSION_OK} if valid, or {@code ADMISSION_INVALID}
   */
  long checkAdmission(String token);
}
