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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.usageevents.UsageEventRecorder;

/**
 * ETP-5462 — {@link NeoServlet#destroy()} is where the usage event writer is stopped on undeploy.
 *
 * <p>The writer is the only thing that drains queued usage rows, so the servlet must call
 * {@link UsageEventRecorder#shutdown()} exactly once, and a failure there must never stop the rest of
 * the servlet's teardown. {@code super.destroy()} ({@code GenericServlet}) is a no-op with nothing
 * observable, so the second half is asserted as "destroy() still completes normally" when the
 * shutdown throws.</p>
 */
class NeoServletDestroyTest {

  @Test
  void destroyStopsTheUsageEventWriter() {
    try (MockedStatic<UsageEventRecorder> recorder = mockStatic(UsageEventRecorder.class)) {
      new NeoServlet().destroy();
      recorder.verify(UsageEventRecorder::shutdown, times(1));
    }
  }

  @Test
  void aFailingShutdownDoesNotFailDestroy() {
    try (MockedStatic<UsageEventRecorder> recorder = mockStatic(UsageEventRecorder.class)) {
      recorder.when(UsageEventRecorder::shutdown).thenThrow(new IllegalStateException("boom"));
      NeoServlet servlet = new NeoServlet();
      assertDoesNotThrow(servlet::destroy);
      recorder.verify(UsageEventRecorder::shutdown, times(1));
    }
  }
}
