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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link InvoiceCalloutHelper#applyRectificativeFieldsFromDocType}.
 *
 * <p>Covers ETP-5229: the new {@code tbaiReverseinvoicecode} field must be autofilled/cleared
 * for SALES invoices only ({@code c_doctype.issotrx = 'Y'}), mirroring the existing
 * {@code tbaiIsreverseinvoice}/{@code tbaiReverseinvoicetype} conditional-set/clear pattern, while
 * remaining untouched for purchase invoices.
 */
public class InvoiceCalloutHelperTest {

  @After
  public void tearDown() {
    RectificativeSupport.setColumnPresentForTests(null);
  }

  private JSONObject requestBodyFor(String docTypeId) throws Exception {
    return new JSONObject()
        .put(AbstractInvoiceHeaderHandler.FIELD_VALUE, docTypeId);
  }

  private void mockDocType(MockedStatic<OBDal> dalMock, boolean isRectificative, boolean isSotrx)
      throws Exception {
    OBDal dal = mock(OBDal.class);
    dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
    Connection conn = mock(Connection.class);
    when(dal.getConnection()).thenReturn(conn);
    PreparedStatement ps = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(ps);
    ResultSet rs = mock(ResultSet.class);
    when(ps.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    when(rs.getString(1)).thenReturn(isRectificative ? "Y" : "N");
    when(rs.getString(2)).thenReturn(isSotrx ? "Y" : "N");
  }

  @Test
  public void rectificativeSalesInvoice_setsAllThreeTbaiFields() throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      mockDocType(dalMock, true, true);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-1"), updates);

      assertEquals("Y", updates.getJSONObject("tbaiIsreverseinvoice")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      assertEquals("I", updates.getJSONObject("tbaiReverseinvoicetype")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      assertEquals("R4", updates.getJSONObject("tbaiReverseinvoicecode")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
    }
  }

  @Test
  public void nonRectificativeSalesInvoice_clearsAllThreeTbaiFields() throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      mockDocType(dalMock, false, true);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-2"), updates);

      assertEquals("N", updates.getJSONObject("tbaiIsreverseinvoice")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      assertEquals("", updates.getJSONObject("tbaiReverseinvoicetype")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      assertEquals("", updates.getJSONObject("tbaiReverseinvoicecode")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
    }
  }

  @Test
  public void rectificativePurchaseInvoice_reverseInvoiceCodeNeverTouched() throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      mockDocType(dalMock, true, false);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-3"), updates);

      // Sibling fields are pre-existing, direction-agnostic behavior — untouched by this fix.
      assertEquals("Y", updates.getJSONObject("tbaiIsreverseinvoice")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      assertEquals("I", updates.getJSONObject("tbaiReverseinvoicetype")
          .getString(AbstractInvoiceHeaderHandler.FIELD_VALUE));
      // Sales-only field: never set for a purchase doc type, rectificative or not.
      assertFalse(updates.has("tbaiReverseinvoicecode"));
    }
  }

  @Test
  public void nonRectificativePurchaseInvoice_reverseInvoiceCodeNeverTouched() throws Exception {
    RectificativeSupport.setColumnPresentForTests(true);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      mockDocType(dalMock, false, false);
      JSONObject updates = new JSONObject();

      InvoiceCalloutHelper.applyRectificativeFieldsFromDocType(
          AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, requestBodyFor("dt-4"), updates);

      assertFalse(updates.has("tbaiReverseinvoicecode"));
    }
  }
}
