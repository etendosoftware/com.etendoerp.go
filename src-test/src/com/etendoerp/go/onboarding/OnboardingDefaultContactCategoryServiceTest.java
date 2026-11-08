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

package com.etendoerp.go.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.usageevents.LogCapture;

/**
 * Specifies the ETP-5791 onboarding step that makes "Cliente/Proveedor" the tenant's default
 * contact category while the {@code contact-category-type} flag is on.
 *
 * <p>Each native statement is a mock routed on a distinctive fragment of its SQL.</p>
 *
 * @covers com.etendoerp.go.onboarding.OnboardingDefaultContactCategoryService
 */
class OnboardingDefaultContactCategoryServiceTest {

  private static final String CLIENT_ID = "CLIENT-NEW";
  private static final String ACCOUNT_EMAIL = "owner@example.com";
  private static final String TARGET_ID = "CAT-CLIPROV";

  private static final String BY_MAPPING = "FROM ad_ref_data_loaded";
  private static final String BY_VALUE = "AND value = :value";
  private static final String UNDEFAULT = "SET isdefault = 'N'";
  private static final String DEFAULT = "SET isdefault = 'Y'";

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<GoFeatureFlags> flagsStatic;
  private Session session;
  private NativeQuery<?> byMapping;
  private NativeQuery<?> byValue;
  private NativeQuery<?> undefault;
  private NativeQuery<?> makeDefault;

  private final OnboardingDefaultContactCategoryService service =
      new OnboardingDefaultContactCategoryService();

  @BeforeEach
  void setUp() {
    session = mock(Session.class);
    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    obContextStatic = mockStatic(OBContext.class);
    flagsStatic = mockStatic(GoFeatureFlags.class);
    givenFlag(true);
    byMapping = route(BY_MAPPING);
    byValue = route(BY_VALUE);
    undefault = route(UNDEFAULT);
    makeDefault = route(DEFAULT);
    givenRows(byMapping);
    givenRows(byValue);
  }

  @AfterEach
  void tearDown() {
    flagsStatic.close();
    obContextStatic.close();
    obDalStatic.close();
  }

  @Test
  void flagOffLeavesTheTenantUntouched() {
    givenFlag(false);
    givenRows(byMapping, TARGET_ID);

    assertFalse(service.applyIfEnabled(CLIENT_ID, ACCOUNT_EMAIL));

    verify(session, never()).createNativeQuery(anyString());
  }

  /**
   * Flag on: the category found through the import mapping of GOClient's record becomes the only
   * default — every other default of the tenant is cleared, the target is set.
   */
  @Test
  void flagOnMakesTheMappedCategoryTheOnlyDefault() {
    givenRows(byMapping, TARGET_ID);
    when(undefault.executeUpdate()).thenReturn(1);
    when(makeDefault.executeUpdate()).thenReturn(1);

    assertTrue(service.applyIfEnabled(CLIENT_ID, ACCOUNT_EMAIL));

    verify(byMapping).setParameter("clientId", CLIENT_ID);
    verify(byMapping).setParameter("genericId",
        OnboardingDefaultContactCategoryService.SAMPLE_CUSTOMER_VENDOR_CATEGORY_ID);
    verify(session, never()).createNativeQuery(argThat(sql -> sql.contains(BY_VALUE)));
    verify(undefault).setParameter("clientId", CLIENT_ID);
    verify(undefault).setParameter("targetId", TARGET_ID);
    verify(undefault).executeUpdate();
    verify(makeDefault).setParameter("targetId", TARGET_ID);
    verify(makeDefault).executeUpdate();
  }

  /** Without an import mapping (older dataset) the category is found by its Value. */
  @Test
  void withoutAnImportMappingTheCategoryIsFoundByValue() {
    givenRows(byValue, TARGET_ID);
    when(makeDefault.executeUpdate()).thenReturn(1);

    assertTrue(service.applyIfEnabled(CLIENT_ID, ACCOUNT_EMAIL));

    verify(byValue).setParameter("value",
        OnboardingDefaultContactCategoryService.CUSTOMER_VENDOR_CATEGORY_VALUE);
    verify(makeDefault).setParameter("targetId", TARGET_ID);
  }

  /** A second run finds nothing to change: no row written, nothing for the caller to commit. */
  @Test
  void reRunIsANoOp() {
    givenRows(byMapping, TARGET_ID);
    when(undefault.executeUpdate()).thenReturn(0);
    when(makeDefault.executeUpdate()).thenReturn(0);

    assertFalse(service.applyIfEnabled(CLIENT_ID, ACCOUNT_EMAIL));
  }

  /** A tenant without the category keeps its default: a WARN, no write, no exception. */
  @Test
  void missingCategoryIsLoggedAndSkipped() {
    try (LogCapture logs = LogCapture.of(OnboardingDefaultContactCategoryService.class)) {
      assertFalse(service.applyIfEnabled(CLIENT_ID, ACCOUNT_EMAIL));

      assertTrue(logs.messages(Level.WARN).stream().anyMatch(m -> m.contains(CLIENT_ID)),
          logs.messages(Level.WARN).toString());
    }
    verify(undefault, never()).executeUpdate();
    verify(makeDefault, never()).executeUpdate();
  }

  /**
   * The hand-over runs as System, so the flag is read for the account and tenant passed in — not
   * for the OBContext, whose tenant ("0") and user would target nobody.
   */
  @Test
  void flagIsEvaluatedForTheReceivingAccountAndTenantNotTheOBContext() {
    Client systemClient = mock(Client.class);
    when(systemClient.getId()).thenReturn("0");
    OBContext systemContext = mock(OBContext.class);
    when(systemContext.getCurrentClient()).thenReturn(systemClient);
    obContextStatic.when(OBContext::getOBContext).thenReturn(systemContext);

    service.applyIfEnabled(CLIENT_ID, ACCOUNT_EMAIL);

    ArgumentCaptor<FeatureFlagContext> captor = ArgumentCaptor.forClass(FeatureFlagContext.class);
    flagsStatic.verify(() -> GoFeatureFlags.isEnabled(
        eq(GoFeatureFlags.FLAG_CONTACT_CATEGORY_TYPE), captor.capture()));
    assertEquals(ACCOUNT_EMAIL, captor.getValue().getTargetingKey());
    assertEquals(CLIENT_ID,
        captor.getValue().getAttributes().get(FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
  }

  /** A null tenant short-circuits before the flag is even read. */
  @Test
  void nullClientDoesNothing() {
    assertFalse(service.applyIfEnabled(null, ACCOUNT_EMAIL));
    verify(session, never()).createNativeQuery(anyString());
    flagsStatic.verify(() -> GoFeatureFlags.isEnabled(anyString(), any()), never());
  }

  private void givenFlag(boolean enabled) {
    flagsStatic.when(() -> GoFeatureFlags.isEnabled(
        eq(GoFeatureFlags.FLAG_CONTACT_CATEGORY_TYPE), any())).thenReturn(enabled);
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private NativeQuery<?> route(String fragment) {
    NativeQuery query = mock(NativeQuery.class, RETURNS_SELF);
    when(session.createNativeQuery(argThat((String sql) -> sql != null && sql.contains(fragment))))
        .thenReturn(query);
    return query;
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static void givenRows(NativeQuery query, Object... rows) {
    List<Object> result = rows.length == 0 ? Collections.emptyList() : List.of(rows);
    when(query.getResultList()).thenReturn(result);
  }
}
