/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.mcp;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Test helper: captures what a class logs, to assert a secret never reaches the log (ETP-5715).
 */
final class LogCapture extends AbstractAppender implements AutoCloseable {

  private final Logger logger;
  private final List<LogEvent> events = new ArrayList<>();

  LogCapture(Class<?> source) {
    super("capture-" + source.getSimpleName(), null, null, true, Property.EMPTY_ARRAY);
    logger = (Logger) LogManager.getLogger(source);
    start();
    logger.addAppender(this);
  }

  @Override
  public void append(LogEvent event) {
    events.add(event.toImmutable());
  }

  List<LogEvent> events() {
    return events;
  }

  /** Everything logged, message and stack included, as one string. */
  String everything() {
    StringBuilder all = new StringBuilder();
    for (LogEvent event : events) {
      all.append(event.getMessage().getFormattedMessage());
      Throwable thrown = event.getThrown();
      if (thrown != null) {
        all.append(thrown.getMessage());
      }
    }
    return all.toString();
  }

  boolean anyThrown() {
    return events.stream().anyMatch(e -> e.getThrown() != null);
  }

  @Override
  public void close() {
    logger.removeAppender(this);
    stop();
  }
}
