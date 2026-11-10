package org.sensepitch.edge;

/// Outcome of a configuration reload.
///
/// @param applied `true` if the new configuration is active
/// @param message what happened, for a rejection the reason
/// @author Jens Wilke
public record ReloadResult(boolean applied, String message) {

  public static final ReloadResult APPLIED = new ReloadResult(true, "Configuration reloaded");

  public static ReloadResult rejected(String reason) {
    return new ReloadResult(false, reason);
  }
}
