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

package com.etendoerp.go.schemaforge.telemetry;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sanitized backend observability event.
 */
public final class NeoTelemetryEvent {

  private final String name;
  private final Map<String, Object> properties;
  private final Instant timestamp;

  NeoTelemetryEvent(String name, Map<String, Object> properties, Instant timestamp) {
    this.name = name;
    this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    this.timestamp = timestamp;
  }

  /**
   * Returns the event name.
   *
   * @return event name
   */
  public String getName() {
    return name;
  }

  /**
   * Returns sanitized event properties.
   *
   * @return immutable property map
   */
  public Map<String, Object> getProperties() {
    return properties;
  }

  /**
   * Returns the event creation timestamp.
   *
   * @return event timestamp
   */
  public Instant getTimestamp() {
    return timestamp;
  }
}
