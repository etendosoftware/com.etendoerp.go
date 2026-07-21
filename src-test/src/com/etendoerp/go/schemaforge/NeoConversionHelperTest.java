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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.common.enterprise.Organization;

/** Tests for {@link NeoConversionHelper}. */
public class NeoConversionHelperTest {

  @Test
  public void buildSqlWithCurrency() {
    String template = "SELECT {AMOUNT} FROM c_invoice i";
    String result = NeoConversionHelper.buildSql(template, "102");

    assertTrue(result.contains("COALESCE"));
    assertTrue(result.contains(":orgCurrencyId"));
  }

  @Test
  public void buildSqlWithoutCurrency() {
    String template = "SELECT {AMOUNT} FROM c_invoice i";
    String result = NeoConversionHelper.buildSql(template, null);

    assertEquals("SELECT i.grandtotal FROM c_invoice i", result);
  }

  @Test
  public void resolveOrgCurrencyIdDelegates() {
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("org-1");

    OBContext obCtx = mock(OBContext.class);
    when(obCtx.getCurrentOrganization()).thenReturn(org);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBCurrencyUtils> currMock = mockStatic(OBCurrencyUtils.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      currMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("102");

      String currencyId = NeoConversionHelper.resolveOrgCurrencyId();
      assertEquals("102", currencyId);
    }
  }

  @Test
  public void buildSqlIncludesSystemClientRatesWithTenantPriority() {
    // ETP-4474 regression: a GO tenant must see both its own rates and the shared system ('0')
    // rates, with the tenant rate winning (ad_client_id DESC picks the tenant row under LIMIT 1).
    String template = "SELECT {AMOUNT} FROM c_invoice i";
    String result = NeoConversionHelper.buildSql(template, "102");

    assertTrue(result.contains("ad_client_id IN ('0', i.ad_client_id)"));
    assertTrue(result.contains("ORDER BY cr.ad_client_id DESC"));
  }

  @Test
  public void constantsExist() {
    assertNotNull(NeoConversionHelper.PARAM_ORG_CURRENCY_ID);
    assertNotNull(NeoConversionHelper.AMOUNT_PLACEHOLDER);
    assertNotNull(NeoConversionHelper.CONVERTED_GRANDTOTAL);
  }
}
