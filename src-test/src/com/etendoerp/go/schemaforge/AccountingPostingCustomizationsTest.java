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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Named;

import org.apache.logging.log4j.Level;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.base.weld.WeldUtils;

import com.etendoerp.go.schemaforge.handlers.AssetAccountingHandler;
import com.etendoerp.go.schemaforge.handlers.AssetGroupAccountingHandler;
import com.etendoerp.go.schemaforge.handlers.GLJournalLineHandler;
import com.etendoerp.go.schemaforge.handlers.GeneralLedgerAccountsHandler;
import com.etendoerp.go.schemaforge.handlers.GeneralLedgerDefaultsHandler;
import com.etendoerp.go.schemaforge.handlers.WarehouseAccountingHandler;
import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;
import com.etendoerp.go.schemaforge.util.PostingAccountGuard;
import com.etendoerp.go.usageevents.LogCapture;

/**
 * ETP-5693: the accounting tabs without a {@code Java_Qualifier} are customized by
 * {@link NeoExtension} only, and REST now dispatches qualifier-less entities through the hook
 * pipeline, so those customizations run there too. {@link NeoExtensionIndex} is built from the
 * real annotations; only {@link WeldUtils}'s bean manager is mocked.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.WarehouseAccountingHandler
 * @covers com.etendoerp.go.schemaforge.handlers.AssetGroupAccountingHandler
 * @covers com.etendoerp.go.schemaforge.handlers.AssetAccountingHandler
 * @covers com.etendoerp.go.schemaforge.handlers.GeneralLedgerDefaultsHandler
 * @covers com.etendoerp.go.schemaforge.handlers.GeneralLedgerAccountsHandler
 * @covers com.etendoerp.go.schemaforge.handlers.GLJournalLineHandler
 * @covers com.etendoerp.go.schemaforge.NeoServletSupport
 * @covers com.etendoerp.go.schemaforge.NeoExtensionDispatcher
 */
class AccountingPostingCustomizationsTest {

  static Stream<Arguments> customizations() {
    return Stream.of(
        Arguments.of(WarehouseAccountingHandler.class, "warehouse", "accounting"),
        Arguments.of(AssetGroupAccountingHandler.class, "asset-group", "accounting"),
        Arguments.of(AssetAccountingHandler.class, "assets", "assetAcct"),
        Arguments.of(GeneralLedgerDefaultsHandler.class, "general-ledger-configuration",
            "Valores por defecto"),
        Arguments.of(GeneralLedgerAccountsHandler.class, "general-ledger-configuration",
            "Cuentas generales"),
        Arguments.of(GLJournalLineHandler.class, "manual-journals", "gLJournalLine"));
  }

  private MockedStatic<WeldUtils> weld;

  @BeforeEach
  void deployCustomizations() throws Exception {
    NeoExtensionIndex.invalidate();
    BeanManager bm = mock(BeanManager.class);
    Set<Bean<?>> beans = new LinkedHashSet<>();
    for (Arguments args : (Iterable<Arguments>) customizations()::iterator) {
      Class<?> type = (Class<?>) args.get()[0];
      beans.add(deployedBean(bm, (NeoHandler) type.getDeclaredConstructor().newInstance()));
    }
    when(bm.getBeans(NeoHandler.class, WeldUtils.ANY_LITERAL)).thenReturn(beans);
    weld = mockStatic(WeldUtils.class);
    weld.when(WeldUtils::getStaticInstanceBeanManager).thenReturn(bm);
  }

  @AfterEach
  void dropIndex() {
    weld.close();
    NeoExtensionIndex.invalidate();
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static Bean<?> deployedBean(BeanManager bm, NeoHandler instance) {
    Bean bean = mock(Bean.class);
    doReturn(instance.getClass()).when(bean).getBeanClass();
    doReturn(instance).when(bm).getReference(same(bean), eq(NeoHandler.class), any());
    return bean;
  }

  private static NeoContext patch(String spec, String entity) {
    return NeoContext.builder()
        .specName(spec)
        .entityName(entity)
        .httpMethod("PATCH")
        .recordId("row-1")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(new JSONObject())
        .build();
  }

  @ParameterizedTest(name = "{1}/{2}")
  @MethodSource("customizations")
  @DisplayName("Bound by @NeoExtension only, and resolved by the index with no qualifier")
  void boundByAnnotationAndResolvedWithoutQualifier(Class<?> handler, String spec, String entity) {
    NeoExtension binding = handler.getAnnotation(NeoExtension.class);
    assertNotNull(binding);
    assertEquals(spec, binding.spec());
    assertEquals(entity, binding.entity());
    assertNull(handler.getAnnotation(Named.class));

    NeoHandler resolved = NeoExtensionIndex.resolve(spec, entity, null);
    assertNotNull(resolved, spec + "/" + entity + " must resolve by annotation");
    assertEquals(handler, resolved.getClass());
  }

  @Test
  @DisplayName("REST, blank qualifier: the annotation-only pre-hook runs and its refusal short-circuits CRUD")
  void restRunsAnnotationOnlyPreHook() {
    NeoResponse refusal = NeoResponse.error(400, "refused");
    AtomicBoolean defaultRan = new AtomicBoolean();
    NeoResponse response;
    try (MockedStatic<PostingAccountGuard> guard = mockStatic(PostingAccountGuard.class)) {
      guard.when(() -> PostingAccountGuard.checkWrite(any(), any())).thenReturn(refusal);
      response = NeoServletSupport.handleWithDefaultStep(null, patch("warehouse", "accounting"),
          ctx -> {
            defaultRan.set(true);
            return NeoResponse.ok(new JSONObject());
          }, NeoExtensionChannel.REST_SINGLE);
    }
    assertSame(refusal, response);
    assertFalse(defaultRan.get(), "a refused write must not reach default CRUD");
  }

  @Test
  @DisplayName("REST, blank qualifier and no customization: default CRUD runs and no INFO trace is logged")
  void restWithoutCustomizationFallsBackQuietly() {
    NeoResponse ok = NeoResponse.ok(new JSONObject());
    NeoResponse response;
    try (LogCapture log = LogCapture.of(NeoExtensionDispatcher.class)) {
      response = NeoServletSupport.handleWithDefaultStep(null, patch("product", "header"),
          ctx -> ok, NeoExtensionChannel.REST_SINGLE);
      assertTrue(log.messages(Level.INFO).isEmpty(), String.valueOf(log.messages(Level.INFO)));
    }
    assertSame(ok, response);
  }
}
