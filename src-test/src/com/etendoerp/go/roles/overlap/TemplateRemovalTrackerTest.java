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
package com.etendoerp.go.roles.overlap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain, DB-free unit tests for {@link TemplateRemovalTracker} — a ThreadLocal-based marker
 * tracking template role ids currently being removed via an in-flight {@code RoleInheritance}
 * deletion within the current transaction (ETP-4830 item 7). Extracted from {@code
 * WindowAccessOverlapCorruptionGuard}'s own private {@code TEMPLATES_BEING_REMOVED} field so
 * {@code ProcessAccessOverlapCorruptionGuard} shares the SAME marker instead of tracking its own,
 * separate one.
 */
class TemplateRemovalTrackerTest {

  @BeforeEach
  void setUp() {
    // Ensure each test starts with a clean state, since ThreadLocal persists across test methods
    // if they reuse the same JUnit worker thread.
    TemplateRemovalTracker.clear();
  }

  @Test
  void markedTemplateIsReportedAsBeingRemoved() {
    TemplateRemovalTracker.markRemoved("template-a");

    assertTrue(TemplateRemovalTracker.isBeingRemoved("template-a"));
  }

  @Test
  void unmarkedTemplateIsNotReportedAsBeingRemoved() {
    assertFalse(TemplateRemovalTracker.isBeingRemoved("template-b"));
  }

  @Test
  void clearedTemplateIsNoLongerReportedAsBeingRemoved() {
    TemplateRemovalTracker.markRemoved("template-c");
    assertTrue(TemplateRemovalTracker.isBeingRemoved("template-c"));

    TemplateRemovalTracker.clear();

    assertFalse(TemplateRemovalTracker.isBeingRemoved("template-c"));
  }

  @Test
  void multipleTemplatesAccumulateIndependently() {
    TemplateRemovalTracker.markRemoved("template-a");
    TemplateRemovalTracker.markRemoved("template-b");
    TemplateRemovalTracker.markRemoved("template-c");

    assertTrue(TemplateRemovalTracker.isBeingRemoved("template-a"));
    assertTrue(TemplateRemovalTracker.isBeingRemoved("template-b"));
    assertTrue(TemplateRemovalTracker.isBeingRemoved("template-c"));
  }
}
