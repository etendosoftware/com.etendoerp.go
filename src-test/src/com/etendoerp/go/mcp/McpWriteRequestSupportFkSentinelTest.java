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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@code McpWriteRequestSupport#resolveFkSentinels}: what a create/batch body
 * becomes when a foreign key carries {@code "0"}.
 *
 * <p>{@code "0"} is two different things depending on the target. On {@code C_DocType_ID} it is the
 * AD placeholder "resolve from {@code C_DocTypeTarget_ID}", and the sibling copy is the fix. On
 * {@code M_AttributeSetInstance_ID} or {@code AD_Org_ID} it is a real record ("no attributes", the
 * {@code *} organization), and stripping it writes something the agent did not ask for — the org
 * case can land the record in another organization. The entity names below are mock names: the
 * rule under test is structural (does a record with id {@code "0"} exist in the target), never a
 * list of entities.
 *
 * @covers com.etendoerp.go.mcp.McpWriteRequestSupport
 */
class McpWriteRequestSupportFkSentinelTest {

  private static final Logger LOG = LogManager.getLogger();
  private static final String ZERO = "0";

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;
  private OBDal obDal;

  @BeforeEach
  void setUp() {
    obDal = mock(OBDal.class);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(obDal);
    contextStatic = mockStatic(OBContext.class);
  }

  @AfterEach
  void tearDown() {
    contextStatic.close();
    dalStatic.close();
  }

  /** Declares which target entities hold a record whose id is {@code "0"}. */
  private void zeroRecordExistsIn(Set<String> entityNames) {
    when(obDal.get(anyString(), eq(ZERO))).thenAnswer(inv ->
        entityNames.contains(inv.<String>getArgument(0)) ? mock(BaseOBObject.class) : null);
  }

  /** A DAL entity whose given properties are FKs to the given target entity names. */
  private static Entity entityWithFks(Map<String, String> propertyToTarget) {
    Entity entity = mock(Entity.class);
    propertyToTarget.forEach((propName, targetName) -> {
      Entity target = mock(Entity.class);
      when(target.getName()).thenReturn(targetName);
      Property prop = mock(Property.class);
      when(prop.isPrimitive()).thenReturn(false);
      when(prop.getTargetEntity()).thenReturn(target);
      when(entity.getProperty(propName, false)).thenReturn(prop);
    });
    return entity;
  }

  @Test
  @DisplayName("an FK '0' that is a real record of its target is kept")
  void zeroThatIsARealRecordIsKept() throws Exception {
    zeroRecordExistsIn(Set.of("FkSentinelAsi", "FkSentinelOrg"));
    Entity line = entityWithFks(Map.of(
        "attributeSetValue", "FkSentinelAsi",
        "organization", "FkSentinelOrg"));
    JSONObject body = new JSONObject()
        .put("attributeSetValue", ZERO)
        .put("organization", ZERO);

    McpWriteRequestSupport.resolveFkSentinels(body, line, LOG);

    assertEquals(ZERO, body.optString("attributeSetValue", null),
        "'0' is an existing record of the target and must reach the DAL unchanged");
    assertEquals(ZERO, body.optString("organization", null),
        "stripping organization '0' writes the record into an org the agent did not ask for");
  }

  @Test
  @DisplayName("a '0' with a real sibling FK to the same target still copies the sibling")
  void zeroWithSiblingIsResolvedFromSibling() throws Exception {
    // The target holds a '0' record too ("** New **" in C_DocType): the sibling rule must win,
    // or the document would be persisted with the placeholder document type.
    zeroRecordExistsIn(Set.of("FkSentinelDocType"));
    Entity header = entityWithFks(Map.of(
        "documentType", "FkSentinelDocType",
        "transactionDocument", "FkSentinelDocType"));
    JSONObject body = new JSONObject()
        .put("documentType", ZERO)
        .put("transactionDocument", "AB12");

    McpWriteRequestSupport.resolveFkSentinels(body, header, LOG);

    assertEquals("AB12", body.getString("documentType"));
    assertEquals("AB12", body.getString("transactionDocument"));
  }

  @Test
  @DisplayName("a '0' that is no record of its target and has no sibling is removed")
  void zeroThatIsNoRecordIsRemoved() throws Exception {
    zeroRecordExistsIn(Set.of());
    Entity entity = entityWithFks(Map.of("warehouse", "FkSentinelNoZero"));
    JSONObject body = new JSONObject().put("warehouse", ZERO);

    McpWriteRequestSupport.resolveFkSentinels(body, entity, LOG);

    assertFalse(body.has("warehouse"));
  }
}
