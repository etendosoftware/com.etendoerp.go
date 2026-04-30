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

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.etendoerp.go.schemaforge.selector.meta.SelectorContextResolver;

class SelectorContextResolverTest {

  @Test
  @DisplayName("resolveContextOrganizationId prefers AD_Org_ID and hides star organization")
  void testResolveContextOrganizationIdPrefersCanonicalOrg() {
    Map<String, String> params = new HashMap<>();
    params.put("AD_Org_ID", "ORG_A");
    params.put("inpadOrgId", "ORG_B");

    assertEquals("ORG_A", SelectorContextResolver.resolveContextOrganizationId(null, params));

    params.put("AD_Org_ID", "0");
    assertNull(SelectorContextResolver.resolveContextOrganizationId(null, params));
  }

  @Test
  @DisplayName("buildComboSelectorParams normalizes casing without overwriting explicit values")
  void testBuildComboSelectorParamsNormalizesAliases() {
    Map<String, String> params = new HashMap<>();
    params.put("inpadOrgId", "ORG_B");
    params.put("isSOTrx", "Y");
    params.put("IsReceipt", "N");
    params.put("PriceList", "PL_1");

    Map<String, String> selectorParams = SelectorContextResolver.buildComboSelectorParams(null, params);

    assertEquals("ORG_B", selectorParams.get("AD_Org_ID"));
    assertEquals("ORG_B", selectorParams.get("inpadOrgId"));
    assertEquals("Y", selectorParams.get("IsSOTrx"));
    assertEquals("Y", selectorParams.get("isSOTrx"));
    assertEquals("N", selectorParams.get("IsReceipt"));
    assertEquals("N", selectorParams.get("isReceipt"));
    assertEquals("PL_1", selectorParams.get("priceList"));
    assertEquals("PL_1", selectorParams.get("PriceList"));
  }

  @Test
  @DisplayName("copyIfAbsent ignores blanks and existing values")
  void testCopyIfAbsentPreservesExistingValues() {
    Map<String, String> target = new HashMap<>();
    target.put("existing", "kept");

    SelectorContextResolver.copyIfAbsent(target, "existing", "new");
    SelectorContextResolver.copyIfAbsent(target, "blank", " ");
    SelectorContextResolver.copyIfAbsent(target, "missing", "value");

    assertEquals("kept", target.get("existing"));
    assertFalse(target.containsKey("blank"));
    assertEquals("value", target.get("missing"));
  }
}
