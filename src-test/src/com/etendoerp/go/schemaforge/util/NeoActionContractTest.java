/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NeoActionContract} (ETP-5447): the immutable declaration of a named
 * handler action — its builder defaults, the GET/POST validation and the parameter accessors.
 */
@ExtendWith(MockitoExtension.class)
class NeoActionContractTest {

  private static final String ACTION = "createStatement";
  private static final String NAME = "name";
  private static final String DATE = "transactionDate";
  private static final String NOTES = "notes";

  @Test
  void testBuilderDefaultsToPostNotReadOnlyWithoutParameters() {
    NeoActionContract contract = NeoActionContract.builder(ACTION).build();

    assertEquals(ACTION, contract.getName());
    assertEquals(NeoActionContract.METHOD_POST, contract.getMethod());
    assertFalse(contract.isReadOnly());
    assertNull(contract.getDescription());
    assertTrue(contract.getParameters().isEmpty());
    assertTrue(contract.getRequiredParameterNames().isEmpty());
  }

  @Test
  void testMethodConstantsAreTheHttpVerbs() {
    assertEquals("GET", NeoActionContract.METHOD_GET);
    assertEquals("POST", NeoActionContract.METHOD_POST);
  }

  @Test
  void testBuilderAcceptsGetReadOnlyAndDescription() {
    NeoActionContract contract = NeoActionContract.builder("listStatements")
        .method(NeoActionContract.METHOD_GET)
        .readOnly(true)
        .description("Lists statements")
        .build();

    assertEquals(NeoActionContract.METHOD_GET, contract.getMethod());
    assertTrue(contract.isReadOnly());
    assertEquals("Lists statements", contract.getDescription());
  }

  @Test
  void testBuildRejectsAnUnsupportedMethod() {
    NeoActionContract.Builder builder = NeoActionContract.builder(ACTION).method("PUT");

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        builder::build);
    assertTrue(error.getMessage().contains("PUT"), error.getMessage());
    assertTrue(error.getMessage().contains(ACTION), error.getMessage());
  }

  @Test
  void testBuildRejectsALowerCaseMethod() {
    NeoActionContract.Builder builder = NeoActionContract.builder(ACTION).method("get");

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void testBuildRejectsANullMethod() {
    NeoActionContract.Builder builder = NeoActionContract.builder(ACTION).method(null);

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void testBuildRejectsABlankName() {
    NeoActionContract.Builder blank = NeoActionContract.builder("   ");
    NeoActionContract.Builder empty = NeoActionContract.builder("");
    NeoActionContract.Builder absent = NeoActionContract.builder(null);

    assertThrows(IllegalArgumentException.class, blank::build);
    assertThrows(IllegalArgumentException.class, empty::build);
    assertThrows(IllegalArgumentException.class, absent::build);
  }

  @Test
  void testParametersKeepDeclarationOrderAndSkipNull() {
    NeoActionContract contract = NeoActionContract.builder(ACTION)
        .param(NeoReportParam.required(NAME, NeoReportParam.TYPE_STRING, "n"))
        .param(null)
        .param(NeoReportParam.optional(NOTES, NeoReportParam.TYPE_STRING, "x"))
        .param(NeoReportParam.required(DATE, NeoReportParam.TYPE_DATE, "d"))
        .build();

    List<NeoReportParam> params = contract.getParameters();
    assertEquals(3, params.size());
    assertEquals(NAME, params.get(0).getName());
    assertEquals(NOTES, params.get(1).getName());
    assertEquals(DATE, params.get(2).getName());
  }

  @Test
  void testParametersAreUnmodifiable() {
    NeoActionContract contract = NeoActionContract.builder(ACTION)
        .param(NeoReportParam.required(NAME, NeoReportParam.TYPE_STRING, "n"))
        .build();
    List<NeoReportParam> params = contract.getParameters();
    NeoReportParam extra = NeoReportParam.optional(NOTES, NeoReportParam.TYPE_STRING, "x");

    assertThrows(UnsupportedOperationException.class, () -> params.add(extra));
  }

  @Test
  void testContractIsNotAffectedByLaterBuilderCalls() {
    NeoActionContract.Builder builder = NeoActionContract.builder(ACTION)
        .param(NeoReportParam.required(NAME, NeoReportParam.TYPE_STRING, "n"));
    NeoActionContract first = builder.build();

    builder.param(NeoReportParam.optional(NOTES, NeoReportParam.TYPE_STRING, "x"));

    assertEquals(1, first.getParameters().size());
  }

  @Test
  void testRequiredParameterNamesListsOnlyRequiredInOrder() {
    NeoActionContract contract = NeoActionContract.builder(ACTION)
        .param(NeoReportParam.required(NAME, NeoReportParam.TYPE_STRING, "n"))
        .param(NeoReportParam.optional(NOTES, NeoReportParam.TYPE_STRING, "x"))
        .param(NeoReportParam.required(DATE, NeoReportParam.TYPE_DATE, "d"))
        .param(NeoReportParam.options("mode", "m", List.of("A", "B")))
        .build();

    assertEquals(List.of(NAME, DATE), contract.getRequiredParameterNames());
  }

  @Test
  void testReportParamArrayAndObjectTypeConstants() {
    assertEquals("array", NeoReportParam.TYPE_ARRAY);
    assertEquals("object", NeoReportParam.TYPE_OBJECT);
  }
}
