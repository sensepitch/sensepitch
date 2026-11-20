package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class UpstreamRegistryTest {

  static final UpstreamConfig ONE = UpstreamConfig.builder().target("one:80").build();
  static final UpstreamConfig TWO = UpstreamConfig.builder().target("two:80").build();

  final List<TestUpstream> created = new ArrayList<>();

  UpstreamRegistry registry(UpstreamRegistry previous) {
    return new UpstreamRegistry(
        (name, cfg) -> {
          TestUpstream upstream = new TestUpstream(name);
          created.add(upstream);
          return upstream;
        },
        previous);
  }

  @Test
  void sameTargetDifferentNameIsNotShared() {
    UpstreamRegistry registry = registry(null);
    assertThat(registry.get("sites.a.upstream", ONE))
        .isNotSameAs(registry.get("sites.b.upstream", ONE));
  }

  @Test
  void duplicateNameRejected() {
    UpstreamRegistry registry = registry(null);
    registry.get("upstream", ONE);
    assertThatThrownBy(() -> registry.get("upstream", ONE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unchangedTakenOverChangedAndRemovedClosed() {
    UpstreamRegistry first = registry(null);
    Upstream kept = first.get("upstreams.kept", ONE);
    Upstream changed = first.get("upstreams.changed", ONE);
    Upstream removed = first.get("upstreams.removed", ONE);
    UpstreamRegistry second = registry(first);
    assertThat(second.get("upstreams.kept", ONE)).isSameAs(kept);
    assertThat(second.get("upstreams.changed", TWO)).isNotSameAs(changed);
    // same config at another position is a different upstream
    assertThat(second.get("upstreams.moved", ONE)).isNotSameAs(removed);
    second.closeUnusedFrom(first);
    assertThat(created)
        .filteredOn(u -> u.closed)
        .containsExactlyInAnyOrder((TestUpstream) changed, (TestUpstream) removed);
  }

  @Test
  void networkTargetsAreDistinctAndSkipUpstreamsWithoutTarget() {
    UpstreamRegistry registry = registry(null);
    ((TestUpstream) registry.get("upstreams.a", ONE)).target = "one:80";
    ((TestUpstream) registry.get("upstreams.b", ONE)).target = "one:80";
    ((TestUpstream) registry.get("upstreams.c", TWO)).target = "two:80";
    registry.get("sites.static.upstream", TWO);
    assertThat(registry.networkTargets()).containsExactlyInAnyOrder("one:80", "two:80");
  }

  @Test
  void rejectedBuildLeavesPreviousUntouched() {
    UpstreamRegistry first = registry(null);
    first.get("upstream", ONE);
    UpstreamRegistry abandoned = registry(first);
    abandoned.get("upstream", TWO);
    // nothing is closed until the swap, so the previous registry still works
    assertThat(created).noneMatch(u -> u.closed);
    assertThat(registry(first).get("upstream", ONE)).isSameAs(first.lookup("upstream"));
  }

  static class TestUpstream implements Upstream {
    final String name;
    String target;
    boolean closed;

    TestUpstream(String name) {
      this.name = name;
    }

    @Override
    public Future<Channel> connect(ChannelHandlerContext downstreamContext) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String target() {
      return target;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
