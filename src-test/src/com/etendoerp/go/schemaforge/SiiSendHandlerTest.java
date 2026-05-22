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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link SiiSendHandler}.
 *
 * <p>Covers action-name matching for the three valid variants and the error
 * message prefix produced when execution fails.
 */
public class SiiSendHandlerTest {

  private final SiiSendHandler handler = new SiiSendHandler();

  @Test
  public void testMatchesCanonicalActionName() {
    assertTrue(handler.matchesActionName(SiiSendHandler.ACTION_NAME));
  }

  @Test
  public void testMatchesLegacyActionName() {
    assertTrue(handler.matchesActionName(SiiSendHandler.ACTION_NAME_LEGACY));
  }

  @Test
  public void testMatchesQualifierActionName() {
    assertTrue(handler.matchesActionName(SiiSendHandler.ACTION_NAME_QUALIFIER));
  }

  @Test
  public void testDoesNotMatchUnrelatedActionName() {
    assertFalse(handler.matchesActionName("registerPayment"));
    assertFalse(handler.matchesActionName("Em_Tbai_Xmlgenerator"));
    assertFalse(handler.matchesActionName(""));
  }

  @Test
  public void testDoesNotMatchNull() {
    assertFalse(handler.matchesActionName(null));
  }

  @Test
  public void testBuildExecutionErrorMessageIncludesPrefix() {
    RuntimeException e = new RuntimeException("network timeout");
    String msg = handler.buildExecutionErrorMessage(e);
    assertTrue(msg.startsWith("SII send failed: "));
    assertTrue(msg.contains("network timeout"));
  }

  @Test
  public void testHandleReturnsNullForGetRequest() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(SiiSendHandler.ACTION_NAME)
        .recordId("invoice-1")
        .build();

    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleReturnsNullForCrudEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .fieldName(SiiSendHandler.ACTION_NAME)
        .recordId("invoice-1")
        .build();

    assertNull(handler.handle(ctx));
  }
}
