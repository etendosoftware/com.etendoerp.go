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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.openbravo.module.remittance.Remittance;

/**
 * Unit tests for {@link RemittanceSepaOptions} (ETP-6247): the per-remittance direct debit scheme
 * and financed flag.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaOptions
 */
class RemittanceSepaOptionsTest {

  @Test
  void theSchemeIsNormalizedAndOnlyCoreAndB2bAreKnown() {
    assertEquals("CORE", RemittanceSepaOptions.normalizeScheme(" core "));
    assertEquals("B2B", RemittanceSepaOptions.normalizeScheme("b2b"));
    assertNull(RemittanceSepaOptions.normalizeScheme("COR"));
    assertNull(RemittanceSepaOptions.normalizeScheme(null));
  }

  @Test
  void aRemittanceWithoutASchemeIsCoreAndNotFinanced() {
    Remittance remittance = mock(Remittance.class);

    assertEquals("CORE", RemittanceSepaOptions.scheme(remittance));
    assertFalse(RemittanceSepaOptions.financed(remittance));
  }

  @Test
  void theStoredOptionsAreRead() {
    Remittance remittance = mock(Remittance.class);
    when(remittance.get(RemittanceSepaOptions.PROPERTY_SCHEME)).thenReturn("b2b");
    when(remittance.get(RemittanceSepaOptions.PROPERTY_FINANCED)).thenReturn(Boolean.TRUE);

    assertEquals("B2B", RemittanceSepaOptions.scheme(remittance));
    assertTrue(RemittanceSepaOptions.financed(remittance));
  }

  @Test
  void theFinancedPrefixDefaultsToTheProvisionalConstant() {
    Remittance remittance = mock(Remittance.class);

    assertEquals(RemittanceSepaOptions.FINANCED_PREFIX, RemittanceSepaOptions.financedPrefix(remittance));
    assertEquals("FSDD", RemittanceSepaOptions.FINANCED_PREFIX);
  }
}
