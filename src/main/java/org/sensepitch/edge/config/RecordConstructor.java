package org.sensepitch.edge.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;


public class RecordConstructor {

  private static final Map<String, Boolean> BOOL_VALUES = new HashMap<>();

  static {
    BOOL_VALUES.put("yes", Boolean.TRUE);
    BOOL_VALUES.put("no", Boolean.FALSE);
    BOOL_VALUES.put("true", Boolean.TRUE);
    BOOL_VALUES.put("false", Boolean.FALSE);
    BOOL_VALUES.put("on", Boolean.TRUE);
    BOOL_VALUES.put("off", Boolean.FALSE);
  }

  public static <T> T construct(Class<T> targetType, Node node) {
    if (targetType != null && targetType.isRecord() && node instanceof MappingNode mappingNode) {
      return (T) constructRecord((Class<? extends Record>) targetType, mappingNode);
    }
    throw new IllegalArgumentException("Cannot construct record of type " + targetType.getName());
  }

  static Object constructRecord(Class<? extends Record> recordClass, MappingNode node) {
    try {
      Method builderMethod = recordClass.getMethod("builder");
      Object builder = builderMethod.invoke(null);
      for (NodeTuple t : node.getValue()) {
        String key = scalarValue(t.getKeyNode());
        Node valueNode = t.getValueNode();
        Method setMethod = findMethod(builder.getClass(), key);
        Type type = setMethod.getGenericParameterTypes()[0];
        Object value = createValueObject(type, valueNode);
        setMethod.invoke(builder, value);
      }
      Method buildMethod = builder.getClass().getMethod("build");
      return buildMethod.invoke(builder);
    } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  static Object createValueObject(Type type, Node node) {
    if (type.equals(String.class)) {
      return scalarValue(node);
    } else if (type.equals(int.class)) {
      return Integer.valueOf(scalarValue(node));
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
        sequenceNode.getValue().forEach(val -> list.add(createValueObject(elementType, val)));
        return list;
      } else if (Map.class.isAssignableFrom(owner)) {
        if (!(node instanceof MappingNode mappingNode)) {
          throw new IllegalArgumentException("Mapping expected");
        }
        Type elementType = parameterizedType.getActualTypeArguments()[1];
        Map<String, Object> map = new LinkedHashMap<>();
        mappingNode.getValue().forEach(t ->
          map.put(scalarValue(t.getKeyNode()), createValueObject(elementType, t.getValueNode())));
        return map;
      }
    }
    return construct((Class) type, node);
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