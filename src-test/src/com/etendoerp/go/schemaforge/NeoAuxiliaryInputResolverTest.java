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
package com.etendoerp.go.schemaforge;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.model.ad.ui.AuxiliaryInput;
import org.openbravo.model.ad.ui.Tab;

/**
 * Unit tests for {@link NeoAuxiliaryInputResolver}. Pure — no database or Etendo
 * container. Covers the container-free evaluation branches (literal value,
 * parent-value token, and the guard/skip paths); the {@code @SQL=} and
 * {@code Utility.getContext} branches require a running instance and are exercised
 * end-to-end via the schema_forge mocked E2E instead.
 */
class NeoAuxiliaryInputResolverTest {

  private static final String WINDOW_ID = "100";

  private static AuxiliaryInput auxInput(String name, String code, boolean active) {
    AuxiliaryInput aux = mock(AuxiliaryInput.class);
    when(aux.getName()).thenReturn(name);
    when(aux.getValidationCode()).thenReturn(code);
    when(aux.isActive()).thenReturn(active);
    return aux;
  }

  private static Tab tabWith(List<AuxiliaryInput> auxInputs) {
    Tab tab = mock(Tab.class);
    when(tab.getADAuxiliaryInputList()).thenReturn(auxInputs);
    return tab;
  }

  @Test
  @DisplayName("null tab is a no-op")
  void nullTabNoOp() {
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    NeoAuxiliaryInputResolver.injectAuxiliaryInputs(null, WINDOW_ID, vars, null, null);
    verify(vars, never()).setSessionValue(anyString(), anyString());
  }

  @Test
  @DisplayName("empty auxiliary-input list is a no-op")
  void emptyListNoOp() {
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    NeoAuxiliaryInputResolver.injectAuxiliaryInputs(tabWith(Collections.emptyList()), WINDOW_ID, vars, null, null);
    verify(vars, never()).setSessionValue(anyString(), anyString());
  }

  @Test
  @DisplayName("null auxiliary-input list is a no-op")
  void nullListNoOp() {
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    NeoAuxiliaryInputResolver.injectAuxiliaryInputs(tabWith(null), WINDOW_ID, vars, null, null);
    verify(vars, never()).setSessionValue(anyString(), anyString());
  }

  @Test
  @DisplayName("inactive auxiliary input is skipped")
  void inactiveSkipped() {
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Tab tab = tabWith(Collections.singletonList(auxInput("DOCBASETYPE", "GLJ", false)));
    NeoAuxiliaryInputResolver.injectAuxiliaryInputs(tab, WINDOW_ID, vars, null, null);
    verify(vars, never()).setSessionValue(anyString(), anyString());
  }

  @Test
  @DisplayName("literal code is stored in session as windowId|name")
  void literalStored() {
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Tab tab = tabWith(Collections.singletonList(auxInput("DOCBASETYPE", "GLJ", true)));
    NeoAuxiliaryInputResolver.injectAuxiliaryInputs(tab, WINDOW_ID, vars, null, null);
    verify(vars).setSessionValue("100|DOCBASETYPE", "GLJ");
  }

  @Test
  @DisplayName("@Token@ resolves from parent values (uppercased key) without a DB call")
  void tokenFromParentValues() {
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Tab tab = tabWith(Collections.singletonList(auxInput("DESCRIPTION1", "@Description@", true)));
    Map<String, Object> parentValues = new HashMap<>();
    parentValues.put("DESCRIPTION", "Parent journal description");
    NeoAuxiliaryInputResolver.injectAuxiliaryInputs(tab, WINDOW_ID, vars, null, parentValues);
    verify(vars).setSessionValue("100|DESCRIPTION1", "Parent journal description");
  }

  @Test
  @DisplayName("null or blank code is skipped")
  void nullOrBlankCodeSkipped() {
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Tab tab = tabWith(Arrays.asList(
        auxInput("A", null, true),
        auxInput("B", "   ", true)));
    NeoAuxiliaryInputResolver.injectAuxiliaryInputs(tab, WINDOW_ID, vars, null, null);
    verify(vars, never()).setSessionValue(anyString(), anyString());
  }

  @Test
  @DisplayName("only active inputs with a resolved value are stored (mixed list)")
  void mixedListStoresOnlyResolved() {
    VariablesSecureApp vars = mock(VariablesSecureApp.class);
    Tab tab = tabWith(Arrays.asList(
        auxInput("LIT", "GLJ", true),
        auxInput("INACTIVE", "X", false),
        auxInput("EMPTY", "", true)));
    NeoAuxiliaryInputResolver.injectAuxiliaryInputs(tab, WINDOW_ID, vars, null, null);
    verify(vars).setSessionValue("100|LIT", "GLJ");
    verify(vars, never()).setSessionValue(eq("100|INACTIVE"), anyString());
    verify(vars, never()).setSessionValue(eq("100|EMPTY"), anyString());
  }
}
