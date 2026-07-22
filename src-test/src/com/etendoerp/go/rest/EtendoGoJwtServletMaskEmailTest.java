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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EtendoGoJwtServlet#maskEmail(String)} — the PII masking helper used when
 * logging the lost-stream warning (ETP-4428). Pure function, no servlet harness required.
 */
class EtendoGoJwtServletMaskEmailTest {

  @Test
  @DisplayName("masks a normal email keeping the first char plus the domain")
  void masksNormalEmail() {
    assertEquals("r***@corp.com", EtendoGoJwtServlet.maskEmail("roman@corp.com"));
  }

  @Test
  @DisplayName("collapses null and blank input to a safe placeholder")
  void placeholderForNullOrBlank() {
    assertEquals("(unknown)", EtendoGoJwtServlet.maskEmail(null));
    assertEquals("(unknown)", EtendoGoJwtServlet.maskEmail("   "));
  }

  @Test
  @DisplayName("masks a value with no '@' using the first char only")
  void masksValueWithoutAt() {
    assertEquals("a***", EtendoGoJwtServlet.maskEmail("abc"));
  }
}
