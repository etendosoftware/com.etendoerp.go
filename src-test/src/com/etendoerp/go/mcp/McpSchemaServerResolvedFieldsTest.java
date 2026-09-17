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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.selector.policy.NeoSelectorPolicy;

/**
 * ETP-5368 — the wrapper's own server-resolved fields must reach {@code view:"create"}.
 *
 * <p>{@code C_BPartner_Location.C_Location_ID} is {@code NOT NULL}, so {@code neo_schema} named
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
 */
@DisplayName("ETP-5368 — server-resolved wrapper fields in view:\"create\"")
class McpSchemaServerResolvedFieldsTest {

  private static final String ROUTER = "com/etendoerp/go/mcp/McpToolRouter.java";

  /** The union that carries the wrapper's answer into the set the create view partitions by. */
  private static final Pattern UNION = Pattern.compile(
      "(\\w+)\\s*\\.\\s*addAll\\s*\\(\\s*NeoSelectorPolicy\\s*\\.\\s*serverResolvedFieldNames\\s*\\(");

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
        "handleSchema must union NeoSelectorPolicy.serverResolvedFieldNames into its "
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
