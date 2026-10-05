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

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.LineAmountTestSupport.assertAmount;

import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

/**
 * {@link InvoiceLineAmountSupport#deriveAmountsOnCreate} (ETP-5528): a sales invoice line created
 * through {@code etendo_create} persisted {@code LineNetAmt 0 / Line_Gross_Amount 0}; live after the
 * fix, Fernet at 18, qty 10, 21 % → net 180 / gross 217.80, the same as {@code etendo_batch}.
 */
class InvoiceLineAmountSupportTest {

  private static final String LINE =
      "\"invoicedQuantity\":10,\"unitPrice\":18,\"tax\":\"TAX21\"";

  static Stream<Arguments> amountsOnCreate() {
    return Stream.of(
        Arguments.of("no amounts → both derived", "POST", NeoEndpointType.CRUD,
            "{" + LINE + "}", "180", "217.80"),
        Arguments.of("amounts at 0 → overwritten", "POST", NeoEndpointType.CRUD,
            "{" + LINE + ",\"lineNetAmount\":0,\"grossAmount\":\"0\"}", "180", "217.80"),
        Arguments.of("the caller's net and gross → both kept", "POST", NeoEndpointType.CRUD,
            "{" + LINE + ",\"lineNetAmount\":150,\"grossAmount\":200}", "150", "200"),
        Arguments.of("no tax → untouched", "POST", NeoEndpointType.CRUD,
            "{\"invoicedQuantity\":10,\"unitPrice\":18}", null, null),
        Arguments.of("blank tax → untouched", "POST", NeoEndpointType.CRUD,
            "{\"invoicedQuantity\":10,\"unitPrice\":18,\"tax\":\"  \"}", null, null),
        Arguments.of("null tax → untouched", "POST", NeoEndpointType.CRUD,
            "{\"invoicedQuantity\":10,\"unitPrice\":18,\"tax\":null}", null, null),
        Arguments.of("PATCH → untouched", "PATCH", NeoEndpointType.CRUD, "{" + LINE + "}", null,
            null),
        Arguments.of("non-CRUD → untouched", "POST", NeoEndpointType.CALLOUT, "{" + LINE + "}",
            null, null),
        Arguments.of("quantity 0 → no write", "POST", NeoEndpointType.CRUD,
            "{\"invoicedQuantity\":0,\"unitPrice\":18,\"tax\":\"TAX21\"}", null, null),
        Arguments.of("price 0 → no write", "POST", NeoEndpointType.CRUD,
            "{\"invoicedQuantity\":10,\"unitPrice\":0,\"tax\":\"TAX21\"}", null, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("amountsOnCreate")
  void deriveAmountsOnCreate(String label, String method, NeoEndpointType type, String body,
      String expectedNet, String expectedGross) throws Exception {
    JSONObject json = new JSONObject(body);
    NeoContext ctx = NeoContext.builder()
        .specName("sales-invoice")
        .entityName("lines")
        .httpMethod(method)
        .endpointType(type)
        .requestBody(json)
        .build();

    try (MockedStatic<OBDal> dal = LineAmountTestSupport.taxRate(21.0)) {
      InvoiceLineAmountSupport.deriveAmountsOnCreate(ctx);
    }

    assertAmount(label, expectedNet, json, InvoiceLineAmountSupport.FIELD_LINE_NET_AMOUNT);
    assertAmount(label, expectedGross, json, InvoiceLineAmountSupport.FIELD_GROSS_AMOUNT);
  }
}
