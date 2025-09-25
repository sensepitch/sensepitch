package org.sensepitch.edge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Jens Wilke
 */
public class EnvInjector {

  public static Object injectFromEnv(String prefix, Map<String, String> env, Object targetBuilder)
      throws Exception {
    Class<?> clazz = targetBuilder.getClass();
    for (Method method : clazz.getMethods()) {
      if (!isEligible(method)) {
        continue;
      }
      String methodName = method.getName();
      String envName = prefix + toEnvVarName(methodName);
      Object value;
      Class<?> paramType = method.getParameterTypes()[0];
      if (paramType.isRecord() && hasSettingsWithPrefix(env, envName)) {
        Method builderMethod = paramType.getMethod("builder");
        Object nestedTarget = builderMethod.invoke(null);
        value = injectFromEnv(envName + "_", env, nestedTarget);
      } else if (paramType.isAssignableFrom(List.class)) {
        List<Object> list = handleList(env, method, envName);
        if (list.isEmpty()) continue;
        value = list;
      } else if (paramType.isAssignableFrom(Map.class)) {
        Map<String, Object> map = handleMap(env, method, envName);
        if (map.isEmpty()) continue;
        value = map;
      } else {
        String envValue = env.get(envName);
        if (envValue == null) {
          continue;
        }
        value = parseValue(envValue, paramType);
      }
      try {
        method.invoke(targetBuilder, value);
      } catch (Exception e) {
        throw new RuntimeException(
            "Failed to inject env var " + envName + " into " + methodName, e);
      }
    }
    return clazz.getMethod("build").invoke(targetBuilder);
  }

  private static List<Object> handleList(Map<String, String> env, Method method, String envName)
      throws Exception {
    ParameterizedType pt = (ParameterizedType) method.getGenericParameterTypes()[0];
    Class targetType = (Class) pt.getActualTypeArguments()[0];
    List<Object> list = new ArrayList<>();
    if (targetType.equals(String.class) && env.containsKey(envName)) {
      String[] sa = env.get(envName).split(",");
      list.addAll(Arrays.asList(sa));
    } else {
      if (targetType.isRecord()) {
        Method builderMethod = targetType.getMethod("builder");
        for (int i = 0; true; i++) {
          String indexPrefix = envName + "_" + i + "_";
          if (hasSettingsWithPrefix(env, indexPrefix)) {
            Object nestedTarget = builderMethod.invoke(null);
            list.add(injectFromEnv(indexPrefix, env, nestedTarget));
          } else {
            break;
          }
        }
      } else {
        for (int i = 0; true; i++) {
          String indexPrefix = envName + "_" + i;
          String value = env.get(indexPrefix);
          if (value == null) { break; }
          list.add(parseValue(value, targetType));
        }
      }
    }
    return list;
  }

  private static Map<String, Object> handleMap(
      Map<String, String> env, Method method, String envName) throws Exception {
    ParameterizedType pt = (ParameterizedType) method.getGenericParameterTypes()[0];
    Class targetType = (Class) pt.getActualTypeArguments()[1];
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; true; i++) {
      String indexPrefix = envName + "_" + i + "_";
      if (hasSettingsWithPrefix(env, indexPrefix)) {
        Method builderMethod = targetType.getMethod("builder");
        Object nestedTargetBuilder = builderMethod.invoke(null);
        Object obj = injectFromEnv(indexPrefix, env, nestedTargetBuilder);
        if (obj instanceof HasKey objWithKey) {
          map.put(objWithKey.key(), obj);
        } else {
          throw new IllegalArgumentException("Map value needs to have key, :" + obj.getClass());
        }
      } else {
        break;
      }
    }
    return map;
  }

  private static boolean hasSettingsWithPrefix(Map<String, String> eng, String prefix) {
    for (Map.Entry<String, String> entry : eng.entrySet()) {
      if (entry.getKey().startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /** must be instance, public, one-param, non-equals */
  private static boolean isEligible(Method m) {
    return Modifier.isPublic(m.getModifiers())
        && !Modifier.isStatic(m.getModifiers())
        && m.getParameterCount() == 1
        && !"equals".equals(m.getName())
        && !"wait".equals(m.getName());
  }

  private static String toEnvVarName(String camelCase) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < camelCase.length(); i++) {
      char ch = camelCase.charAt(i);
      if (Character.isUpperCase(ch) && i > 0) {
        sb.append('_');
      }
      sb.append(Character.toUpperCase(ch));
    }
    return sb.toString();
  }

  private static Object parseValue(String str, Class<?> type) {
    if (type == int.class || type == Integer.class) {
      return Integer.parseInt(str);
    } else if (type == boolean.class || type == Boolean.class) {
      return Boolean.parseBoolean(str);
    } else if (type == String.class) {
      return str;
    }
    throw new IllegalArgumentException("Unsupported type " + type);
  }
}
