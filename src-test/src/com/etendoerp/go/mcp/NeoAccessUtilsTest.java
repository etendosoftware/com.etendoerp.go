/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Unit tests for {@link NeoAccessUtils}.
 */
@ExtendWith(MockitoExtension.class)
class NeoAccessUtilsTest {

  private MockedStatic<NeoAccessHelper> accessHelperMock;

  @BeforeEach
  void setUp() {
    accessHelperMock = mockStatic(NeoAccessHelper.class);
  }

  @AfterEach
  void tearDown() {
    accessHelperMock.close();
  }

  @Test
  @DisplayName("hasWindowAccess delegates to NeoAccessHelper and returns true")
  void hasWindowAccessDelegatesTrue() {
    accessHelperMock.when(() -> NeoAccessHelper.hasWindowAccess("win-123")).thenReturn(true);
    assertTrue(NeoAccessUtils.hasWindowAccess("win-123"));
  }

  @Test
  @DisplayName("hasWindowAccess returns false when helper returns false")
  void hasWindowAccessDelegatesFalse() {
    accessHelperMock.when(() -> NeoAccessHelper.hasWindowAccess("win-456")).thenReturn(false);
    assertFalse(NeoAccessUtils.hasWindowAccess("win-456"));
  }

  @Test
  @DisplayName("hasProcessAccess delegates to NeoAccessHelper and returns true")
  void hasProcessAccessDelegatesTrue() {
    accessHelperMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-1")).thenReturn(true);
    assertTrue(NeoAccessUtils.hasProcessAccess("proc-1"));
  }

  @Test
  @DisplayName("hasProcessAccess returns false when helper returns false")
  void hasProcessAccessDelegatesFalse() {
    accessHelperMock.when(() -> NeoAccessHelper.hasProcessAccess("proc-2")).thenReturn(false);
    assertFalse(NeoAccessUtils.hasProcessAccess("proc-2"));
  }
}
