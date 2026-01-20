package org.sensepitch.edge;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class CombinedIpTraitsLookupTest {

  @Test
  public void test() throws IOException {
    var x = new CombinedIpTraitsLookup(IpLookupConfig.builder().build());
  }
}
