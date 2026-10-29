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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Which action refusals {@link McpPeriodRefusal} enriches. The period lookup itself reads the DAL
 * and is exercised against a live instance.
 */
class McpPeriodRefusalTest {

  private static JSONObject keyed(String key) throws Exception {
    return new JSONObject().put("messageKeys", new JSONArray().put(key));
  }

  @Test
  void recognisesBothPeriodKeysFlatAndNested() throws Exception {
    assertTrue(McpPeriodRefusal.isPeriodRefusal(keyed("PeriodNotAvailable")));
    assertTrue(McpPeriodRefusal.isPeriodRefusal(keyed("PeriodNotValid")));
    assertTrue(McpPeriodRefusal.isPeriodRefusal(
        new JSONObject().put("error", keyed("PeriodNotAvailable"))));
  }

  @Test
  void otherRefusalsAreNotPeriodRefusals() throws Exception {
    assertFalse(McpPeriodRefusal.isPeriodRefusal(keyed("20501")));
    assertFalse(McpPeriodRefusal.isPeriodRefusal(new JSONObject().put("processMessage", "x")));
  }

  @Test
  void leavesAnyOtherRefusalUntouchedWithoutReadingTheRecord() throws Exception {
    SFEntity entity = mock(SFEntity.class);
    JSONObject body = keyed("20501");

    McpPeriodRefusal.addTo(body, entity, "ID");

    assertFalse(body.has(McpPeriodRefusal.KEY_PERIOD));
    verifyNoInteractions(entity);
  }
}
