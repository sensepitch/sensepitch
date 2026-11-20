package org.sensepitch.edge.config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.sensepitch.edge.ProxyEnvironment;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/// Builds config records from a YAML node via their builders, so the defaults the builders
/// preset apply to omitted keys. A record whose defaults depend on the [ProxyEnvironment] is
/// started with its `builder(ProxyEnvironment)`, see [#builder(Class, ProxyEnvironment)].
public class RecordConstructor {

  private RecordConstructor() {}

  private static final Map<String, Boolean> BOOL_VALUES = new HashMap<>();

  static {
    BOOL_VALUES.put("yes", Boolean.TRUE);
    BOOL_VALUES.put("no", Boolean.FALSE);
    BOOL_VALUES.put("true", Boolean.TRUE);
    BOOL_VALUES.put("false", Boolean.FALSE);
    BOOL_VALUES.put("on", Boolean.TRUE);
    BOOL_VALUES.put("off", Boolean.FALSE);
  }

  /// Builds the record with the defaults of the environment of this JVM
  public static <T> T construct(Class<T> targetType, Node node) {
    return construct(targetType, node, ProxyEnvironment.current());
  }

  /// Builds the record, defaults that depend on the environment are taken from `environment`
  public static <T> T construct(Class<T> targetType, Node node, ProxyEnvironment environment) {
    if (targetType != null && targetType.isRecord() && node instanceof MappingNode mappingNode) {
      return (T) constructRecord((Class<? extends Record>) targetType, mappingNode, environment);
    }
    // the value is not part of the message, it may be a secret in the old scalar form
    String found =
        node == null
            ? "nothing"
            : "a " + node.getNodeId() + " in line " + (node.getStartMark().getLine() + 1);
    throw new IllegalArgumentException(
        "Cannot construct record of type "
            + targetType.getName()
            + ": a mapping is expected, found "
            + found);
  }

  /// A builder of the record with its defaults preset: from the `DEFAULT` instance if the record
  /// has one, else `builder(ProxyEnvironment)` if the record has it, else `builder()`. Also for
  /// the environment variable loader.
  public static Object builder(Class<?> recordClass, ProxyEnvironment environment)
      throws ReflectiveOperationException {
    try {
      Field field = recordClass.getField("DEFAULT");
      return recordClass.getMethod("toBuilder").invoke(field.get(null));
    } catch (NoSuchFieldException e) {
      try {
        return recordClass.getMethod("builder", ProxyEnvironment.class).invoke(null, environment);
      } catch (NoSuchMethodException noEnvironment) {
        return recordClass.getMethod("builder").invoke(null);
      }
    }
  }

  static Object constructEmptyRecord(
      Class<? extends Record> recordClass, ProxyEnvironment environment) {
    return constructRecord(recordClass, null, environment);
  }

  static Object constructRecord(
      Class<? extends Record> recordClass, MappingNode node, ProxyEnvironment environment) {
    try {
      Object builder = builder(recordClass, environment);
      if (node != null) {
        for (NodeTuple t : node.getValue()) {
          String key = scalarValue(t.getKeyNode());
          Node valueNode = t.getValueNode();
          Method setMethod = findMethod(builder.getClass(), key);
          if (setMethod == null) {
            throw new IllegalArgumentException(
                "Unknown parameter in " + recordClass.getSimpleName() + ": " + key);
          }
          // an empty value, e.g. `null` in the dumped configuration, keeps the default
          if (Tag.NULL.equals(valueNode.getTag())) {
            continue;
          }
          Type type = setMethod.getGenericParameterTypes()[0];
          Object value = createValueObject(type, valueNode, environment);
          setMethod.invoke(builder, value);
        }
      }
      Method buildMethod = builder.getClass().getMethod("build");
      return buildMethod.invoke(builder);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  static Object createValueObject(Type type, Node node, ProxyEnvironment environment) {
    if (type.equals(String.class)) {
      return scalarValue(node);
    } else if (type.equals(int.class)) {
      return Integer.valueOf(scalarValue(node));
    } else if (type.equals(long.class) || type.equals(Long.class)) {
      return Long.valueOf(scalarValue(node));
    } else if (type.equals((boolean.class))) {
      return BOOL_VALUES.get((scalarValue(node).toLowerCase()));
    } else if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;
      Class<?> owner = (Class<?>) parameterizedType.getRawType();
      if (List.class.isAssignableFrom(owner)) {
        if (!(node instanceof SequenceNode sequenceNode)) {
          throw new IllegalArgumentException("Sequence expected");
        }
        Type elementType = parameterizedType.getActualTypeArguments()[0];
        List<Object> list = new ArrayList<>();
        sequenceNode
            .getValue()
            .forEach(val -> list.add(createValueObject(elementType, val, environment)));
        return list;
      } else if (Map.class.isAssignableFrom(owner)) {
        if (!(node instanceof MappingNode mappingNode)) {
          throw new IllegalArgumentException("Mapping expected");
        }
        Type elementType = parameterizedType.getActualTypeArguments()[1];
        Map<String, Object> map = new LinkedHashMap<>();
        mappingNode
            .getValue()
            .forEach(
                t -> {
                  Object obj;
                  if (Tag.NULL.equals(t.getValueNode().getTag())) {
                    obj = constructEmptyRecord((Class) elementType, environment);
                  } else {
                    obj = createValueObject(elementType, t.getValueNode(), environment);
                  }
                  map.put(scalarValue(t.getKeyNode()), obj);
                });
        return map;
      }
    }
    return construct((Class) type, node, environment);
  }

  static Method findMethod(Class<?> recordClass, String methodName) {
    for (Method m : recordClass.getMethods()) {
      if (m.getParameterCount() == 1 && m.getName().equals(methodName)) {
        return m;
      }
    }
    return null;
  }

  static String scalarValue(Node n) {
    return ((ScalarNode) n).getValue();
  }
}
