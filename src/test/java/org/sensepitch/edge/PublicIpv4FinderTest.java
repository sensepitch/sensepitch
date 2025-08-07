package org.sensepitch.edge;

import org.junit.jupiter.api.Test;

/**
 * @author Jens Wilke
 */
public class PublicIpv4FinderTest {

  /** Just call it. */
  @Test
  public void findPublicIpv4() throws Exception {
    var addr = PublicIpv4Finder.findFirstPublicIpv4();
  }
}
