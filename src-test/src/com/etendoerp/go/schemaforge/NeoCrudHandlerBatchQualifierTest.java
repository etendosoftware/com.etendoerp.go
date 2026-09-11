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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.DefaultJsonDataService;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoCrudHelper;
import com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper;

/**
 * ETP-5184 — regression guard for the {@code neo_batch} NPE on entities carrying a
 * {@code Java_Qualifier}.
 *
 * <p><b>Defect guarded.</b> {@code executePostCreate} resolved the entity's {@link NeoHandler}
 * through {@code servlet.lookupHandler(javaQualifier)}. That call sits in the DEFAULT create path,
 * which {@link BatchService#forBatchOnly()} is documented to reach with a {@code null} servlet
 * ("only {@code handleWithHooks} touches the owning servlet"). So every {@code neo_batch} create on
 * an entity with a non-blank {@code Java_Qualifier} died with an NPE on {@code this.servlet} and
 * rolled the whole batch back. The fix resolves the handler through the static
 * {@code NeoServletSupport.lookupHandler} instead.</p>
 *
 * <p><b>How far these tests drive the create.</b> {@code DefaultJsonDataService} cannot be mocked
 * in a unit test — its static initializer needs a Weld/CDI context (the same limitation
 * {@code NeoCrudHelperTest} records). So {@code executePostCreate} is invoked with a {@code null}
 * service and runs the whole pipeline up to, and only up to, the DAL write. That is past the
 * handler lookup, which is what matters here: the create either reaches the write (fix present) or
 * dies earlier on {@code this.servlet} (defect present), and
 * {@link #createWithQualifierFailsOnlyAtTheDalWriteNotOnTheServlet()} tells those two apart by
 * name.</p>
 */
class NeoCrudHandlerBatchQualifierTest {

  private static final String QUALIFIER = "internal-consumption-line";
  private static final String DAL_ENTITY = "OrderLine";
  private static final String HANDLER_PROTECTED_FIELD = "priceList";

  /** Fixture bundling the statics the create pipeline reaches past the handler lookup. */
  private static final class Pipeline implements AutoCloseable {
    final MockedStatic<NeoCrudHelper> crudHelper;
    final MockedStatic<NeoMandatoryDefaultsService> mandatoryDefaults;
    final MockedStatic<NeoServletSupport> servletSupport;
    final MockedStatic<NeoDefaultsCascadeHelper> cascade;
    final MockedStatic<DocTypeResolver> docType;
    final MockedStatic<NeoCommercialLinePolicy> linePolicy;
    final MockedStatic<NeoMandatoryFieldValidator> mandatoryValidator;
    final MockedStatic<NeoTypeCoercionHelper> coercion;
    final MockedStatic<ModelProvider> modelProvider;

    Pipeline() {
      crudHelper = mockStatic(NeoCrudHelper.class);
      crudHelper.when(() -> NeoCrudHelper.snapshotBodyFields(any()))
          .thenReturn(new HashSet<>());
      crudHelper.when(() -> NeoCrudHelper.snapshotMandatoryBodyFields(any(), any()))
          .thenReturn(new HashSet<>());

      mandatoryDefaults = mockStatic(NeoMandatoryDefaultsService.class);
      servletSupport = mockStatic(NeoServletSupport.class);
      cascade = mockStatic(NeoDefaultsCascadeHelper.class);
      docType = mockStatic(DocTypeResolver.class);
      linePolicy = mockStatic(NeoCommercialLinePolicy.class);

      mandatoryValidator = mockStatic(NeoMandatoryFieldValidator.class);
      mandatoryValidator.when(() -> NeoMandatoryFieldValidator.findMissingMandatoryFields(any(),
          any(), any())).thenReturn(Collections.emptyList());

      coercion = mockStatic(NeoTypeCoercionHelper.class);
      coercion.when(() -> NeoTypeCoercionHelper.wrapForSmartclient(any(), anyString(), any()))
          .thenReturn("{\"data\":{}}");

      ModelProvider provider = mock(ModelProvider.class);
      modelProvider = mockStatic(ModelProvider.class);
      modelProvider.when(ModelProvider::getInstance).thenReturn(provider);
      when(provider.getEntity(anyString(), anyBoolean())).thenReturn(null);
    }

    @Override
    public void close() {
      // Reverse open order, as Mockito's scope nesting requires.
      modelProvider.close();
      coercion.close();
      mandatoryValidator.close();
      linePolicy.close();
      docType.close();
      cascade.close();
      servletSupport.close();
      mandatoryDefaults.close();
      crudHelper.close();
    }
  }

  private static NeoContext contextWithQualifier(Tab adTab, String qualifier, JSONObject body) {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getJavaQualifier()).thenReturn(qualifier);
    return NeoContext.builder()
        .specName("sales-order")
        .entityName("orderLine")
        .httpMethod("POST")
        .requestBody(body)
        .queryParams(new HashMap<>())
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(mock(OBContext.class))
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private static NeoFieldFilter passThroughFilter(JSONObject body) {
    NeoFieldFilter filter = mock(NeoFieldFilter.class);
    when(filter.filterCreateRequest(any())).thenReturn(body);
    return filter;
  }

  private static NeoHandler handlerProtecting(String... fields) {
    NeoHandler handler = mock(NeoHandler.class);
    when(handler.protectedCreateCalloutFields(any())).thenReturn(Set.of(fields));
    return handler;
  }

  /**
   * Invokes the private {@code executePostCreate} with a {@code null} JSON service, so the create
   * runs to the DAL write and stops there.
   *
   * @return the cause of the failure raised inside {@code executePostCreate}
   */
  private static Throwable executePostCreateToTheDalWrite(NeoCrudHandler handler,
      NeoContext context, Tab adTab, NeoFieldFilter filter) throws Exception {
    Method method = NeoCrudHandler.class.getDeclaredMethod("executePostCreate",
        NeoContext.class, Tab.class, String.class, NeoFieldFilter.class,
        DefaultJsonDataService.class, Map.class);
    method.setAccessible(true);
    InvocationTargetException raised = assertThrows(InvocationTargetException.class,
        () -> method.invoke(handler, context, adTab, DAL_ENTITY, filter, null,
            new HashMap<String, String>()));
    return raised.getCause();
  }

  @Test
  @DisplayName("BatchService.forBatchOnly wires a NeoCrudHandler with a null servlet")
  void batchOnlyServiceHasAServletLessCrudHandler() throws Exception {
    BatchService service = BatchService.forBatchOnly();

    Field crudHandlerField = BatchService.class.getDeclaredField("crudHandler");
    crudHandlerField.setAccessible(true);
    NeoCrudHandler crudHandler = (NeoCrudHandler) crudHandlerField.get(service);

    Field servletField = NeoCrudHandler.class.getDeclaredField("servlet");
    servletField.setAccessible(true);

    // This is the premise of the whole defect: on the batch path there IS no servlet to ask.
    assertNull(servletField.get(crudHandler),
        "the batch path must reach the default create path without a servlet");
  }

  @Test
  @DisplayName("A create with a Java qualifier and no servlet fails at the DAL write, not on the servlet")
  void createWithQualifierFailsOnlyAtTheDalWriteNotOnTheServlet() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", "PROD-1");
    Tab adTab = mock(Tab.class);
    NeoContext context = contextWithQualifier(adTab, QUALIFIER, body);

    try (Pipeline pipeline = new Pipeline()) {
      NeoHandler handler = handlerProtecting(HANDLER_PROTECTED_FIELD);
      pipeline.servletSupport.when(() -> NeoServletSupport.lookupHandler(QUALIFIER))
          .thenReturn(handler);

      // new NeoCrudHandler(null) is exactly what BatchService builds when it has no servlet.
      Throwable cause = executePostCreateToTheDalWrite(new NeoCrudHandler(null), context, adTab,
          passThroughFilter(body));

      // Only the injected null JSON service may be what stopped the create. Before the fix the
      // create never got this far: it died on `this.servlet` at the handler lookup.
      assertInstanceOf(NullPointerException.class, cause);
      String message = cause.getMessage() != null ? cause.getMessage() : "";
      assertFalse(message.contains("lookupHandler"),
          "the handler must not be resolved through the owning servlet: " + message);
      assertFalse(message.contains("servlet"),
          "nothing on the default create path may dereference the servlet: " + message);
    }
  }

  @Test
  @DisplayName("The handler is resolved through the NeoServletSupport static, not the servlet")
  void handlerIsResolvedThroughTheStaticLookup() throws Exception {
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    NeoContext context = contextWithQualifier(adTab, QUALIFIER, body);

    try (Pipeline pipeline = new Pipeline()) {
      NeoHandler handler = handlerProtecting();
      pipeline.servletSupport.when(() -> NeoServletSupport.lookupHandler(anyString()))
          .thenReturn(handler);

      executePostCreateToTheDalWrite(new NeoCrudHandler(null), context, adTab,
          passThroughFilter(body));

      pipeline.servletSupport.verify(() -> NeoServletSupport.lookupHandler(QUALIFIER));
      verify(handler).protectedCreateCalloutFields(context);
    }
  }

  @Test
  @DisplayName("The handler's protected fields still reach the callout cascade on the batch path")
  @SuppressWarnings("unchecked")
  void handlerProtectedFieldsReachTheCascade() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", "PROD-1");
    Tab adTab = mock(Tab.class);
    NeoContext context = contextWithQualifier(adTab, QUALIFIER, body);

    try (Pipeline pipeline = new Pipeline()) {
      NeoHandler handler = handlerProtecting(HANDLER_PROTECTED_FIELD);
      pipeline.servletSupport.when(() -> NeoServletSupport.lookupHandler(anyString()))
          .thenReturn(handler);

      executePostCreateToTheDalWrite(new NeoCrudHandler(null), context, adTab,
          passThroughFilter(body));

      ArgumentCaptor<Set<String>> protectedFields = ArgumentCaptor.forClass(Set.class);
      pipeline.cascade.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(eq(context),
          eq(adTab), any(), any(), protectedFields.capture()));
      assertTrue(protectedFields.getValue().contains(HANDLER_PROTECTED_FIELD),
          "the handler's declared protected field must be honoured by the cascade");
    }
  }

  @Test
  @DisplayName("A blank Java qualifier skips the lookup entirely")
  void blankQualifierSkipsTheLookup() throws Exception {
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    NeoContext context = contextWithQualifier(adTab, "   ", body);

    try (Pipeline pipeline = new Pipeline()) {
      executePostCreateToTheDalWrite(new NeoCrudHandler(null), context, adTab,
          passThroughFilter(body));

      pipeline.servletSupport.verifyNoInteractions();
    }
  }

  @Test
  @DisplayName("An unknown qualifier (no handler registered) does not break the create")
  void unknownQualifierIsTolerated() throws Exception {
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    NeoContext context = contextWithQualifier(adTab, "no-such-handler", body);

    try (Pipeline pipeline = new Pipeline()) {
      pipeline.servletSupport.when(() -> NeoServletSupport.lookupHandler(anyString()))
          .thenReturn(null);

      Throwable cause = executePostCreateToTheDalWrite(new NeoCrudHandler(null), context, adTab,
          passThroughFilter(body));

      // A missing handler is not an error: the create still runs to the DAL write.
      assertInstanceOf(NullPointerException.class, cause);
      pipeline.cascade.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(eq(context),
          eq(adTab), any(), any(), any()));
    }
  }

  @Test
  @DisplayName("A null SFEntity does not attempt any handler lookup")
  void nullSfEntitySkipsTheLookup() throws Exception {
    JSONObject body = new JSONObject();
    Tab adTab = mock(Tab.class);
    NeoContext context = NeoContext.builder()
        .specName("sales-order")
        .entityName("orderLine")
        .httpMethod("POST")
        .requestBody(body)
        .queryParams(new HashMap<>())
        .adTab(adTab)
        .obContext(mock(OBContext.class))
        .endpointType(NeoEndpointType.CRUD)
        .build();

    try (Pipeline pipeline = new Pipeline()) {
      executePostCreateToTheDalWrite(new NeoCrudHandler(null), context, adTab,
          passThroughFilter(body));

      pipeline.servletSupport.verifyNoInteractions();
    }
  }

  @Test
  @DisplayName("The cascade sees the body the field filter returned")
  void theFilteredBodyIsWhatTheCascadeSees() throws Exception {
    JSONObject body = new JSONObject();
    body.put("product", "PROD-1");
    Tab adTab = mock(Tab.class);
    NeoContext context = contextWithQualifier(adTab, QUALIFIER, body);

    try (Pipeline pipeline = new Pipeline()) {
      NeoHandler handler = handlerProtecting();
      pipeline.servletSupport.when(() -> NeoServletSupport.lookupHandler(anyString()))
          .thenReturn(handler);

      executePostCreateToTheDalWrite(new NeoCrudHandler(null), context, adTab,
          passThroughFilter(body));

      ArgumentCaptor<JSONObject> captured = ArgumentCaptor.forClass(JSONObject.class);
      pipeline.cascade.verify(() -> NeoDefaultsCascadeHelper.executeCalloutCascade(eq(context),
          eq(adTab), captured.capture(), any(), any()));
      assertEquals("PROD-1", captured.getValue().getString("product"));
    }
  }
}
