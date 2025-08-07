package org.sensepitch.edge;

import lombok.Builder;

/**
 * @param readTimeoutSeconds if no input is received for the specified time, the request is aborted
 *     with a status 408 response TLS handshake was completed. Only complete HTTP frames are
 *     recognized, so sending one byte does not reset the timeout. This timeout applies also for
 *     keep alive connections.
 * @param writeTimeoutSeconds if the HTTP response or chunked content is not successfully written
 *     within the specified time, the request is aborted. If the upstream server fails to send data
 *     within the timeout, the request will be aborted as well.
 * @param responseTimeoutSeconds if the HTTP response is not arriving within the defined tme, the
 *     request is aborted
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ConnectionConfig(
    int readTimeoutSeconds, int writeTimeoutSeconds, int responseTimeoutSeconds) {

  public static final ConnectionConfig DEFAULT =
      builder().readTimeoutSeconds(30).writeTimeoutSeconds(30).responseTimeoutSeconds(15).build();
}
