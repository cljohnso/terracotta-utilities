/*
 * Copyright 2023 Terracotta, Inc., a Software AG company.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.utilities.test.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * Static methods in support of logger contributions to testing.
 * This version works with <a href="http://logback.qos.ch/">Logback</a>.
 */
public final class LoggingSupport {
  private LoggingSupport() { }

  /**
   * Run the indicated task while capturing the output of the specified logger.
   * This method adds an {@link ch.qos.logback.core.Appender Appender} to the
   * specified logger and removes it on exit.
   * @param clazz the class of which the name identifies the logger to capture
   * @param minimumLevel the <i>minimum</i> level at which to capture events;
   *                     {@code Level.ERROR} is greater than {@code Level.DEBUG}
   * @param task the task to run; this task receives the <i>live</i> list of logging events as input
   */
  public static void runWithCapture(Class<?> clazz, Level minimumLevel, Consumer<List<ILoggingEvent>> task) {
    runWithCapture(clazz.getName(), minimumLevel, task);
  }

  /**
   * Run the indicated task while capturing the output of the specified logger.
   * This method adds an {@link ch.qos.logback.core.Appender Appender} to the
   * specified logger and removes it on exit.
   * @param loggerName the logger to capture
   * @param minimumLevel the <i>minimum</i> level at which to capture events;
   *                     {@code Level.ERROR} is greater than {@code Level.DEBUG}
   * @param task the task to run; this task receives the <i>live</i> list of logging events as input
   */
  public static void runWithCapture(String loggerName, Level minimumLevel, Consumer<List<ILoggingEvent>> task) {
    requireNonNull(loggerName, "loggerName");
    requireNonNull(minimumLevel, "minimumLevel");
    requireNonNull(task, "task");

    LoggerContext context = (LoggerContext)LoggerFactory.getILoggerFactory();

    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.setContext(context);

    Runnable resetLogger;
    {
      ch.qos.logback.classic.Logger logbackLogger = context.getLogger(loggerName);

      Optional<Level> oldLevel;
      // OFF > ERROR > WARN > INFO > DEBUG > TRACE > ALL
      if (!minimumLevel.isGreaterOrEqual(logbackLogger.getEffectiveLevel())) {
        oldLevel = Optional.ofNullable(logbackLogger.getLevel());
        logbackLogger.setLevel(minimumLevel);
      } else {
        oldLevel = Optional.empty();
      }

      logbackLogger.addAppender(appender);
      appender.start();

      resetLogger = () -> {
        logbackLogger.detachAppender(appender);
        appender.stop();
        oldLevel.ifPresent(logbackLogger::setLevel);
      };
    }

    try {
      task.accept(appender.list);
    } finally {
      resetLogger.run();
    }
  }
}
