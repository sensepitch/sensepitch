package org.sensepitch.edge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/// Measures what the JA4 fingerprint costs per TLS connection.
///
/// <p>[Ja4Handler] removes itself from the pipeline once the ClientHello is seen, so the cost is
/// paid once per connection and not once per request. That makes it too small to see reliably in a
/// proxy level load test, where a single RSA handshake is orders of magnitude more expensive. This
/// benchmark measures the parse and the fingerprint on their own, so the load test result can be
/// stated as a number rather than as "no measurable difference".
///
/// <p>Not a test, surefire ignores it. Run it after `mvn test-compile` with:
///
/// ```
/// mvn -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/cp.txt
/// java -cp target/classes:target/test-classes:$(cat target/cp.txt) org.sensepitch.edge.Ja4Benchmark
/// ```
///
/// @see Ja4Handler
/// @see ClientHelloParser
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class Ja4Benchmark {

  /// A real ClientHello, kept in a heap buffer because that is what the pipeline hands to
  /// [Ja4Handler] before TLS is terminated.
  private ByteBuf clientHello;

  @Setup
  public void setup() throws Exception {
    clientHello = captureClientHello();
    Ja4Fingerprint fingerprint = Ja4Handler.compute(clientHello);
    if (fingerprint == null) {
      throw new IllegalStateException("captured bytes are not a parsable ClientHello");
    }
    System.out.println(
        "ClientHello: " + clientHello.readableBytes() + " bytes, ja4=" + fingerprint.value());
  }

  @TearDown
  public void tearDown() {
    clientHello.release();
  }

  /// Cost of reading the ClientHello, without building the fingerprint from it.
  @Benchmark
  public ClientHelloInfo parse() {
    return ClientHelloParser.parse(clientHello);
  }

  /// Everything the handler does per connection: parse plus the two hashes of the fingerprint.
  @Benchmark
  public Ja4Fingerprint fingerprint() {
    return Ja4Handler.compute(clientHello);
  }

  /// Drives a client side [io.netty.handler.ssl.SslHandler] far enough to produce a ClientHello and
  /// takes the bytes it writes. Using a generated one rather than a recorded byte array keeps the
  /// input honest, at the cost of it being a JDK ClientHello and not a browser one. A browser sends
  /// more extensions, so the numbers here are a lower bound.
  static ByteBuf captureClientHello() throws Exception {
    SslContext context =
        SslContextBuilder.forClient()
            .sslProvider(SslProvider.JDK)
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocolConfig(
                new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2,
                    ApplicationProtocolNames.HTTP_1_1))
            .build();
    EmbeddedChannel channel =
        new EmbeddedChannel(context.newHandler(ByteBufAllocator.DEFAULT, "localhost", 443));
    try {
      // the handshake starts as soon as the channel is active, the first thing written out is the
      // ClientHello, possibly spread over several buffers
      ByteBuf collected = Unpooled.buffer();
      ByteBuf out;
      while ((out = channel.readOutbound()) != null) {
        collected.writeBytes(out);
        out.release();
      }
      if (collected.readableBytes() == 0) {
        throw new IllegalStateException("no ClientHello was written");
      }
      return collected;
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  public static void main(String[] args) throws Exception {
    new Runner(new OptionsBuilder().include(Ja4Benchmark.class.getSimpleName()).build()).run();
  }
}
