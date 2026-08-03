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
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.plm.ProductCategory;

/**
 * Unit tests for {@link ProductCategoryDefaultHandler}.
 *
 * <p>Tests are split into the following groups:
 * <ul>
 *   <li><strong>Guard conditions</strong> – early returns without any DB access</li>
 *   <li><strong>Create (POST)</strong> – blocks/allows setting default on a new record</li>
 *   <li><strong>Update (PATCH/PUT)</strong> – blocks/allows setting default on an existing record,
 *       including the no-op edit case</li>
 *   <li><strong>afterHandle()</strong> – always returns null (no post-processing needed)</li>
 * </ul>
 *
 * <p>The conflict scope is per-CLIENT (not per-organization) — see the class Javadoc on
 * {@link ProductCategoryDefaultHandler} for the QA finding that motivated this: a category in a
 * "real" organization and one in the wildcard organization {@code '0'} both count as the same
 * client's default, even though {@code AD_Org_ID} differs between them.
 */
public class ProductCategoryDefaultHandlerTest {

  private static final String MSG_KEY = "ETGO_ProductCategoryCannotSetMultipleDefault";

  // ── helpers ───────────────────────────────────────────────────────────────

  private static NeoContext methodCtx(String method, String recordId, JSONObject requestBody,
      OBContext obContext) {
    return NeoContext.builder()
        .specName("product-category").entityName("productCategory")
        .httpMethod(method).endpointType(NeoEndpointType.CRUD)
        .recordId(recordId).requestBody(requestBody).obContext(obContext).build();
  }

  private static NeoContext patchCtx(String recordId, JSONObject requestBody) {
    return methodCtx("PATCH", recordId, requestBody, mock(OBContext.class));
  }

  private static NeoContext postCtx(JSONObject requestBody, OBContext obContext) {
    return methodCtx("POST", null, requestBody, obContext);
  }

  private static OBContext obContextWithClient(String clientId) {
    OBContext obContext = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(obContext.getCurrentClient()).thenReturn(client);
    return obContext;
  }

  @SuppressWarnings("unchecked")
  private static OBCriteria<ProductCategory> stubCriteria(OBDal dal,
      List<ProductCategory> conflicts) {
    OBCriteria<ProductCategory> crit = mock(OBCriteria.class);
    when(dal.createCriteria(ProductCategory.class)).thenReturn(crit);
    when(crit.list()).thenReturn(conflicts);
    return crit;
  }

  private static ProductCategory categoryWithClient(String clientId) {
    ProductCategory category = mock(ProductCategory.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(category.getClient()).thenReturn(client);
    return category;
  }

  // ── guard conditions ──────────────────────────────────────────────────────

  @Test
  public void testHandleReturnsNullForNullContext() {
    assertNull(new ProductCategoryDefaultHandler().handle(null));
  }

  @Test
  public void testHandleIgnoresUnsupportedMethod() throws JSONException {
    JSONObject body = new JSONObject().put("default", true);
    NeoContext ctx = methodCtx("GET", "pc-1", body, mock(OBContext.class));

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      assertNull(new ProductCategoryDefaultHandler().handle(ctx));
      obDalMock.verifyNoInteractions();
    }
  }

  @Test
  public void testHandleIgnoresRequestWithoutDefaultField() throws JSONException {
    JSONObject body = new JSONObject().put("name", "Renamed Category");

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      assertNull(new ProductCategoryDefaultHandler().handle(patchCtx("pc-1", body)));
      obDalMock.verifyNoInteractions();
    }
  }

  @Test
  public void testHandleIgnoresRequestSettingDefaultFalse() throws JSONException {
    JSONObject body = new JSONObject().put("default", false);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      assertNull(new ProductCategoryDefaultHandler().handle(patchCtx("pc-1", body)));
      obDalMock.verifyNoInteractions();
    }
  }

  // ── create (POST) ─────────────────────────────────────────────────────────

  @Test
  public void testHandleBlocksCreateWhenAnotherDefaultExistsInSameClient() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      stubCriteria(dal, Collections.singletonList(categoryWithClient("CLIENT1")));
      msgMock.when(() -> OBMessageUtils.messageBD(MSG_KEY)).thenReturn("Localized message");

      JSONObject body = new JSONObject().put("default", true);
      NeoResponse result = new ProductCategoryDefaultHandler()
          .handle(postCtx(body, obContextWithClient("CLIENT1")));

      assertNotNull(result);
      assertEquals(400, result.getHttpStatus());
      assertEquals("Localized message",
          result.getBody().getJSONObject("error").getString("message"));
    }
  }

  @Test
  public void testHandleAllowsCreateWhenNoConflictingDefaultExists() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      stubCriteria(dal, Collections.emptyList());

      JSONObject body = new JSONObject().put("default", true);
      assertNull(new ProductCategoryDefaultHandler()
          .handle(postCtx(body, obContextWithClient("CLIENT1"))));
      msgMock.verifyNoInteractions();
    }
  }

  @Test
  public void testHandleAllowsCreateWhenClientContextMissing() throws JSONException {
    JSONObject body = new JSONObject().put("default", true);

    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      assertNull(new ProductCategoryDefaultHandler().handle(postCtx(body, mock(OBContext.class))));
      obDalMock.verifyNoInteractions();
    }
  }

  // ── update (PATCH/PUT) ────────────────────────────────────────────────────

  @Test
  public void testHandleBlocksUpdateWhenAnotherDefaultExistsInSameClient() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ProductCategory current = categoryWithClient("CLIENT1");
      when(dal.get(ProductCategory.class, "pc-1")).thenReturn(current);
      stubCriteria(dal, Collections.singletonList(categoryWithClient("CLIENT1")));
      msgMock.when(() -> OBMessageUtils.messageBD(MSG_KEY)).thenReturn("Localized message");

      JSONObject body = new JSONObject().put("default", true);
      NeoResponse result = new ProductCategoryDefaultHandler().handle(patchCtx("pc-1", body));

      assertNotNull(result);
      assertEquals(400, result.getHttpStatus());
    }
  }

  /**
   * The exact bug reproduced in QA: "Otros" (a real organization) and "Bebidas" (the wildcard
   * organization {@code '0'}) belong to the SAME client but DIFFERENT {@code AD_Org_ID}. Scoping
   * by client (not by organization) must still detect this as a conflict.
   */
  @Test
  public void testHandleBlocksUpdateWhenConflictingDefaultInDifferentOrgSameClient()
      throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      // "Otros" — the record being edited, in a real organization.
      ProductCategory otros = categoryWithClient("CLIENT1");
      when(dal.get(ProductCategory.class, "otros-id")).thenReturn(otros);
      // "Bebidas" — already default, in the wildcard organization '0', same client.
      ProductCategory bebidas = categoryWithClient("CLIENT1");
      stubCriteria(dal, Collections.singletonList(bebidas));
      msgMock.when(() -> OBMessageUtils.messageBD(MSG_KEY)).thenReturn("Localized message");

      JSONObject body = new JSONObject().put("default", true);
      NeoResponse result = new ProductCategoryDefaultHandler().handle(patchCtx("otros-id", body));

      assertNotNull(result);
      assertEquals(400, result.getHttpStatus());
    }
  }

  @Test
  public void testHandleAllowsUpdateWhenNoConflictingDefaultExists() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ProductCategory current = categoryWithClient("CLIENT1");
      when(dal.get(ProductCategory.class, "pc-1")).thenReturn(current);
      stubCriteria(dal, Collections.emptyList());

      JSONObject body = new JSONObject().put("default", true);
      assertNull(new ProductCategoryDefaultHandler().handle(patchCtx("pc-1", body)));
      msgMock.verifyNoInteractions();
    }
  }

  /**
   * Editing the record that IS already the default (a no-op re-save) must be allowed: the
   * criteria excludes the current record id, so it never conflicts with itself.
   */
  @Test
  public void testHandleAllowsUpdateWhenExistingDefaultIsSameRecord() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ProductCategory current = categoryWithClient("CLIENT1");
      when(dal.get(ProductCategory.class, "pc-1")).thenReturn(current);
      // Criteria excludes pc-1 itself (Restrictions.ne on id), so no conflicts are returned.
      stubCriteria(dal, Collections.emptyList());

      JSONObject body = new JSONObject().put("default", true);
      assertNull(new ProductCategoryDefaultHandler().handle(patchCtx("pc-1", body)));
      msgMock.verifyNoInteractions();
    }
  }

  /**
   * A conflicting default that belongs to a DIFFERENT client never blocks the request — the
   * guard is scoped per client.
   */
  @Test
  public void testHandleAllowsUpdateWhenConflictingDefaultInDifferentClient() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ProductCategory current = categoryWithClient("CLIENT1");
      when(dal.get(ProductCategory.class, "pc-1")).thenReturn(current);
      // The criteria itself is scoped by CLIENT1, so a conflict in CLIENT2 would never be
      // returned by a correctly-filtered query — simulate that by stubbing an empty result.
      stubCriteria(dal, Collections.emptyList());

      JSONObject body = new JSONObject().put("default", true);
      assertNull(new ProductCategoryDefaultHandler().handle(patchCtx("pc-1", body)));
      msgMock.verifyNoInteractions();
    }
  }

  @Test
  public void testHandleAllowsUpdateWhenRecordNotFound() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(ProductCategory.class, "pc-missing")).thenReturn(null);

      JSONObject body = new JSONObject().put("default", true);
      assertNull(new ProductCategoryDefaultHandler().handle(patchCtx("pc-missing", body)));
    }
  }

  @Test
  public void testHandleBlocksSettingDefaultViaPut() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ProductCategory current = categoryWithClient("CLIENT1");
      when(dal.get(ProductCategory.class, "pc-2")).thenReturn(current);
      stubCriteria(dal, Collections.singletonList(categoryWithClient("CLIENT1")));
      msgMock.when(() -> OBMessageUtils.messageBD(MSG_KEY)).thenReturn("Localized message");

      JSONObject body = new JSONObject().put("default", true);
      NeoResponse result = new ProductCategoryDefaultHandler()
          .handle(methodCtx("PUT", "pc-2", body, mock(OBContext.class)));

      assertNotNull(result);
      assertEquals(400, result.getHttpStatus());
    }
  }

  @Test
  public void testHandleRecognizesStringEncodedDefaultTrue() throws JSONException {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBMessageUtils> msgMock = Mockito.mockStatic(OBMessageUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      ProductCategory current = categoryWithClient("CLIENT1");
      when(dal.get(ProductCategory.class, "pc-3")).thenReturn(current);
      stubCriteria(dal, Collections.singletonList(categoryWithClient("CLIENT1")));
      msgMock.when(() -> OBMessageUtils.messageBD(MSG_KEY)).thenReturn("Localized message");

      JSONObject body = new JSONObject().put("default", "Y");
      assertNotNull(new ProductCategoryDefaultHandler().handle(patchCtx("pc-3", body)));
    }
  }

  // ── afterHandle() ─────────────────────────────────────────────────────────

  @Test
  public void testAfterHandleAlwaysReturnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET").endpointType(NeoEndpointType.CRUD).build();
    assertNull(new ProductCategoryDefaultHandler().afterHandle(ctx));
  }
}
