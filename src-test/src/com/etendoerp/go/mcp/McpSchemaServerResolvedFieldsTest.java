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

package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.logging.log4j.Logger;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoExtensionDispatcher;
import com.etendoerp.go.schemaforge.NeoExtensionRequest;
import com.etendoerp.go.schemaforge.NeoExtensionSurface;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.SalesQuotationLineHandler;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.selector.policy.NeoSelectorPolicy;

/**
 * ETP-5368 — the wrapper's own server-resolved fields must reach {@code view:"create"}.
 *
 * <p>{@code C_BPartner_Location.C_Location_ID} is {@code NOT NULL}, so {@code etendo_schema} named
 * {@code locationAddress} as the one field an agent MUST send. That instruction pointed at the
 * reuse-an-existing-C_Location mode, which needs an id no contacts endpoint can produce, while the
 * mode the SPA always uses — hand over the raw address fields — was not advertised at all, and
 * sending both is a 400. The fix reports the field as server-resolved so it lands in
 * {@code optional} carrying {@code serverDefaulted:true}.</p>
 *
 * <p>Two levels, because the failure modes are different: the name coupling is behavioural and
 * asserted as such, while the union itself is a <b>call site</b> inside {@code handleSchema} —
 * deleting that one line leaves every other test passing, and the method needs an OBContext, a
 * live DAL and an AD_Tab, so it cannot be reached from a unit test. That is the case
 * {@link McpSourceScanner} exists for.</p>
 *
 * <p>The same helper also answers the read side ({@link McpServerResolvedFields#enrichedOnRead},
 * ETP-5576): the keys a customization injects on every GET record.</p>
 *
 * @covers com.etendoerp.go.mcp.McpServerResolvedFields
 */
@DisplayName("ETP-5368 / ETP-5535 — server-resolved fields in view:\"create\" and etendo_create")
class McpSchemaServerResolvedFieldsTest {

  private static final String ROUTER = "com/etendoerp/go/mcp/McpToolRouter.java";

  /**
   * The union that carries the wrapper's answer into the set the create view partitions by. Since
   * ETP-5535 it goes through {@link McpServerResolvedFields#forCreate}, which wraps
   * {@code NeoSelectorPolicy.serverResolvedFieldNames} and adds the customization's declaration.
   */
  private static final Pattern UNION = Pattern.compile(
      "(\\w+)\\s*\\.\\s*addAll\\s*\\(\\s*McpServerResolvedFields\\s*\\.\\s*forCreate\\s*\\(");

  // ── behavioural: the name the policy publishes is the name the view matches ─────

  @Test
  @DisplayName("the field the policy names is demoted to optional, flagged serverDefaulted")
  void policyNameDemotesTheField() throws Exception {
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("C_BPartner_Location");
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getADTab()).thenReturn(tab);

    Property property = mock(Property.class);
    when(property.getName()).thenReturn("locationAddress");
    Entity dalEntity = mock(Entity.class);
    when(dalEntity.getPropertyByColumnName("C_Location_ID", false)).thenReturn(property);
    ModelProvider modelProvider = mock(ModelProvider.class);
    when(modelProvider.getEntityByTableName("C_BPartner_Location")).thenReturn(dalEntity);

    JSONArray fields = new JSONArray();
    // NOT NULL in AD, hence userRequired — exactly the descriptor the bug produced.
    fields.put(field("locationAddress", true));
    fields.put(field("phone", false));

    try (MockedStatic<ModelProvider> models = mockStatic(ModelProvider.class)) {
      models.when(ModelProvider::getInstance).thenReturn(modelProvider);

      Set<String> serverResolved = NeoSelectorPolicy.serverResolvedFieldNames(sfEntity);
      JSONObject response = McpSchemaCreateView.buildResponse("contacts", "locationAddress",
          fields, serverResolved);

      // The whole point: nothing is left in `required`, so the agent is never told to produce an
      // id it has no endpoint for. Asserting only the policy's return value would not catch a
      // column name being returned where a field name is matched.
      assertEquals(0, response.getInt("requiredCount"));
      assertEquals(2, response.getInt("optionalCount"));
      JSONObject demoted = response.getJSONArray("optional").getJSONObject(0);
      assertEquals("locationAddress", demoted.getString("name"));
      assertTrue(demoted.getBoolean("serverDefaulted"));
      assertFalse(response.getJSONArray("optional").getJSONObject(1).has("serverDefaulted"));
    }
  }

  // ── structural: the call site in handleSchema ───────────────────────────────────

  @Test
  @DisplayName("handleSchema unions the wrapper's fields into the set it hands the create view")
  void handleSchemaUnionsTheWrapperFields() {
    String body = McpSourceScanner.methodBody(McpSourceScanner.read(ROUTER), "handleSchema");

    Matcher union = UNION.matcher(body);
    assertTrue(union.find(),
        "handleSchema must union McpServerResolvedFields.forCreate into its "
            + "server-resolved set — without it view:\"create\" keeps demanding locationAddress");

    String setVariable = union.group(1);
    // From the union onwards: handleSchema also builds the view:"actions" response earlier in the
    // method, and that call legitimately takes no server-resolved set.
    int call = body.indexOf("buildResponse(", union.end());
    assertTrue(call >= 0, "handleSchema must still build the create view response");
    String arguments = body.substring(call, statementEnd(body, call));
    assertTrue(arguments.contains(setVariable),
        "the unioned set (" + setVariable + ") must be the one passed to buildResponse");
  }

  // ── ETP-5535: McpServerResolvedFields.forCreate ─────────────────────────

  /**
   * The policy's names and the customization's declaration are one set. The real
   * {@link SalesQuotationLineHandler} is the customization, so its {@code tax} declaration is
   * exercised through the reader that consumes it.
   */
  @Test
  @DisplayName("forCreate is the union of the policy's names and the customization's declaration")
  void forCreateUnionsPolicyAndCustomization() {
    SFEntity sfEntity = quotationLineEntity();
    NeoHandler customization = new SalesQuotationLineHandler();

    try (MockedStatic<NeoSelectorPolicy> policy = mockStatic(NeoSelectorPolicy.class);
        MockedStatic<NeoExtensionDispatcher> dispatcher =
            mockStatic(NeoExtensionDispatcher.class)) {
      policy.when(() -> NeoSelectorPolicy.serverResolvedFieldNames(sfEntity))
          .thenReturn(Set.of("locationAddress"));
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(customization);

      assertEquals(Set.of("locationAddress", "tax"), McpServerResolvedFields.forCreate(sfEntity));
    }
  }

  @Test
  @DisplayName("forCreate of a null entity is empty")
  void forCreateOfNullEntityIsEmpty() {
    assertTrue(McpServerResolvedFields.forCreate(null).isEmpty());
  }

  /** A customization whose declaration is {@code null} rather than an empty set. */
  private static final NeoHandler DECLARES_NULL = new NeoHandler() {
    @Override
    public NeoResponse handle(NeoContext context) {
      return null;
    }

    @Override
    public Set<String> serverResolvedCreateFields() {
      return null;
    }
  };

  /** Rows: case, how {@code NeoExtensionDispatcher.resolveOnly} is stubbed. */
  static Stream<Arguments> unusableCustomizations() {
    Consumer<MockedStatic<NeoExtensionDispatcher>> throwing = dispatcher -> dispatcher
        .when(() -> NeoExtensionDispatcher.resolveOnly(any()))
        .thenThrow(new IllegalStateException("CDI not ready"));
    Consumer<MockedStatic<NeoExtensionDispatcher>> unbound = dispatcher -> dispatcher
        .when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(null);
    Consumer<MockedStatic<NeoExtensionDispatcher>> declaresNull = dispatcher -> dispatcher
        .when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(DECLARES_NULL);
    return Stream.of(
        Arguments.of("the resolution throws", throwing),
        Arguments.of("no customization is bound", unbound),
        Arguments.of("the customization declares null", declaresNull));
  }

  /**
   * A customization that cannot be read contributes nothing — the field stays required, which is
   * the pre-ETP-5535 behaviour — and never costs the policy's own names.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("unusableCustomizations")
  void forCreateKeepsOnlyThePolicyWhenTheCustomizationIsUnusable(String scenario,
      Consumer<MockedStatic<NeoExtensionDispatcher>> stubDispatcher) {
    SFEntity sfEntity = quotationLineEntity();

    try (MockedStatic<NeoSelectorPolicy> policy = mockStatic(NeoSelectorPolicy.class);
        MockedStatic<NeoExtensionDispatcher> dispatcher =
            mockStatic(NeoExtensionDispatcher.class)) {
      policy.when(() -> NeoSelectorPolicy.serverResolvedFieldNames(sfEntity))
          .thenReturn(Set.of("locationAddress"));
      stubDispatcher.accept(dispatcher);

      assertEquals(Set.of("locationAddress"), McpServerResolvedFields.forCreate(sfEntity),
          scenario);
    }
  }

  // ── ETP-5535: the etendo_create mandatory pre-check does NOT skip declared fields ─────

  /** A customization that injects two keys on every GET record (ETP-5576). */
  private static final NeoHandler DECLARES_ENRICHED_KEYS = new NeoHandler() {
    @Override
    public NeoResponse handle(NeoContext context) {
      return null;
    }

    @Override
    public Set<String> responseEnrichedFields() {
      return Set.of("followUp", "arInvoiceSubtype");
    }
  };

  @Test
  @DisplayName("enrichedOnRead of a null entity is empty")
  void enrichedOnReadOfNullEntityIsEmpty() {
    assertTrue(McpServerResolvedFields.enrichedOnRead(null).isEmpty());
  }

  /** Rows: case, how {@code NeoExtensionDispatcher.resolveOnly} is stubbed, the expected keys. */
  static Stream<Arguments> readCustomizations() {
    Consumer<MockedStatic<NeoExtensionDispatcher>> throwing = dispatcher -> dispatcher
        .when(() -> NeoExtensionDispatcher.resolveOnly(any()))
        .thenThrow(new IllegalStateException("CDI not ready"));
    Consumer<MockedStatic<NeoExtensionDispatcher>> declaring = dispatcher -> dispatcher
        .when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(DECLARES_ENRICHED_KEYS);
    return Stream.of(
        Arguments.of("the resolution throws", throwing, Set.of()),
        Arguments.of("the customization declares keys", declaring,
            Set.of("followUp", "arInvoiceSubtype")));
  }

  /**
   * The read side asks the customization bound to the READ surface — not the CREATE one
   * {@code forCreate} asks — and answers its declaration; a resolution failure answers empty, so
   * the projection validator judges every name as it did before ETP-5576 instead of failing the
   * read.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("readCustomizations")
  void enrichedOnReadAnswersTheReadCustomizationsDeclaration(String scenario,
      Consumer<MockedStatic<NeoExtensionDispatcher>> stubDispatcher, Set<String> expected) {
    SFEntity sfEntity = quotationLineEntity();

    try (MockedStatic<NeoExtensionDispatcher> dispatcher =
        mockStatic(NeoExtensionDispatcher.class)) {
      stubDispatcher.accept(dispatcher);

      Set<String> keys = assertDoesNotThrow(
          () -> McpServerResolvedFields.enrichedOnRead(sfEntity), scenario);

      assertEquals(expected, keys, scenario);
      dispatcher.verify(() -> NeoExtensionDispatcher.resolveOnly(
          argThat((NeoExtensionRequest request) -> request.surface() == NeoExtensionSurface.READ)));
    }
  }

  /**
   * Rows: case, whether {@link SalesQuotationLineHandler} is bound, the names the selector policy
   * (wrapper) answers, the expected missing fields. The last row pins that a selector-policy name
   * is still skipped, because the wrapper builds its value only later, in the handler.
   */
  static Stream<Arguments> mandatoryPreCheckCases() {
    return Stream.of(
        Arguments.of("customization declares tax", true, Set.of(), List.of("tax", "product")),
        Arguments.of("no customization", false, Set.of(), List.of("tax", "product")),
        Arguments.of("a selector-policy name is still skipped", true, Set.of("tax"),
            List.of("product")));
  }

  /**
   * {@code C_OrderLine.C_Tax_ID} and {@code M_Product_ID} are both mandatory and the body carries
   * neither. The pre-check runs after the create cascade that derives a declared field, so a
   * declared field still empty there is a real gap: even with {@link SalesQuotationLineHandler}
   * declaring {@code tax}, both are reported (ETP-5535).
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("mandatoryPreCheckCases")
  void validateMandatoryFieldsReportsTheCustomizationsDeclaredFields(String scenario,
      boolean withCustomization, Set<String> policyNames, List<String> expectedMissing)
      throws Exception {
    Column taxColumn = mandatoryColumn("C_Tax_ID", "Tax");
    Column productColumn = mandatoryColumn("M_Product_ID", "Product");
    Table table = mock(Table.class);
    when(table.getDBTableName()).thenReturn("C_OrderLine");
    when(table.getADColumnList()).thenReturn(List.of(taxColumn, productColumn));
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    Property taxProperty = namedProperty("tax");
    Property productProperty = namedProperty("product");
    Entity dalEntity = mock(Entity.class);
    when(dalEntity.getPropertyByColumnName("C_Tax_ID")).thenReturn(taxProperty);
    when(dalEntity.getPropertyByColumnName("M_Product_ID")).thenReturn(productProperty);
    SFEntity sfEntity = quotationLineEntity();
    NeoHandler customization = withCustomization ? new SalesQuotationLineHandler() : null;

    JSONArray missing;
    try (MockedStatic<NeoSelectorPolicy> policy = mockStatic(NeoSelectorPolicy.class);
        MockedStatic<NeoExtensionDispatcher> dispatcher =
            mockStatic(NeoExtensionDispatcher.class)) {
      policy.when(() -> NeoSelectorPolicy.serverResolvedFieldNames(sfEntity))
          .thenReturn(policyNames);
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any())).thenReturn(customization);

      missing = McpWriteRequestSupport.validateMandatoryFields(new JSONObject(), tab, dalEntity,
          new HashSet<>(), new HashSet<>(), sfEntity, mock(Logger.class));
    }

    List<String> names = new ArrayList<>();
    for (int i = 0; i < missing.length(); i++) {
      names.add(missing.getJSONObject(i).getString("name"));
    }
    assertEquals(expectedMissing, names, scenario);
  }

  private static SFEntity quotationLineEntity() {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getName()).thenReturn("sales-quotation");
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getName()).thenReturn("quotationLine");
    when(sfEntity.getETGOSFSpec()).thenReturn(spec);
    return sfEntity;
  }

  private static Column mandatoryColumn(String dbColumnName, String label) {
    Column column = mock(Column.class);
    when(column.isActive()).thenReturn(true);
    when(column.isMandatory()).thenReturn(true);
    when(column.getDBColumnName()).thenReturn(dbColumnName);
    when(column.getName()).thenReturn(label);
    return column;
  }

  private static Property namedProperty(String name) {
    Property property = mock(Property.class);
    when(property.getName()).thenReturn(name);
    return property;
  }

  private static int statementEnd(String body, int from) {
    int end = body.indexOf(';', from);
    return end < 0 ? body.length() : end;
  }

  private static JSONObject field(String name, boolean userRequired) throws Exception {
    JSONObject fieldObj = new JSONObject();
    fieldObj.put("name", name);
    fieldObj.put("type", "string");
    fieldObj.put("visibility", "editable");
    fieldObj.put("readOnly", false);
    fieldObj.put(McpSchemaFieldBuilder.KEY_USER_REQUIRED, userRequired);
    return fieldObj;
  }
}
