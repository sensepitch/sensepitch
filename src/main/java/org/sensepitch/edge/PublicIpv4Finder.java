package org.sensepitch.edge;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Optional;

/**
 * @author Jens Wilke
 */
public class PublicIpv4Finder {

  /**
   * Returns the first IPv4 address that is • not loopback • not link-local (169.254.x.x) • not
   * site-local (10/8, 172.16/12, 192.168/16) • only on interfaces that are up and not virtual
   */
  public static Optional<Inet4Address> findFirstPublicIpv4() throws SocketException {
    //noinspection unchecked
    return (Optional<Inet4Address>)
        (Object)
            Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                // only use interfaces that are up/non-loopback/non-virtual
                .filter(
                    iface -> {
                      try {
                        //          System.out.println(iface);
                        //          iface.inetAddresses().forEach(inetAddress -> {
                        //            System.out.println("- " + inetAddress);
                        //          });
                        return iface.isUp() && !iface.isLoopback() && !iface.isVirtual();
                      } catch (SocketException e) {
                        return false;
                      }
                    })
                // flatten to all addresses on each interface
                .flatMap(iface -> Collections.list(iface.getInetAddresses()).stream())
                // keep only IPv4
                .filter(addr -> addr instanceof Inet4Address)
                // filter out private, link-local, loopback
                .filter(
                    addr ->
                        !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()
                            && !addr.isSiteLocalAddress())
                // pick the first one found
                .findFirst();
  }
}
