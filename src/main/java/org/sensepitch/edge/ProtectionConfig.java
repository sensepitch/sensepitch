package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ProtectionConfig(
  boolean disabled,
  String admissionRef,
  AdmissionConfig admission,
  CookieAdmissionConfig cookieAdmission
) { }
