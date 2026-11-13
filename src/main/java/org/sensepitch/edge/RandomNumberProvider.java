package org.sensepitch.edge;

import java.util.concurrent.ThreadLocalRandom;

/// Source of random numbers, replaceable in tests, e.g. for the jitter of a [Backoff].
///
/// @author Jens Wilke
@FunctionalInterface
public interface RandomNumberProvider {

  /// Uses [ThreadLocalRandom], no contention between threads
  RandomNumberProvider THREAD_LOCAL = bound -> ThreadLocalRandom.current().nextLong(bound);

  /// @param bound exclusive upper bound, positive
  /// @return a random value in `[0, bound)`
  long random(long bound);
}
