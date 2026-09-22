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

package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.cost.Costing;
import org.openbravo.model.pricing.pricelist.ProductPrice;

/**
 * Focused unit specifications for the durable demo-to-productive transfer (ETP-5364).
 *
 * <p>The public lifecycle intentionally detaches copying into a worker. The two upsert methods
 * are private because callers must only go through that durable lifecycle, so these tests invoke
 * the synchronous core through reflection rather than making worker timing part of the contract.
 * They still exercise real DAL calls and entity setters through Mockito: an existing target row
 * must be reused, its lookup must stay tenant-scoped, and blank CIF/Tax ID must be copied instead
 * of being routed through the interactive import validator.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DemoDataTransferServiceTest {

  private static final String SYSTEM_ID = "0";
  private static final String SOURCE_ID = "demo-client";
  private static final String TARGET_ID = "productive-client";
  private static final String REQUEST_ID = "checkout-request";

  @Mock private OBDal obDal;
  @Mock private OBProvider obProvider;
  @Mock private OBQuery<Preference> preferenceQuery;

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBProvider> obProviderStatic;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<Preferences> preferencesStatic;
  private DemoDataTransferService service;

  @BeforeEach
  void setUp() {
    obDalStatic = mockStatic(OBDal.class);
    obProviderStatic = mockStatic(OBProvider.class);
    contextStatic = mockStatic(OBContext.class);
    preferencesStatic = mockStatic(Preferences.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    obProviderStatic.when(OBProvider::getInstance).thenReturn(obProvider);
    service = new DemoDataTransferService();
  }

  @AfterEach
  void tearDown() {
    preferencesStatic.close();
    contextStatic.close();
    obProviderStatic.close();
    obDalStatic.close();
  }

  @Test
  void recordsCheckoutSelectionAtSystemScopeBeforeProvisioning() {
    Client system = mock(Client.class);
    when(obDal.get(Client.class, SYSTEM_ID)).thenReturn(system);

    service.recordSelection(REQUEST_ID, true, false);

    preferencesStatic.verify(() -> Preferences.setPreferenceValue(
        "ETGO_DemoDataTransferSelection." + REQUEST_ID, "YN", false,
        system, null, null, null, null, null));
    verify(obDal).flush();
    verify(obDal).commitAndClose();
  }

  @Test
  void startsAnUnselectedTransferAsSkippedOnlyOnTheNewProductiveTenant() throws Exception {
    Client target = mock(Client.class);
    Preference selection = preference("NN");
    when(obDal.get(Client.class, TARGET_ID)).thenReturn(target);
    givenPreferenceReads(selection);
    preventWorkerSubmission(TARGET_ID);

    service.start(REQUEST_ID, SOURCE_ID, TARGET_ID);

    preferencesStatic.verify(() -> Preferences.setPreferenceValue(
        "ETGO_DemoDataTransferSource", SOURCE_ID, false, target, null, null, null, null, null));
    preferencesStatic.verify(() -> Preferences.setPreferenceValue(
        "ETGO_DemoDataTransferProducts", "N", false, target, null, null, null, null, null));
    preferencesStatic.verify(() -> Preferences.setPreferenceValue(
        "ETGO_DemoDataTransferContacts", "N", false, target, null, null, null, null, null));
    preferencesStatic.verify(() -> Preferences.setPreferenceValue(
        "ETGO_DemoDataTransferStatus", DemoDataTransferService.STATUS_SKIPPED, false,
        target, null, null, null, null, null));
    verify(obDal).flush();
    verify(obDal).commitAndClose();
  }

  @Test
  void retriesOnlyFailedWorkAndKeepsTheRetryScopedToItsProductiveTenant() throws Exception {
    Client target = mock(Client.class);
    Preference failed = preference(DemoDataTransferService.STATUS_FAILED);
    Preference running = preference(DemoDataTransferService.STATUS_RUNNING);
    when(obDal.get(Client.class, TARGET_ID)).thenReturn(target);
    givenPreferenceReads(failed, running, null, null, null, null);
    preventWorkerSubmission(TARGET_ID);

    JSONObject response = service.retry(TARGET_ID);

    assertEquals(DemoDataTransferService.STATUS_RUNNING, response.getString("status"));
    preferencesStatic.verify(() -> Preferences.setPreferenceValue(
        "ETGO_DemoDataTransferStatus", DemoDataTransferService.STATUS_RUNNING, false,
        target, null, null, null, null, null));
    preferencesStatic.verify(() -> Preferences.setPreferenceValue(
        "ETGO_DemoDataTransferFailure", "", false, target, null, null, null, null, null));
  }

  @Test
  void doesNotRetryCompletedWork() throws Exception {
    givenPreferenceReads(preference(DemoDataTransferService.STATUS_COMPLETED),
        preference(DemoDataTransferService.STATUS_COMPLETED), null, null, null, null);

    JSONObject response = service.retry(TARGET_ID);

    assertEquals(DemoDataTransferService.STATUS_COMPLETED, response.getString("status"));
    preferencesStatic.verifyNoInteractions();
  }

  @Test
  void productUpsertReusesTheTargetTenantRowInsteadOfCreatingADuplicate() throws Exception {
    Client source = client(SOURCE_ID);
    Client target = client(TARGET_ID);
    Organization targetOrg = mock(Organization.class);
    Product sourceProduct = mock(Product.class);
    Product targetProduct = mock(Product.class);
    when(sourceProduct.getSearchKey()).thenReturn("SKU-001");
    when(sourceProduct.getName()).thenReturn("Migrated product");

    OBQuery<Product> sourceProducts = queryWithList(List.of(sourceProduct));
    OBQuery<Product> existingTarget = queryWithUniqueResult(targetProduct);
    OBQuery<ProductPrice> prices = queryWithList(Collections.emptyList());
    OBQuery<Costing> costs = queryWithList(Collections.emptyList());
    when(obDal.createQuery(eq(Product.class), anyString())).thenReturn(sourceProducts, existingTarget);
    when(obDal.createQuery(eq(ProductPrice.class), anyString())).thenReturn(prices);
    when(obDal.createQuery(eq(Costing.class), anyString())).thenReturn(costs);

    invokeCopy("copyProducts", Client.class, Client.class, Organization.class, source, target, targetOrg);

    verify(existingTarget).setNamedParameter("clientId", TARGET_ID);
    verify(obProvider, never()).get(Product.class);
    verify(targetProduct).setSearchKey("SKU-001");
    verify(targetProduct).setName("Migrated product");
    verify(obDal).save(targetProduct);
  }

  @Test
  void contactUpsertCopiesABlankTaxIdWithoutImportValidationAndReusesTheTargetRow() throws Exception {
    Client source = client(SOURCE_ID);
    Client target = client(TARGET_ID);
    Organization targetOrg = mock(Organization.class);
    BusinessPartner sourceContact = mock(BusinessPartner.class);
    BusinessPartner targetContact = mock(BusinessPartner.class);
    when(sourceContact.getSearchKey()).thenReturn("CONTACT-001");
    when(sourceContact.getName()).thenReturn("Migrated contact");
    when(sourceContact.getTaxID()).thenReturn("");

    OBQuery<BusinessPartner> sourceContacts = queryWithList(List.of(sourceContact));
    OBQuery<BusinessPartner> existingTarget = queryWithUniqueResult(targetContact);
    when(obDal.createQuery(eq(BusinessPartner.class), anyString()))
        .thenReturn(sourceContacts, existingTarget);

    invokeCopy("copyContacts", Client.class, Client.class, Organization.class, source, target, targetOrg);

    verify(existingTarget).setNamedParameter("clientId", TARGET_ID);
    verify(obProvider, never()).get(BusinessPartner.class);
    verify(targetContact).setSearchKey("CONTACT-001");
    verify(targetContact).setTaxID("");
    verify(obDal).save(targetContact);
  }

  @Test
  void pinsTheRuntimeProductFieldOrderAsTheContractForThisModule() {
    assertEquals(List.of("searchKey", "name", "description", "productType", "uOM",
        "salesPrice", "purchasePrice", "cost", "costStartingDate", "category"),
        DemoDataTransferService.PRODUCT_IMPORT_FIELDS);
  }

  private static Client client(String id) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(id);
    return client;
  }

  private static Preference preference(String value) {
    Preference preference = mock(Preference.class);
    when(preference.getSearchKey()).thenReturn(value);
    return preference;
  }

  @SafeVarargs
  private final void givenPreferenceReads(Preference... values) {
    when(obDal.createQuery(eq(Preference.class), anyString())).thenReturn(preferenceQuery);
    when(preferenceQuery.uniqueResult()).thenReturn(values[0],
        Arrays.copyOfRange(values, 1, values.length));
  }

  @SuppressWarnings("unchecked")
  private static <T extends BaseOBObject> OBQuery<T> queryWithList(List<T> rows) {
    OBQuery<T> query = mock(OBQuery.class);
    when(query.list()).thenReturn(rows);
    return query;
  }

  @SuppressWarnings("unchecked")
  private static <T extends BaseOBObject> OBQuery<T> queryWithUniqueResult(T row) {
    OBQuery<T> query = mock(OBQuery.class);
    when(query.uniqueResult()).thenReturn(row);
    return query;
  }

  private void invokeCopy(String name, Class<?> first, Class<?> second, Class<?> third,
      Client source, Client target, Organization targetOrg) throws Exception {
    Method method = DemoDataTransferService.class.getDeclaredMethod(name, first, second, third);
    method.setAccessible(true);
    method.invoke(service, source, target, targetOrg);
  }

  @SuppressWarnings("unchecked")
  private void preventWorkerSubmission(String clientId) throws Exception {
    Field field = DemoDataTransferService.class.getDeclaredField("activeClients");
    field.setAccessible(true);
    ((Set<String>) field.get(service)).add(clientId);
  }
}
