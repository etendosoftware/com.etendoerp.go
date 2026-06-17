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
package com.etendoerp.go.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Unit tests for {@link OnboardingSourceMoniker}, the shared moniker-replacement used by the
 * accounting (Gap A1) and period-control (Gap C1) onboarding wiring to rebrand GOClient sample-data
 * names with the new tenant's client name.
 */
public class OnboardingSourceMonikerTest {

  private static final String CLIENT = "Acme";

  @Test
  public void testReplaceReturnsNullUnchanged() {
    assertNull(OnboardingSourceMoniker.replace(null, CLIENT));
  }

  @Test
  public void testReplaceReturnsEmptyUnchanged() {
    String empty = "";
    assertSame(empty, OnboardingSourceMoniker.replace(empty, CLIENT));
  }

  @Test
  public void testReplaceLeavesValueWithoutMonikerUntouched() {
    assertEquals("Plain Name", OnboardingSourceMoniker.replace("Plain Name", CLIENT));
  }

  @Test
  public void testReplaceSubstitutesBareGoMoniker() {
    assertEquals("Esquema Acme", OnboardingSourceMoniker.replace("Esquema GO", CLIENT));
    assertEquals("Arbol de cuentas Acme",
        OnboardingSourceMoniker.replace("Arbol de cuentas GO", CLIENT));
  }

  @Test
  public void testReplaceMatchesLongerGoOrgBeforeGoPrefix() {
    // "GOOrg" must be replaced as a whole; if "GO" were applied first the result would be the
    // mangled "AcmeOrg Calendar". The match order in OnboardingSourceMoniker guards against that.
    assertEquals("Acme Calendar", OnboardingSourceMoniker.replace("GOOrg Calendar", CLIENT));
  }

  @Test
  public void testReplaceSubstitutesGoPrefixInsideCompoundToken() {
    assertEquals("AcmeClient Calendar", OnboardingSourceMoniker.replace("GOClient Calendar", CLIENT));
  }

  @Test
  public void testReplaceSubstitutesEveryOccurrence() {
    assertEquals("Acme Acme", OnboardingSourceMoniker.replace("GO GO", CLIENT));
  }
}
