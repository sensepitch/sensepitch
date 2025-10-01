package org.sensepitch.edge;

import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * @author Jens Wilke
 */
public class CombinedIpTraitsLookupTest {

  @Test
  public void test() throws IOException {
    var x = new CombinedIpTraitsLookup(IpLookupConfig.builder().build());
  }
}
