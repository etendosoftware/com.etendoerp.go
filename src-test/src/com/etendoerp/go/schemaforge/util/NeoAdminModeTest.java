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

package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;

/**
 * Unit tests for {@link NeoAdminMode#enter()}.
 *
 * @covers com.etendoerp.go.schemaforge.util.NeoAdminMode
 */
public class NeoAdminModeTest {

  @Test
  public void enterSetsAdminModeAndLeavesTheStackAloneOnSuccess() {
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      NeoAdminMode.enter();

      ctx.verify(() -> OBContext.setAdminMode(true), times(1));
      ctx.verify(OBContext::restorePreviousMode, never());
    }
  }

  @Test
  public void enterPopsTheEntryAndRethrowsWhenSettingAdminModeFails() {
    IllegalStateException failure = new IllegalStateException("no admin context");

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class)) {
      ctx.when(() -> OBContext.setAdminMode(true)).thenThrow(failure);

      IllegalStateException thrown = assertThrows(IllegalStateException.class,
          NeoAdminMode::enter);

      assertSame(failure, thrown);
      ctx.verify(OBContext::restorePreviousMode, times(1));
    }
  }
}
