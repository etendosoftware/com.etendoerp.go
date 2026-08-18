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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Test;
import org.openbravo.model.common.enterprise.DocumentType;

/**
 * Unit tests for {@link RectificativeSupport} (ETP-4737).
 *
 * <p>Covers {@link RectificativeSupport#isRectificative(DocumentType)}, using the
 * {@link RectificativeSupport#setColumnPresentForTests(Boolean)} hook to avoid depending on a
 * real database connection for the column-presence check.
 *
 * <p>ETP-4841 removed the id-based lookup ({@code isRectificativeDocType}) and the client-wide
 * doc-type resolution ({@code resolveRectificativeDocTypes}) together with their only callers:
 * whether an invoice is a consumable credit is now decided by the SIGN of its total, never by its
 * document type. What remains here is the document-type FLAG itself, which still drives display
 * classification (FAC vs RECTIFICATIVA badge) and the return-shipment doc-type lookup.
 */
public class RectificativeSupportTest {

  @After
  public void resetColumnPresentCache() {
    RectificativeSupport.setColumnPresentForTests(null);
  }

  @Test
  public void isRectificative_nullDocType_returnsFalse() {
    RectificativeSupport.setColumnPresentForTests(true);
    assertFalse(RectificativeSupport.isRectificative(null));
  }

  /**
   * When the column is absent (SIF General not installed) the flag getter is never consulted —
   * that short-circuit is what keeps a SELECT against a missing column from poisoning the shared
   * PostgreSQL transaction for the rest of the request.
   */
  @Test
  public void isRectificative_columnAbsent_returnsFalseWithoutTouchingDocType() {
    RectificativeSupport.setColumnPresentForTests(false);
    DocumentType dt = mock(DocumentType.class);

    assertFalse(RectificativeSupport.isRectificative(dt));
    verify(dt, never()).isEtsgIsRectificative();
  }

  @Test
  public void isRectificative_columnPresentAndFlagTrue_returnsTrue() {
    RectificativeSupport.setColumnPresentForTests(true);
    DocumentType dt = mock(DocumentType.class);
    when(dt.isEtsgIsRectificative()).thenReturn(true);

    assertTrue(RectificativeSupport.isRectificative(dt));
  }

  @Test
  public void isRectificative_columnPresentAndFlagFalse_returnsFalse() {
    RectificativeSupport.setColumnPresentForTests(true);
    DocumentType dt = mock(DocumentType.class);
    when(dt.isEtsgIsRectificative()).thenReturn(false);

    assertFalse(RectificativeSupport.isRectificative(dt));
  }

  @Test
  public void isRectificative_columnPresentAndFlagNull_returnsFalse() {
    RectificativeSupport.setColumnPresentForTests(true);
    DocumentType dt = mock(DocumentType.class);
    when(dt.isEtsgIsRectificative()).thenReturn(null);

    assertFalse(RectificativeSupport.isRectificative(dt));
  }

  /** The forced column-presence value survives repeated calls (it is a cache, not a one-shot). */
  @Test
  public void isColumnPresent_forcedValue_isStableAcrossCalls() {
    RectificativeSupport.setColumnPresentForTests(true);

    assertTrue(RectificativeSupport.isColumnPresent());
    assertTrue(RectificativeSupport.isColumnPresent());

    RectificativeSupport.setColumnPresentForTests(false);

    assertFalse(RectificativeSupport.isColumnPresent());
  }
}
