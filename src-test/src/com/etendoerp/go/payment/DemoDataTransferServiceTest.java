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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.plm.ProductCategory;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.tax.TaxCategory;
import org.openbravo.model.materialmgmt.cost.Costing;
import org.openbravo.model.pricing.pricelist.ProductPrice;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;


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
  @Mock private SessionHandler sessionHandler;

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBProvider> obProviderStatic;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<Preferences> preferencesStatic;
  private MockedStatic<SessionHandler> sessionHandlerStatic;
  private DemoDataTransferService service;

  @BeforeEach
  void setUp() {
    obDalStatic = mockStatic(OBDal.class);
    obProviderStatic = mockStatic(OBProvider.class);
    contextStatic = mockStatic(OBContext.class);
    preferencesStatic = mockStatic(Preferences.class);
    sessionHandlerStatic = mockStatic(SessionHandler.class);
    sessionHandlerStatic.when(SessionHandler::getInstance).thenReturn(sessionHandler);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    obProviderStatic.when(OBProvider::getInstance).thenReturn(obProvider);
    service = new DemoDataTransferService();
  }

  @AfterEach
  void tearDown() {
    sessionHandlerStatic.close();
    preferencesStatic.close();
    contextStatic.close();
    obProviderStatic.close();
    obDalStatic.close();
  }

  @Test
  void recordsCheckoutSelectionAtSystemScopeBeforeProvisioning() {
    Client system = mock(Client.class);
    when(obDal.get(Client.class, SYSTEM_ID)).thenReturn(system);
    givenPreferenceReads((Preference) null);

    service.recordSelection(REQUEST_ID, true, false);

    preferencesStatic.verify(() -> Preferences.setPreferenceValue(
        DemoDataTransferService.SELECTION_PREFIX + REQUEST_ID, "YN", false,
        system, null, null, null, null, null));
    verify(obDal).flush();
    verify(obDal).commitAndClose();
  }

  @Test
  void readsTheSelectionRecordedForThePurchase() throws Exception {
    givenPreferenceReads(preference("NY"));

    JSONObject selection = service.selection(REQUEST_ID);

    assertFalse(selection.getBoolean("products"));
    assertTrue(selection.getBoolean("contacts"));
  }

  @Test
  void oldPurchasesWithoutASelectionRemainUnselected() throws Exception {
    givenPreferenceReads((Preference) null);

    assertNull(service.selection(REQUEST_ID));
  }

  @Test
  void purchaseSelectionCannotBeChangedOnReopen() {
    givenPreferenceReads(preference("YN"));

    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> service.recordSelection(REQUEST_ID, false, true));

    assertTrue(error.getMessage().contains("cannot change"));
    preferencesStatic.verifyNoInteractions();
  }

  @Test
  void duplicatePurchaseCreationPreservesTheOriginalSelection() {
    givenPreferenceReads(preference("YN"));

    service.recordSelection(REQUEST_ID, true, false);

    preferencesStatic.verifyNoInteractions();
    verify(obDal, never()).flush();
  }

  @Test
  void keepsTheSelectionPreferenceKeyWithinTheAttributeColumn() {
    // ETP-5443: AD_PREFERENCE.ATTRIBUTE is VARCHAR(60) and the key is the prefix plus a checkout
    // request UUID (HostedCheckoutService mints UUID.randomUUID().toString(), 36 chars).
    String key = DemoDataTransferService.SELECTION_PREFIX + UUID.randomUUID();
    assertTrue(key.length() <= 60, () -> key + " is " + key.length() + " chars");
  }

  @Test
  void createsNoWorkerThreadUntilWorkIsSubmitted() throws Exception {
    // The servlet builds this service at init; until a transfer is submitted no executor may
    // exist. Status reads with no RUNNING job stay lazy too.
    givenPreferenceReads(null, null, null, null, null, null);
    service.status(TARGET_ID);
    assertFalse(service.hasExecutor());
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
  void refusesToCopyWhenSourceAndTargetAreTheSameTenant() throws Exception {
    Client target = mock(Client.class);
    when(obDal.get(Client.class, SOURCE_ID)).thenReturn(target);
    givenPreferenceReads(preference("YY"));

    service.start(REQUEST_ID, SOURCE_ID, SOURCE_ID);

    preferencesStatic.verify(() -> Preferences.setPreferenceValue(
        "ETGO_DemoDataTransferStatus", DemoDataTransferService.STATUS_FAILED, false,
        target, null, null, null, null, null));
    preferencesStatic.verify(() -> Preferences.setPreferenceValue(
        eq("ETGO_DemoDataTransferFailure"), anyString(), eq(false),
        eq(target), eq(null), eq(null), eq(null), eq(null), eq(null)));
    verify(obDal, never()).save(org.mockito.ArgumentMatchers.isA(Product.class));
    verify(obDal, never()).save(org.mockito.ArgumentMatchers.isA(BusinessPartner.class));
  }

  @Test
  void aSelectedTransferWithNoDemoSourceIsFailedAndVisible() throws Exception {
    Client target = mock(Client.class);
    when(obDal.get(Client.class, TARGET_ID)).thenReturn(target);
    givenPreferenceReads(preference("YN"));

    service.start(REQUEST_ID, null, TARGET_ID);

    preferencesStatic.verify(() -> Preferences.setPreferenceValue(
        "ETGO_DemoDataTransferStatus", DemoDataTransferService.STATUS_FAILED, false,
        target, null, null, null, null, null));
    preferencesStatic.verify(() -> Preferences.setPreferenceValue(
        "ETGO_DemoDataTransferFailure", "Source demo environment is unavailable", false,
        target, null, null, null, null, null));
    verify(obDal, never()).save(org.mockito.ArgumentMatchers.isA(Product.class));
  }

  @Test
  void retriesOnlyFailedWorkAndKeepsTheRetryScopedToItsProductiveTenant() throws Exception {
    Client target = mock(Client.class);
    Preference failed = preference(DemoDataTransferService.STATUS_FAILED);
    Preference running = preference(DemoDataTransferService.STATUS_RUNNING);
    when(obDal.get(Client.class, TARGET_ID)).thenReturn(target);
    givenPreferenceReads(failed, preference(SOURCE_ID), running, preference(SOURCE_ID),
        running, null, null, null, null);
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
        preference(DemoDataTransferService.STATUS_COMPLETED),
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
    Client system = client(SYSTEM_ID);
    UOM unit = mock(UOM.class);
    TaxCategory tax = mock(TaxCategory.class);
    when(unit.getClient()).thenReturn(system);
    when(tax.getClient()).thenReturn(system);
    when(sourceProduct.getUOM()).thenReturn(unit);
    when(sourceProduct.getTaxCategory()).thenReturn(tax);
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
    verify(targetProduct).setUOM(unit);
    verify(targetProduct).setTaxCategory(tax);
    verify(obDal).save(targetProduct);
    // Every progress write commits (total, start, then one per product): status reads see the
    // counters while the job runs.
    verify(sessionHandler, times(3)).commitAndStart();
    verify(obDal).createQuery(eq(Product.class), contains("p.active = true"));
  }

  @Test
  void aDemoProductCategoryIsCopiedWithItsMandatoryPlannedMargin() throws Exception {
    Client source = client(SOURCE_ID);
    Client target = client(TARGET_ID);
    Organization targetOrg = mock(Organization.class);
    Product sourceProduct = mock(Product.class);
    Product targetProduct = mock(Product.class);
    Client system = client(SYSTEM_ID);
    UOM unit = mock(UOM.class);
    TaxCategory tax = mock(TaxCategory.class);
    ProductCategory sourceCategory = mock(ProductCategory.class);
    ProductCategory copiedCategory = mock(ProductCategory.class);
    when(unit.getClient()).thenReturn(system);
    when(tax.getClient()).thenReturn(system);
    when(sourceCategory.getClient()).thenReturn(source);
    when(sourceCategory.getName()).thenReturn("Bebidas");
    when(sourceCategory.getPlannedMargin()).thenReturn(BigDecimal.ZERO);
    when(sourceProduct.getUOM()).thenReturn(unit);
    when(sourceProduct.getTaxCategory()).thenReturn(tax);
    when(sourceProduct.getProductCategory()).thenReturn(sourceCategory);
    when(sourceProduct.getSearchKey()).thenReturn("SKU-001");

    OBQuery<Product> sourceProducts = queryWithList(List.of(sourceProduct));
    OBQuery<Product> existingTarget = queryWithUniqueResult(targetProduct);
    OBQuery<ProductCategory> noTargetCategory = queryWithUniqueResult(null);
    OBQuery<ProductPrice> prices = queryWithList(Collections.emptyList());
    OBQuery<Costing> costs = queryWithList(Collections.emptyList());
    when(obDal.createQuery(eq(Product.class), anyString())).thenReturn(sourceProducts, existingTarget);
    when(obDal.createQuery(eq(ProductCategory.class), anyString())).thenReturn(noTargetCategory);
    when(obDal.createQuery(eq(ProductPrice.class), anyString())).thenReturn(prices);
    when(obDal.createQuery(eq(Costing.class), anyString())).thenReturn(costs);
    when(obProvider.get(ProductCategory.class)).thenReturn(copiedCategory);

    invokeCopy("copyProducts", Client.class, Client.class, Organization.class, source, target, targetOrg);

    verify(copiedCategory).setPlannedMargin(BigDecimal.ZERO);
    verify(targetProduct).setProductCategory(copiedCategory);
  }

  @Test
  void missingTargetTaxCategoryFailsInsteadOfLeavingAnInvalidProduct() throws Exception {
    Client source = client(SOURCE_ID);
    Client target = client(TARGET_ID);
    TaxCategory tax = mock(TaxCategory.class);
    when(tax.getClient()).thenReturn(source);
    when(tax.getName()).thenReturn("Source VAT");
    OBQuery<TaxCategory> missingCategory = queryWithUniqueResult(null);
    when(obDal.createQuery(eq(TaxCategory.class), anyString()))
        .thenReturn(missingCategory);
    Method method = DemoDataTransferService.class.getDeclaredMethod(
        "targetTaxCategory", TaxCategory.class, Client.class);
    method.setAccessible(true);

    InvocationTargetException error = assertThrows(InvocationTargetException.class,
        () -> method.invoke(service, tax, target));

    assertTrue(error.getCause().getMessage().contains("Source VAT"));
  }

  @Test
  void aSourcePriceWithoutATargetPriceListVersionFailsTheTransfer() throws Exception {
    Product sourceProduct = mock(Product.class);
    Product targetProduct = mock(Product.class);
    Client targetClient = client(TARGET_ID);
    Organization targetOrg = mock(Organization.class);
    ProductPrice sourcePrice = mock(ProductPrice.class);
    PriceListVersion sourceVersion = mock(PriceListVersion.class);
    PriceList sourceList = mock(PriceList.class);
    when(sourceProduct.getId()).thenReturn("source-product");
    when(targetOrg.getId()).thenReturn("target-org");
    when(sourcePrice.getPriceListVersion()).thenReturn(sourceVersion);
    when(sourceVersion.getPriceList()).thenReturn(sourceList);
    when(sourceList.isSalesPriceList()).thenReturn(true);
    when(sourceList.isDefault()).thenReturn(true);
    OBQuery<ProductPrice> sourcePrices = queryWithList(List.of(sourcePrice));
    when(obDal.createQuery(eq(ProductPrice.class), anyString()))
        .thenReturn(sourcePrices);
    OBCriteria<PriceListVersion> missingTargetVersions = mock(OBCriteria.class);
    when(obDal.createCriteria(PriceListVersion.class)).thenReturn(missingTargetVersions);
    when(missingTargetVersions.list()).thenReturn(Collections.emptyList());
    Method method = DemoDataTransferService.class.getDeclaredMethod("copyPrices",
        Product.class, Product.class, Client.class, Organization.class);
    method.setAccessible(true);

    InvocationTargetException error = assertThrows(InvocationTargetException.class,
        () -> method.invoke(service, sourceProduct, targetProduct, targetClient, targetOrg));
    assertTrue(error.getCause().getMessage().contains("sales price list version is missing"));
    verify(obProvider, never()).get(ProductPrice.class);
  }

  @Test
  void aPriceOnANonDefaultPriceListIsNotMigrated() throws Exception {
    Product sourceProduct = mock(Product.class);
    ProductPrice sourcePrice = mock(ProductPrice.class);
    PriceListVersion sourceVersion = mock(PriceListVersion.class);
    PriceList secondaryList = mock(PriceList.class);
    when(sourceProduct.getId()).thenReturn("source-product");
    when(sourcePrice.getPriceListVersion()).thenReturn(sourceVersion);
    when(sourceVersion.getPriceList()).thenReturn(secondaryList);
    when(secondaryList.isSalesPriceList()).thenReturn(true);
    when(secondaryList.isDefault()).thenReturn(false);
    OBQuery<ProductPrice> sourcePrices = queryWithList(List.of(sourcePrice));
    when(obDal.createQuery(eq(ProductPrice.class), anyString())).thenReturn(sourcePrices);
    Method method = DemoDataTransferService.class.getDeclaredMethod("copyPrices",
        Product.class, Product.class, Client.class, Organization.class);
    method.setAccessible(true);

    method.invoke(service, sourceProduct, mock(Product.class), client(TARGET_ID),
        mock(Organization.class));

    verify(obDal, never()).createCriteria(PriceListVersion.class);
    verify(obProvider, never()).get(ProductPrice.class);
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
    OBQuery<org.openbravo.model.common.businesspartner.Location> emptyPartnerLocations =
        queryWithList(Collections.emptyList());
    OBQuery<org.openbravo.model.common.geography.Location> emptyAddresses =
        queryWithList(Collections.emptyList());
    OBQuery<User> emptyUsers = queryWithList(Collections.emptyList());
    when(obDal.createQuery(eq(org.openbravo.model.common.businesspartner.Location.class), anyString()))
        .thenReturn(emptyPartnerLocations, emptyPartnerLocations);
    when(obDal.createQuery(eq(org.openbravo.model.common.geography.Location.class), anyString()))
        .thenReturn(emptyAddresses);
    when(obDal.createQuery(eq(User.class), anyString())).thenReturn(emptyUsers, emptyUsers);

    invokeCopy("copyContacts", Client.class, Client.class, Organization.class, source, target, targetOrg);

    verify(existingTarget).setNamedParameter("clientId", TARGET_ID);
    verify(obProvider, never()).get(BusinessPartner.class);
    verify(targetContact).setSearchKey("CONTACT-001");
    verify(targetContact).setTaxID("");
    verify(obDal).save(targetContact);
    // The organization's fiscal address shares the table: only contact addresses are reusable.
    verify(obDal).createQuery(eq(org.openbravo.model.common.geography.Location.class),
        contains("BusinessPartnerLocation"));
    verify(sessionHandler, times(3)).commitAndStart();
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
