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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link SalesInvoiceHeaderHandler}.
 *
 * <p>Covers two responsibilities:
 * <ul>
 *   <li>{@code handle()} dispatches ACTION requests to the right downstream handler</li>
 *   <li>{@code afterHandle()} delegates to {@link BpEmailAnnotatorHandler}</li>
 * </ul>
 *
 * <p>The annotation logic itself is covered in {@link BpEmailAnnotatorHandlerTest}.
 */
public class SalesInvoiceHeaderHandlerTest {

  /**
   * Creates a {@link SalesInvoiceHeaderHandler} with its {@code @Inject} fields replaced by the
   * provided mocks via reflection, bypassing CDI in the unit-test context.
   *
   * @param mockClone     mock for {@link NeoCloneRecordHandler}
   * @param mockPayment   mock for {@link RegisterPaymentHandler}
   * @param mockAnnotator mock for {@link BpEmailAnnotatorHandler}
   */
  private static SalesInvoiceHeaderHandler handlerWithMocks(
      NeoCloneRecordHandler mockClone,
      RegisterPaymentHandler mockPayment,
      BpEmailAnnotatorHandler mockAnnotator) throws Exception {
    SalesInvoiceHeaderHandler handler = new SalesInvoiceHeaderHandler();
    setField(handler, "cloneRecordHandler", mockClone);
    setField(handler, "registerPaymentHandler", mockPayment);
    setField(handler, "bpEmailAnnotatorHandler", mockAnnotator);
    return handler;
  }

  /**
   * Sets a private field on a {@link SalesInvoiceHeaderHandler} instance via reflection.
   *
   * @param target    the handler instance to mutate
   * @param fieldName the declared field name
   * @param value     the value to assign
   */
  private static void setField(SalesInvoiceHeaderHandler target, String fieldName, Object value) throws Exception {
    Field field = SalesInvoiceHeaderHandler.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  // ── handle() dispatch ──────────────────────────────────────────────────────

  /**
   * Verifies that handle returns the register-payment response when the payment handler matches.
   */
  @Test
  public void testHandleDispatchesToRegisterPaymentHandler() throws Exception {
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    RegisterPaymentHandler mockPayment = mock(RegisterPaymentHandler.class);
    BpEmailAnnotatorHandler mockAnnotator = mock(BpEmailAnnotatorHandler.class);
    SalesInvoiceHeaderHandler handler = handlerWithMocks(mockClone, mockPayment, mockAnnotator);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("action", "registerPayment"));
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).fieldName("registerPayment").build();
    when(mockPayment.handle(ctx)).thenReturn(expected);

    assertSame(expected, handler.handle(ctx));
  }

  /**
   * Verifies that handle returns null when no downstream handler matches the context.
   */
  @Test
  public void testHandleReturnsNullWhenNoHandlerMatches() throws Exception {
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    RegisterPaymentHandler mockPayment = mock(RegisterPaymentHandler.class);
    BpEmailAnnotatorHandler mockAnnotator = mock(BpEmailAnnotatorHandler.class);
    SalesInvoiceHeaderHandler handler = handlerWithMocks(mockClone, mockPayment, mockAnnotator);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    when(mockClone.handle(ctx)).thenReturn(null);
    when(mockPayment.handle(ctx)).thenReturn(null);

    assertNull(handler.handle(ctx));
  }

  // ── afterHandle() delegation ───────────────────────────────────────────────

  /**
   * Verifies that afterHandle returns the response produced by BpEmailAnnotatorHandler.
   */
  @Test
  public void testAfterHandleDelegatesToBpEmailAnnotator() throws Exception {
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    RegisterPaymentHandler mockPayment = mock(RegisterPaymentHandler.class);
    BpEmailAnnotatorHandler mockAnnotator = mock(BpEmailAnnotatorHandler.class);
    SalesInvoiceHeaderHandler handler = handlerWithMocks(mockClone, mockPayment, mockAnnotator);

    NeoResponse expected = NeoResponse.ok(new JSONObject().put("annotated", true));
    NeoContext ctx = NeoContext.builder()
        .specName("sales-invoice").entityName("header")
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    when(mockAnnotator.afterHandle(ctx)).thenReturn(expected);

    assertSame(expected, handler.afterHandle(ctx));
    verify(mockAnnotator).afterHandle(ctx);
  }

  /**
   * Verifies that afterHandle returns null when the annotator decides not to annotate (e.g. non-GET).
   */
  @Test
  public void testAfterHandleReturnsNullWhenAnnotatorReturnsNull() throws Exception {
    NeoCloneRecordHandler mockClone = mock(NeoCloneRecordHandler.class);
    RegisterPaymentHandler mockPayment = mock(RegisterPaymentHandler.class);
    BpEmailAnnotatorHandler mockAnnotator = mock(BpEmailAnnotatorHandler.class);
    SalesInvoiceHeaderHandler handler = handlerWithMocks(mockClone, mockPayment, mockAnnotator);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION).build();
    when(mockAnnotator.afterHandle(ctx)).thenReturn(null);

    assertNull(handler.afterHandle(ctx));
  }
}
