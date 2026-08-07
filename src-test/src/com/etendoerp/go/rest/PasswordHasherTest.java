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
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PasswordHasher} (ETP-4829) — extracted from {@link
 * EtendoGoJwtServlet#hashPassword}, which now delegates here.
 */
class PasswordHasherTest {

  @Test
  void hashReturnsSaltAndDigestSeparatedByColon() {
    String hash = PasswordHasher.hash("Str0ng!Pass");

    assertTrue(hash.contains(":"), "hash must be \"salt:digest\"");
    String[] parts = hash.split(":", 2);
    assertEquals(2, parts.length);
  }

  @Test
  void hashUsesARandomSaltSoTwoHashesOfTheSamePasswordDiffer() {
    String first = PasswordHasher.hash("Str0ng!Pass");
    String second = PasswordHasher.hash("Str0ng!Pass");

    assertNotEquals(first, second);
  }
}
