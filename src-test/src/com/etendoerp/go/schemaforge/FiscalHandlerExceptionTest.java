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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.openbravo.base.exception.OBException;

/**
 * Unit tests for {@link FiscalHandlerException}.
 *
 * Verifies that the exception correctly wraps its cause, propagates the
 * message, and satisfies the Etendo convention of extending {@link OBException}.
 */
public class FiscalHandlerExceptionTest {

  @Test
  public void testExtendsOBException() {
    RuntimeException cause = new RuntimeException("boom");
    FiscalHandlerException ex = new FiscalHandlerException(cause);
    assertTrue(ex instanceof OBException);
  }

  @Test
  public void testIsUnchecked() {
    RuntimeException cause = new RuntimeException("boom");
    FiscalHandlerException ex = new FiscalHandlerException(cause);
    assertTrue(ex instanceof RuntimeException);
  }

  @Test
  public void testCauseIsPreserved() {
    RuntimeException cause = new RuntimeException("original");
    FiscalHandlerException ex = new FiscalHandlerException(cause);
    assertSame(cause, ex.getCause());
  }

  @Test
  public void testMessageDerivedFromCause() {
    RuntimeException cause = new RuntimeException("root message");
    FiscalHandlerException ex = new FiscalHandlerException(cause);
    assertEquals("root message", ex.getMessage());
  }

  @Test
  public void testWrapsCheckedException() {
    Exception checked = new Exception("checked error");
    FiscalHandlerException ex = new FiscalHandlerException(checked);
    assertSame(checked, ex.getCause());
    assertEquals("checked error", ex.getMessage());
  }
}
