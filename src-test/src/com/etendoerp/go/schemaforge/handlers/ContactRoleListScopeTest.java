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

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;

/**
 * ETP-5852 (M-172): an MCP list of contacts/customer or contacts/vendorCreditor keeps only the
 * partners holding the role; a list scoped to one partner and every non-MCP list are unchanged.
 */
class ContactRoleListScopeTest {

  static Stream<Arguments> cases() {
    NeoHandler customer = new ContactCustomerTabHandler();
    NeoHandler vendor = new ContactVendorTabHandler();
    return Stream.of(
        Arguments.of("MCP customer list", customer, true, Map.of(), List.of("e.customer = true")),
        Arguments.of("MCP vendor list", vendor, true, Map.of(), List.of("e.vendor = true")),
        Arguments.of("MCP list of one partner", customer, true, Map.of("parentId", "BP1"),
            List.of()),
        Arguments.of("SPA/REST list", customer, false, Map.of(), List.of()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void keepsThePartnersHoldingTheRole(String label, NeoHandler handler, boolean mcp,
      Map<String, String> query, List<String> expected) {
    NeoContext context = NeoContext.builder()
        .specName("contacts")
        .httpMethod("GET")
        .queryParams(query)
        .mcpOrigin(mcp)
        .build();

    assertEquals(expected, handler.readPredicates(context), label);
  }
}
