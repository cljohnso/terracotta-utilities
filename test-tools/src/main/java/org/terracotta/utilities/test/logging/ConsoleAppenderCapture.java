/*
 * Copyright 2022 Terracotta, Inc., a Software AG company.
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

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.filter.AbstractMatcherFilter;
import ch.qos.logback.core.joran.spi.ConsoleTarget;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * Captures output associated with the current thread logged to each active {@link ConsoleAppender}
 * instance in the current logging environment.
 */
public class ConsoleAppenderCapture implements AutoCloseable {
  private static final String MDC_KEY_ROOT = ConsoleAppenderCapture.class.getSimpleName() + ".testId";

  private final String testId;
  private final String mdcKey = MDC_KEY_ROOT + '.' + UUID.randomUUID();
  private final Runnable removeAppenders;
  private final Map<String, ListAppender<ILoggingEvent>> appenderMap = new HashMap<>();

  /**
   * Creates a new {@code ConsoleAppenderCapture} instance for {@code testId}.
   * <p>
   * The returned {@code ConsoleAppenderCapture} instance <b>must</b> be closed when no longer needed.
   * Use of a try-with-resource block is recommended: <pre>{@code
   *   try (ConsoleAppenderCapture appenderCapture = ConsoleAppenderCapture.capture("identifier")) {
   *     // invocation of code under test using ConsoleAppender
   *     List<String> logCapture = appenderCapture.getMessages(ConsoleAppenderCapture.Target.STDOUT);
   *     assertThat(logCapture, Matcher...);
   *   }
   * }</pre>
   *
   * @param testId the identifier for which events are captured
   * @return a new {@code ConsoleAppenderCapture} instance
   * @throws IllegalStateException if the current logging configuration does not append to {@link ConsoleAppender}
   * @see ConsoleAppenderCapture#ConsoleAppenderCapture(String)  ConsoleAppenderCapture
   */
  public static ConsoleAppenderCapture capture(String testId) {
    ConsoleAppenderCapture cac = new ConsoleAppenderCapture(testId);
    if (cac.appenderMap.isEmpty()) {
      cac.close();
      throw new IllegalStateException("Current logging configuration does not use " + ConsoleAppender.class.getName());
    }
    return cac;
  }

  /**
   * Creates a new {@code ConsoleAppenderCapture} instance.
   * <p>
   * The returned {@code ConsoleAppenderCapture} instance <b>must</b> be closed when no longer needed.
   * Use of a try-with-resource block is recommended: <pre>{@code
   *   try (ConsoleAppenderCapture appenderCapture = ConsoleAppenderCapture.capture()) {
   *     // invocation of code under test using ConsoleAppender
   *     List<String> logCapture = appenderCapture.getMessages(ConsoleAppenderCapture.Target.STDOUT);
   *     assertThat(logCapture, Matcher...);
   *   }
   * }</pre>
   *
   * @return a new {@code ConsoleAppenderCapture} instance
   * @throws IllegalStateException if the current logging configuration does not append to {@link ConsoleAppender}
   * @see ConsoleAppenderCapture#ConsoleAppenderCapture(String)  ConsoleAppenderCapture
   */
  public static ConsoleAppenderCapture capture() {
    return ConsoleAppenderCapture.capture(null);
  }

  /**
   * Creates a {@code ConsoleAppenderCapture} instance which captures events sent to each {@code Logger}
   * in the current Logback configuration having a {@link ConsoleAppender}.  Only events logged by threads
   * for which the {@link MDC} contains a key generated by this constructor are captured.  If multiple
   * {@code ConsoleAppender} instances are used (uncommon), the captures from all instances having the
   * same target are aggregated.
   * <p>
   * If the current logging configuration does not append to {@code ConsoleAppender}, no capture is
   * performed and {@link #getLogs()} will return an empty map.
   * <p>
   * If a {@code Logger}, through inheritance, appends to multiple instances of {@code ConsoleAppender},
   * then multiple event captures may be observed.
   * <p>
   * The {@code ConsoleAppenderCapture} instance <b>must</b> be closed when no longer needed -- use
   * try-with-resources.
   *
   * @param testId used as the value for the generated {@code MDC} key; if {@code null}, a value of
   *               {@code present} is used
   */
  public ConsoleAppenderCapture(String testId) {
    this.testId = (testId == null ? "present" : testId);

    MDC.put(mdcKey, this.testId);
    Runnable removeAppenders = () -> {
    };
    LoggerContext context = (LoggerContext)LoggerFactory.getILoggerFactory();

    /*
     * Scan for all loggers using the ConsoleAppender and add an appender to capture
     * the output for the designated ConsoleAppender target.
     */
    for (ch.qos.logback.classic.Logger logger : context.getLoggerList()) {
      Iterator<Appender<ILoggingEvent>> appenderIterator = logger.iteratorForAppenders();
      while (appenderIterator.hasNext()) {
        Appender<ILoggingEvent> iLoggingEventAppender = appenderIterator.next();
        if (iLoggingEventAppender instanceof ConsoleAppender) {
          String target = ((ConsoleAppender<?>)iLoggingEventAppender).getTarget();
          ListAppender<ILoggingEvent> appender = appenderMap.computeIfAbsent(target, t -> {
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.setContext(context);

            listAppender.addFilter(new AbstractMatcherFilter<ILoggingEvent>() {
              @Override
              public FilterReply decide(ILoggingEvent iLoggingEvent) {
                if (iLoggingEvent.getMDCPropertyMap().getOrDefault(mdcKey, "").equals(ConsoleAppenderCapture.this.testId)) {
                  return FilterReply.ACCEPT;
                } else {
                  return FilterReply.DENY;      // Filter only applies to 'this' Appender -- can stop processing here
                }
              }
            });

            listAppender.start();
            return listAppender;
          });
          logger.addAppender(appender);
          Runnable r = removeAppenders;
          removeAppenders = () -> {
            r.run();
            logger.detachAppender(appender);
            appender.stop();
          };
          break;
        }
      }
    }

    this.removeAppenders = removeAppenders;
  }

  /**
   * Gets a reference to the {@code List}s into which log entries are captured.
   *
   * @return the map of logging target to log entry list reference; the key of the
   *      map is the {@link ConsoleAppender#getTarget()} value for which
   *      {@link Target#targetName()} value may be used
   * @see Target
   */
  public Map<String, List<ILoggingEvent>> getLogs() {
    return appenderMap.entrySet().stream()
        .collect(toMap(Map.Entry::getKey, e -> e.getValue().list));
  }

  /**
   * Gets the events, in formatted string form, logged to the specified target.
   * @param target the target for which log messages are to be returned
   * @return the list of messages logged to {@code target}; may be empty
   */
  public List<String> getMessages(Target target) {
    requireNonNull(target, "target");
    return Optional.ofNullable(appenderMap.get(target.targetName()))
        .map(a -> a.list).orElse(emptyList())
        .stream().map(ILoggingEvent::getFormattedMessage).collect(toList());
  }

  /**
   * Gets the events, in formatted string form, as a single string with events
   * separated by {@link System#lineSeparator()}.
   * @param target the target for which log messages are to be returned
   * @return a string containing all messages logged to {@code target}; may be empty
   */
  public String getMessagesAsString(Target target) {
    requireNonNull(target, "target");
    return Optional.ofNullable(appenderMap.get(target.targetName()))
        .map(a -> a.list).orElse(emptyList())
        .stream().map(ILoggingEvent::getFormattedMessage).collect(joining(System.lineSeparator()));
  }

  /**
   * Detaches and stops the capturing appenders.
   */
  @Override
  public void close() {
    removeAppenders.run();
    MDC.remove(mdcKey);
  }

  /**
   * Logging targets for {@link ConsoleAppender}.
   * @see ch.qos.logback.core.joran.spi.ConsoleTarget
   */
  public enum Target {
    /** {@code System.out} target. */
    STDOUT(ConsoleTarget.SystemOut),
    /** {@code System.err} target. */
    STDERR(ConsoleTarget.SystemErr);

    private final ConsoleTarget target;

    Target(ConsoleTarget target) {
      this.target = target;
    }

    public String targetName() {
      return this.target.getName();
    }
  }
}