package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.annotations.Steps;
import org.junit.jupiter.api.Test;

/// Guards the way the tests use Serenity: a failing step must fail its test.
///
/// A step object injected with `@Steps` is a Serenity proxy. It records a failure and returns
/// normally, the failure is only thrown later if Serenity's reporting listener runs and matches
/// the test by name. That listener is off by default, see `test.deactivatedListeners` in the
/// `pom.xml`, and the match fails for parameterized tests even when it is on. In both cases a
/// failing step passed silently. So step objects are created with `new`: `@Step` is then a marker
/// for the reader and an assertion in a step throws at once.
///
/// @author Jens Wilke
class SerenityUsageTest {

  @Test
  void noStepObjectIsInjected() throws Exception {
    List<String> injected = new ArrayList<>();
    for (Class<?> testClass : testClasses()) {
      for (Field field : testClass.getDeclaredFields()) {
        if (field.isAnnotationPresent(Steps.class)) {
          injected.add(testClass.getSimpleName() + "." + field.getName());
        }
      }
    }
    assertThat(injected)
        .as("an injected step object swallows failures, create it with new instead of @Steps")
        .isEmpty();
  }

  /// All classes of this package in the test output directory, nested ones included
  private static List<Class<?>> testClasses()
      throws IOException, URISyntaxException, ClassNotFoundException {
    String packageName = SerenityUsageTest.class.getPackageName();
    Path directory =
        Path.of(SerenityUsageTest.class.getResource("SerenityUsageTest.class").toURI()).getParent();
    List<String> names;
    try (Stream<Path> files = Files.list(directory)) {
      names =
          files
              .map(file -> file.getFileName().toString())
              .filter(name -> name.endsWith(".class"))
              .map(name -> packageName + "." + name.substring(0, name.length() - ".class".length()))
              .toList();
    }
    List<Class<?>> classes = new ArrayList<>();
    for (String name : names) {
      classes.add(Class.forName(name, false, SerenityUsageTest.class.getClassLoader()));
    }
    assertThat(classes).as("test classes found").contains(SerenityUsageTest.class);
    return classes;
  }

  /// The step object of the canary
  public static class FailingSteps {

    @Step
    public FailingSteps then_this_fails() {
      assertThat(1).as("canary").isEqualTo(2);
      return this;
    }
  }

  /// A failing step of a step object created with `new` reaches the caller
  @Test
  void failingStepThrows() {
    FailingSteps steps = new FailingSteps();
    assertThatThrownBy(steps::then_this_fails)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("canary");
  }
}
