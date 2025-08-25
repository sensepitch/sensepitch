package org.sensepitch.edge.experiments;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Print some details about the configured system logger and JUL logger.
 *
 * @see LogManager#readConfiguration()
 * @author Jens Wilke
 */
public class LoggingExperiment {

  static PrintWriter output = new PrintWriter(new OutputStreamWriter(System.out), true);

  public static void main(String[] arg) {
    String cname = System.getProperty("java.util.logging.config.class");
    System.err.println("Error output");
    System.out.println("Standard output");
    output.println("java.util.logging.config.class = " + cname);
    output.println(
        "java.util.logging.config.file = " + System.getProperty("java.util.logging.config.file"));
    output.println(
        "SimpleFormatter.format = "
            + LogManager.getLogManager().getProperty("java.util.logging.SimpleFormatter.format"));
    output.println("Testing System.Logger");
    System.Logger log = System.getLogger(LoggingExperiment.class.getName());
    output.println(
        "System.getLogger(LoggingExperiment.class.getName()).getClass().getName()="
            + log.getClass().getName());
    for (System.Logger.Level level : System.Logger.Level.values()) {
      output.println("log.isLoggable(" + level + ")=" + log.isLoggable(level));
    }
    log.log(System.Logger.Level.INFO, "Log output via system logger");
    Logger logger = Logger.getLogger(LoggingExperiment.class.getName());
    logger.log(Level.INFO, "Log output via JUL logger");
  }
}
