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

package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ETP-5852 (M-188): an agent's orderBy ends with {@code id}, so rows tied on the sorted value keep
 * one order across pages; an order that already names {@code id} is left as sent.
 *
 * @covers com.etendoerp.go.mcp.McpQuerySupport
 */
class McpQuerySupportOrderTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "orderDate|orderDate,id",
      "-orderDate|-orderDate,id",
      "orderDate desc,documentNo|orderDate desc,documentNo,id",
      "orderDate,id|orderDate,id",
      "-id|-id",
      "orderDate,id desc|orderDate,id desc",
      "identifier|identifier,id" })
  void endsWithTheIdAsTieBreak(String sent, String expected) {
    assertEquals(expected, McpQuerySupport.withIdTieBreak(sent));
  }
}
