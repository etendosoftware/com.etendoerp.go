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
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletResponse;

import org.junit.Test;
import org.openbravo.base.structure.BaseOBObject;

/**
 * Unit tests for {@link FiscalSubmittedSnapshotSupport} (ETP-5438) — the edges not already
 * exercised end-to-end through {@code FiscalDeclCrudHandlerTest}'s PUT tests.
 */
public class FiscalSubmittedSnapshotSupportTest {

  private final NeoServlet servlet = mock(NeoServlet.class);
  private final FiscalSubmittedSnapshotSupport support =
      new FiscalSubmittedSnapshotSupport(new FiscalDeclCrudHandler(servlet), servlet);

  /** draft -> ready is not a submission: nothing computed, nothing written. */
  @Test
  public void nonSubmittedToNonSubmittedLeavesTheSnapshotAlone() throws Exception {
    int[] calls = { 0 };
    support.setProvider((model, year, period) -> {
      calls[0]++;
      return null;
    });
    BaseOBObject decl = mock(BaseOBObject.class);

    assertTrue(support.applyTransition(decl, "draft", "ready", "d1", mock(HttpServletResponse.class)));

    assertTrue(calls[0] == 0);
    verify(decl, never()).set(eq(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT), any());
  }

  /** Without a wired provider a presentation proceeds without a snapshot. */
  @Test
  public void withoutProviderAPresentationProceedsWithoutSnapshot() throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);

    assertTrue(support.applyTransition(decl, "draft", "submitted", "d1", mock(HttpServletResponse.class)));

    verify(decl, never()).set(eq(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT), any());
    verify(servlet, never()).sendError(any(), anyInt(), anyString());
  }

  /** A failure without a message still yields a readable error (the exception type). */
  @Test
  public void messagelessFailureReportsTheExceptionType() throws Exception {
    support.setProvider((model, year, period) -> {
      throw new IllegalStateException();
    });
    BaseOBObject decl = mock(BaseOBObject.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    assertTrue(!support.applyTransition(decl, null, "submitted_ack", "(new)", response));

    verify(servlet).sendError(eq(response), eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
        org.mockito.ArgumentMatchers.contains("IllegalStateException"));
  }

  /** A blank or unparseable stored value reads back as "no snapshot". */
  @Test
  public void parseReturnsNullForBlankAndCorruptValues() {
    BaseOBObject blank = mock(BaseOBObject.class);
    when(blank.get(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT)).thenReturn("  ");
    BaseOBObject corrupt = mock(BaseOBObject.class);
    when(corrupt.get(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT)).thenReturn("[not an object");

    assertNull(FiscalSubmittedSnapshotSupport.parseSubmittedSnapshot(blank));
    assertNull(FiscalSubmittedSnapshotSupport.parseSubmittedSnapshot(corrupt));
  }

  /** A declaration without entity metadata (a unit-test double) has nothing to validate. */
  @Test
  public void validateWithoutEntityIsANoOp() {
    FiscalSubmittedSnapshotSupport.validateSubmittedSnapshot(mock(BaseOBObject.class), "{}");
  }
}
