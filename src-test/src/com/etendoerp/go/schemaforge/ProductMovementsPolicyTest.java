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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * Unit tests for {@link ProductMovementsPolicy} (ETP-5889): the flag is evaluated per account, with the
 * account of the request and its tenant, and what the flag answers is what the policy answers.
 *
 * @covers com.etendoerp.go.schemaforge.ProductMovementsPolicy
 */
class ProductMovementsPolicyTest {

  private static OBContext contextOfTenant(String clientId, boolean tenantLookupFails) {
    OBContext obContext = mock(OBContext.class);
    if (tenantLookupFails) {
      when(obContext.getCurrentClient()).thenThrow(new IllegalStateException("no tenant"));
    } else if (clientId != null) {
      Client client = mock(Client.class);
      when(client.getId()).thenReturn(clientId);
      when(obContext.getCurrentClient()).thenReturn(client);
    }
    return obContext;
  }

  private static FeatureFlagContext capture(MockedStatic<GoFeatureFlags> flags) {
    ArgumentCaptor<FeatureFlagContext> context = ArgumentCaptor.forClass(FeatureFlagContext.class);
    flags.verify(() -> GoFeatureFlags.isEnabled(eq(GoFeatureFlags.FLAG_PRODUCT_MOVEMENTS_TAB), context.capture()));
    return context.getValue();
  }

  /** The request's flag read targets the account behind the session user, plus its tenant. */
  @Test
  void isEnabledTargetsTheAccountOfTheRequestAndItsTenant() {
    try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class);
         MockedStatic<GoAccountResolver> resolver = mockStatic(GoAccountResolver.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      resolver.when(GoAccountResolver::currentAccountEmail).thenReturn("Owner@Example.com");
      OBContext current = contextOfTenant("CLI-1", false);
      obContext.when(OBContext::getOBContext).thenReturn(current);
      flags.when(() -> GoFeatureFlags.isEnabled(eq(GoFeatureFlags.FLAG_PRODUCT_MOVEMENTS_TAB), any()))
          .thenReturn(true);

      assertTrue(ProductMovementsPolicy.isEnabled());

      FeatureFlagContext context = capture(flags);
      assertEquals("Owner@Example.com", context.getTargetingKey());
      assertEquals(Map.of(
          FeatureFlagContext.ATTRIBUTE_EMAIL, "Owner@Example.com",
          FeatureFlagContext.ATTRIBUTE_EMAIL_LOWERCASE, "owner@example.com",
          FeatureFlagContext.ATTRIBUTE_CLIENT_ID, "CLI-1"), context.getAttributes());
    }
  }

  /** The policy answers what the flag answers: on and off. */
  @Test
  void isEnabledForReturnsWhatTheFlagAnswers() {
    for (boolean answer : new boolean[] {true, false}) {
      try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class);
           MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
        OBContext current = contextOfTenant("CLI-1", false);
        obContext.when(OBContext::getOBContext).thenReturn(current);
        flags.when(() -> GoFeatureFlags.isEnabled(eq(GoFeatureFlags.FLAG_PRODUCT_MOVEMENTS_TAB), any()))
            .thenReturn(answer);

        assertEquals(answer, ProductMovementsPolicy.isEnabledFor("owner@example.com"));
      }
    }
  }

  /** A caller that already resolved the account hands it in: the policy does not resolve another. */
  @Test
  void isEnabledForUsesTheGivenAccountWithoutResolvingAnother() {
    try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class);
         MockedStatic<GoAccountResolver> resolver = mockStatic(GoAccountResolver.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBContext current = contextOfTenant("CLI-1", false);
      obContext.when(OBContext::getOBContext).thenReturn(current);

      ProductMovementsPolicy.isEnabledFor("given@example.com");

      assertEquals("given@example.com", capture(flags).getTargetingKey());
      resolver.verifyNoInteractions();
    }
  }

  /**
   * A request with no account is evaluated account-less: no targeting key, so no per-account rule can
   * match. A tenant that cannot be read is left out; neither case fails the request.
   */
  @Test
  void noAccountIsEvaluatedAccountLessAndAnUnreadableTenantIsLeftOut() {
    try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      OBContext current = contextOfTenant(null, true);
      obContext.when(OBContext::getOBContext).thenReturn(current);

      assertFalse(ProductMovementsPolicy.isEnabledFor(null));

      FeatureFlagContext context = capture(flags);
      assertNull(context.getTargetingKey());
      assertEquals(Map.of(), context.getAttributes());
    }
  }

  /** A user with no ETGO_ACCOUNT behind it (resolver answers null) is evaluated account-less, never as "everyone". */
  @Test
  void isEnabledWithNoResolvableAccountIsEvaluatedAccountLess() {
    try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class);
         MockedStatic<GoAccountResolver> resolver = mockStatic(GoAccountResolver.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      resolver.when(GoAccountResolver::currentAccountEmail).thenReturn(null);
      OBContext current = contextOfTenant("CLI-1", false);
      obContext.when(OBContext::getOBContext).thenReturn(current);
      flags.when(() -> GoFeatureFlags.isEnabled(eq(GoFeatureFlags.FLAG_PRODUCT_MOVEMENTS_TAB), any()))
          .thenReturn(false);

      assertFalse(ProductMovementsPolicy.isEnabled());

      FeatureFlagContext context = capture(flags);
      assertNull(context.getTargetingKey());
      assertFalse(context.getAttributes().containsKey(FeatureFlagContext.ATTRIBUTE_EMAIL));
      assertEquals("CLI-1", context.getAttributes().get(FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    }
  }

  /** With no OBContext at all there is no tenant either, and the evaluation still completes. */
  @Test
  void noContextMeansNoTenantAttribute() {
    try (MockedStatic<GoFeatureFlags> flags = mockStatic(GoFeatureFlags.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      obContext.when(OBContext::getOBContext).thenReturn(null);

      ProductMovementsPolicy.isEnabledFor("owner@example.com");

      assertFalse(capture(flags).getAttributes().containsKey(FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
    }
  }
}
