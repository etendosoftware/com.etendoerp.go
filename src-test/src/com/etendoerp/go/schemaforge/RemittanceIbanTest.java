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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.module.remittance.Remittance;

/**
 * Unit tests for {@link RemittanceIban} (ETP-6247): the IBAN of a remittance, its copy of the
 * account's, which its SEPA file carries.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceIban
 */
class RemittanceIbanTest {

  private static final String IBAN = "ES9121000418450200051332";

  @Test
  void theIbanIsNormalizedAndChecked() {
    assertEquals(IBAN, RemittanceIban.normalize(" es91 2100-0418 4502 0005 1332 "));
    assertTrue(RemittanceIban.isValid("DE89 3704 0044 0532 0130 00"));
    assertFalse(RemittanceIban.isValid("ES0021000418450200051332"), "check digits");
    assertFalse(RemittanceIban.isValid("ES91"), "too short");
    assertFalse(RemittanceIban.isValid("ES91210004184502000513321234567890123"), "too long");
    assertFalse(RemittanceIban.isValid(null));
  }

  @Test
  void theCopyWinsAndWithoutOneTheAccountsIsUsed() {
    assertEquals(IBAN, RemittanceIban.effective(remittance(" es91 2100 0418 4502 0005 1332", "x")));
    assertEquals(IBAN, RemittanceIban.effective(remittance(null, IBAN)));
    assertNull(RemittanceIban.effective(remittance("  ", null)));
    assertEquals("ES00", RemittanceIban.effective(remittance("ES00", IBAN)),
        "an invalid copy is not replaced by the account's");
  }

  @Test
  void theFileTakesTheCopyAndSnapshotsTheAccountsWhenThereIsNone() {
    Remittance withCopy = remittance(IBAN, "DE89370400440532013000");
    assertEquals(IBAN, RemittanceIban.forFile(withCopy));
    verify(withCopy, never()).set(anyString(), any());

    Remittance old = remittance(null, IBAN);
    assertEquals(IBAN, RemittanceIban.forFile(old));
    verify(old).set(RemittanceIban.PROPERTY, IBAN);

    Remittance invalid = remittance("ES0021000418450200051332", IBAN);
    assertNull(RemittanceIban.forFile(invalid));
    verify(invalid, never()).set(anyString(), any());
  }

  /** The stand-in is only for an account WITHOUT an IBAN; otherwise the generator runs as is. */
  @Test
  void theStandInIsNotUsedWhenTheAccountHasAnIban() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getIBAN()).thenReturn(IBAN);

    assertEquals("ran", RemittanceIban.whileAccountHas(account, "DE89370400440532013000",
        () -> "ran"));
    verify(account, never()).setIBAN(anyString());
    assertEquals("ran", RemittanceIban.whileAccountHas(null, IBAN, () -> "ran"));
  }

  private static Remittance remittance(String copy, String accountIban) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getIBAN()).thenReturn(accountIban);
    Remittance remittance = mock(Remittance.class);
    when(remittance.get(RemittanceIban.PROPERTY)).thenReturn(copy);
    when(remittance.getFinancialAccount()).thenReturn(account);
    return remittance;
  }
}
