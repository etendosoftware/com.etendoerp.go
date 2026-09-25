/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usageevents;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Captures the formatted lines one class logs at a given level, for the duration of a
 * try-with-resources block. The "loss is reported, throttled" guarantee is only observable in the
 * log, so the tests assert on the lines themselves rather than on a mocked logger.
 */
final class LogCapture extends AbstractAppender implements AutoCloseable {

  private final Logger logger;
  private final List<LogEvent> events = new CopyOnWriteArrayList<>();

  private LogCapture(Class<?> source) {
    super("capture-" + source.getSimpleName() + "-" + System.nanoTime(), null, null, true,
        Property.EMPTY_ARRAY);
    this.logger = (Logger) LogManager.getLogger(source);
  }

  /** Start capturing everything {@code source} logs. */
  static LogCapture of(Class<?> source) {
    LogCapture capture = new LogCapture(source);
    capture.start();
    capture.logger.addAppender(capture);
    return capture;
  }

  @Override
  public void append(LogEvent event) {
    events.add(event.toImmutable());
  }

  /** @return the formatted messages logged at exactly {@code level}, in order */
  List<String> messages(Level level) {
    return events.stream()
        .filter(e -> e.getLevel() == level)
        .map(e -> e.getMessage().getFormattedMessage())
        .collect(Collectors.toList());
  }

  @Override
  public void close() {
    logger.removeAppender(this);
    stop();
  }
}
