package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.sensepitch.edge.config.KeyInjector;
import org.sensepitch.edge.config.RecordConstructor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * @author Jens Wilke
 */
public class ConfigTest {

  @Test
  public void readConfigFromEnvironmentLists() throws Exception {
    Map<String, String> env = Map.of("XX_TEXTS", "/xy,special,123");
    AllFieldTypesConfig cfg =
        (AllFieldTypesConfig) EnvInjector.injectFromEnv("XX_", env, AllFieldTypesConfig.builder());
    assertEquals("[/xy, special, 123]", cfg.texts().toString());
  }

  @Test
  public void readConfigFromEnvironmentForSingleObject() throws Exception {
    Map<String, String> env =
        Map.of(
            "XX_NUMBER", "123",
            "XX_FLAG", "true",
            "XX_TEXT", "hello world");
    AllFieldTypesConfig cfg =
        (AllFieldTypesConfig) EnvInjector.injectFromEnv("XX_", env, AllFieldTypesConfig.builder());
    assertEquals(123, cfg.number());
    assertEquals(true, cfg.flag());
    assertEquals("hello world", cfg.text());
  }

  @Test
  public void readConfigFromEnvironmentNestedObject() throws Exception {
    Map<String, String> env = Map.of("XX_ALL_NUMBER", "123");
    NestedTestConfig cfg =
        (NestedTestConfig) EnvInjector.injectFromEnv("XX_", env, NestedTestConfig.builder());
    assertNotNull(cfg.all());
    assertNull(cfg.list());
    assertEquals(false, cfg.enable());
    assertEquals(123, cfg.all().number());
  }

  @Test
  public void configDeflector() throws Exception {
    Map<String, String> env =
        Map.of(
            "SENSEPITCH_EDGE_PROTECTION_DEFLECTOR_TOKEN_GENERATORS_0_PREFIX", "z");
    ProxyConfig cfg =
        (ProxyConfig) EnvInjector.injectFromEnv("SENSEPITCH_EDGE_", env, ProxyConfig.builder());
    assertNotNull(cfg.protection());
  }

  @Test
  public void testReadConfigFromEnvironmentForMultipleObjects() throws Exception {
    Map<String, String> env =
        Map.of(
            "XX_LIST_0_FLAG", "true",
            "XX_LIST_1_NUMBER", "234");
    NestedTestConfig cfg =
        (NestedTestConfig) EnvInjector.injectFromEnv("XX_", env, NestedTestConfig.builder());
    assertNotNull(cfg.list());
    assertEquals(true, cfg.list().get(0).flag());
    assertEquals(false, cfg.list().get(1).flag());
    assertEquals(234, cfg.list().get(1).number());
  }

  @Test
  public void readMap() throws Exception {
    Map<String, String> env =
        Map.of(
            "XX_MAP_0_KEY", "hello",
            "XX_MAP_0_NUMBER", "234");
    NestedTestConfig cfg =
        (NestedTestConfig) EnvInjector.injectFromEnv("XX_", env, NestedTestConfig.builder());
    assertThat(cfg.map()).isNotNull();
    assertThat(cfg.map().get("hello").number()).isEqualTo(234);
  }

  @Test
  public void readAllFieldTypesFromYaml() {
    String yaml =
        """
      number: 123
      flag: true
      text: hello world
      texts:
        - one
        - two
      list:
        - number: 123
        - number: 456
      map:
        first:
          number: 1001
        second:
          number: 1002
    """;
    Yaml parser = new Yaml();
    Node root = parser.compose(new StringReader(yaml));
    // printNode(root, 2);
    AllFieldTypesConfig obj = RecordConstructor.construct(AllFieldTypesConfig.class, root);
    // System.out.println(obj);
    assertThat(obj.number()).isEqualTo(123);
    assertThat(obj.flag()).isEqualTo(true);
    assertThat(obj.text()).isEqualTo("hello world");
    assertThat(obj.texts()).hasSize(2).first().isEqualTo("one");
    assertThat(obj.list()).hasSize(2);
    assertThat(obj.list().getFirst().number()).isEqualTo(123);
    assertThat(obj.map().get("second").number()).isEqualTo(1002);
  }

  @Test
  public void injectKey() {
    String yaml =
        """
      map:
        first:
          number: 1
        second:
          number: 2
    """;
    Yaml parser = new Yaml();
    Node root = parser.compose(new StringReader(yaml));
    // printNode(root, 2);
    AllFieldTypesConfig obj = RecordConstructor.construct(AllFieldTypesConfig.class, root);
    assertThat(obj.map().get("first").key()).isNull();
    obj = KeyInjector.injectAllMapKeys(obj);
    assertThat(obj.map().get("first").key()).isEqualTo("first");
    assertThat(obj.map().get("second").key()).isEqualTo("second");
  }

  void printNode(Node node, int indent) {
    String pad = " ".repeat(indent);
    if (node instanceof MappingNode m) {
      System.out.println(pad + "MappingNode:");
      for (NodeTuple tuple : m.getValue()) {
        printNode(tuple.getKeyNode(), indent + 2);
        printNode(tuple.getValueNode(), indent + 2);
      }
    } else if (node instanceof SequenceNode s) {
      System.out.println(pad + "SequenceNode:");
      for (Node item : s.getValue()) {
        printNode(item, indent + 2);
      }
    } else if (node instanceof ScalarNode sc) {
      System.out.printf("%sScalarNode: %s (%s)%n", pad, sc.getValue(), sc.getTag());
    }
  }
}
