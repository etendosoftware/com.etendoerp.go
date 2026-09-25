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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.payment;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;

/**
 * Collects the log events of one logger so a spec can assert on them (ETP-5047).
 *
 * <p>Log4j2's default configuration is ERROR-only in this build, so the level is pinned before
 * attaching and restored on {@link #detach()}; without that an INFO or WARN would never reach the
 * appender and a spec asserting on one would pass vacuously.
 */
final class TestLogCapture extends AbstractAppender {

  private final List<LogEvent> events = new ArrayList<>();
  private final String loggerName;
  private final Level previousLevel;

  private TestLogCapture(String loggerName, Level previousLevel) {
    super("Etp5047TestLogCapture", (Filter) null, (Layout<? extends Serializable>) null, true,
        new Property[0]);
    this.loggerName = loggerName;
    this.previousLevel = previousLevel;
  }

  static TestLogCapture attachTo(Class<?> type, Level level) {
    String name = type.getName();
    Level previous = LogManager.getLogger(name).getLevel();
    Configurator.setLevel(name, level);
    TestLogCapture appender = new TestLogCapture(name, previous);
    appender.start();
    ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(name)).addAppender(appender);
    return appender;
  }

  void detach() {
    ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerName)).removeAppender(this);
    stop();
    Configurator.setLevel(loggerName, previousLevel);
  }

  List<String> messagesAt(Level level) {
    List<String> messages = new ArrayList<>();
    synchronized (events) {
      for (LogEvent event : events) {
        if (level.equals(event.getLevel())) {
          messages.add(event.getMessage().getFormattedMessage());
        }
      }
    }
    return messages;
  }

  @Override
  public void append(LogEvent event) {
    synchronized (events) {
      events.add(event.toImmutable());
    }
  }
}
