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

package com.etendoerp.go.usage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.hibernate.criterion.Criterion;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyException;
import org.openbravo.erpCommon.utility.PropertyNotFoundException;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

/**
 * Unit specs for {@link UsageSettings} (ETP-5050).
 *
 * <p>{@code Preferences} and {@code OBDal} are static entry points into the platform, so they are
 * mocked statically; nothing here touches a database.
 *
 * <p>The centrepiece is the <b>{@code isListProperty = true} flag</b>. The settling-window key is
 * registered as an {@code AD_Ref_List} value of the {@code Property Configuration} reference, so
 * Openbravo stores it in {@code AD_Preference.Property}. Reading it with {@code false} would look
 * in {@code AD_Preference.Attribute}, find nothing, throw {@code PropertyNotFoundException} and
 * return the default — meaning every tenant's configured window would silently be ignored and the
 * system would report 5 everywhere, with no error in any log. That failure is invisible from the
 * outside, which is exactly why the flag is asserted directly on the captured argument rather
 * than inferred from a return value: a test that only checked the returned number would pass with
 * the flag wrong.
 *
 * <p>The second theme is that <b>every malformed value degrades to the documented default</b>
 * rather than propagating. A nightly job that refuses to start because a preference holds
 * {@code "five"} is worse than one that counts with the default window, so blank, non-numeric and
 * negative values are all specified to return {@link UsageSettings#DEFAULT_SETTLING_WINDOW_DAYS}.
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
class UsageSettingsTest {

  private static final String CLIENT_ID = "A1B2C3D4E5F60718293A4B5C6D7E8F90";
  private static final String SYSTEM_CLIENT = "0";

  /** Stubs the preference read to return {@code value} for any lookup. */
  private static void givenPreferenceValue(MockedStatic<Preferences> preferences, String value)
      throws PropertyException {
    preferences
        .when(() -> Preferences.getPreferenceValue(anyString(), anyBoolean(), anyString(),
            anyString(), any(), any(), any()))
        .thenReturn(value);
  }

  @Nested
  @DisplayName("the list-property flag")
  class ListPropertyFlag {

    /**
     * The load-bearing assertion of this class: the key lives in {@code AD_Preference.Property},
     * so the read must declare it as a list property. Captured and asserted explicitly because
     * passing {@code false} produces the right-looking default and no error at all.
     */
    @Test
    void theSettlingWindowIsReadAsAListProperty() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class)) {
        givenPreferenceValue(preferences, "7");

        UsageSettings.getSettlingWindowDays(CLIENT_ID);

        ArgumentCaptor<Boolean> isList = ArgumentCaptor.forClass(Boolean.class);
        preferences.verify(() -> Preferences.getPreferenceValue(
            eq(UsageSettings.PREFERENCE_PROPERTY), isList.capture(), eq(CLIENT_ID), eq("0"),
            any(), any(), any()));

        assertTrue(isList.getValue(),
            "the key is an AD_Ref_List value stored in AD_Preference.Property; reading it with"
                + " isListProperty=false would look in .Attribute, find nothing and silently"
                + " return the default");
      }
    }

    /** A blank tenant falls back to the System client rather than passing null through. */
    @ParameterizedTest(name = "clientId = \"{0}\"")
    @ValueSource(strings = { "", "   " })
    void aBlankTenantIsReadAtSystemLevel(String blankClient) throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class)) {
        givenPreferenceValue(preferences, "7");

        UsageSettings.getSettlingWindowDays(blankClient);

        preferences.verify(() -> Preferences.getPreferenceValue(
            eq(UsageSettings.PREFERENCE_PROPERTY), eq(true), eq(SYSTEM_CLIENT), eq("0"), any(),
            any(), any()));
      }
    }

    @Test
    void theNoArgumentFormReadsTheSystemLevelPreference() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class)) {
        givenPreferenceValue(preferences, "3");

        assertEquals(3, UsageSettings.getSettlingWindowDays());

        preferences.verify(() -> Preferences.getPreferenceValue(
            eq(UsageSettings.PREFERENCE_PROPERTY), eq(true), eq(SYSTEM_CLIENT), eq("0"), any(),
            any(), any()));
      }
    }
  }

  @Nested
  @DisplayName("getSettlingWindowDays")
  class ReadingTheWindow {

    @Test
    void returnsTheConfiguredValue() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class)) {
        givenPreferenceValue(preferences, "12");
        assertEquals(12, UsageSettings.getSettlingWindowDays(CLIENT_ID));
      }
    }

    @Test
    void acceptsAValueWithSurroundingWhitespace() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class)) {
        givenPreferenceValue(preferences, " 9 ");
        assertEquals(9, UsageSettings.getSettlingWindowDays(CLIENT_ID));
      }
    }

    /** Zero is a legitimate configuration — only today stays open — not a malformed value. */
    @Test
    void acceptsZero() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class)) {
        givenPreferenceValue(preferences, "0");
        assertEquals(0, UsageSettings.getSettlingWindowDays(CLIENT_ID));
      }
    }

    /** No preference anywhere: the ordinary case on a fresh install. */
    @Test
    void fallsBackToTheDefaultWhenThePreferenceIsNotFound() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class)) {
        preferences
            .when(() -> Preferences.getPreferenceValue(anyString(), anyBoolean(), anyString(),
                anyString(), any(), any(), any()))
            .thenThrow(new PropertyNotFoundException());

        assertEquals(UsageSettings.DEFAULT_SETTLING_WINDOW_DAYS,
            UsageSettings.getSettlingWindowDays(CLIENT_ID));
      }
    }

    /**
     * A broader platform failure (conflicting preferences, no session) must not escape either:
     * the aggregation job runs unattended, so it degrades to the default instead of aborting.
     */
    @Test
    void fallsBackToTheDefaultAndDoesNotPropagateAPropertyException() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class)) {
        preferences
            .when(() -> Preferences.getPreferenceValue(anyString(), anyBoolean(), anyString(),
                anyString(), any(), any(), any()))
            .thenThrow(new PropertyException("conflicting preference values"));

        assertEquals(UsageSettings.DEFAULT_SETTLING_WINDOW_DAYS,
            UsageSettings.getSettlingWindowDays(CLIENT_ID));
      }
    }

    @ParameterizedTest(name = "value = \"{0}\"")
    @ValueSource(strings = { "five", "7.5", "", "   ", "-1", "-10" })
    void aMalformedOrNegativeValueFallsBackToTheDefault(String raw) throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class)) {
        givenPreferenceValue(preferences, raw);
        assertEquals(UsageSettings.DEFAULT_SETTLING_WINDOW_DAYS,
            UsageSettings.getSettlingWindowDays(CLIENT_ID));
      }
    }

    @Test
    void aNullValueFallsBackToTheDefault() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class)) {
        givenPreferenceValue(preferences, null);
        assertEquals(UsageSettings.DEFAULT_SETTLING_WINDOW_DAYS,
            UsageSettings.getSettlingWindowDays(CLIENT_ID));
      }
    }
  }

  @Nested
  @DisplayName("getMaxSettlingWindowDays")
  class MaximumWindow {

    private Preference preferenceHolding(String value, String clientId) {
      Preference preference = mock(Preference.class);
      when(preference.getSearchKey()).thenReturn(value);
      if (clientId == null) {
        when(preference.getVisibleAtClient()).thenReturn(null);
      } else {
        Client client = mock(Client.class);
        when(client.getId()).thenReturn(clientId);
        when(preference.getVisibleAtClient()).thenReturn(client);
      }
      return preference;
    }

    /** The criteria the scan built, so the restrictions it added can be inspected. */
    private OBCriteria<Preference> scanCriteria;

    @SuppressWarnings("unchecked")
    private void givenConfiguredPreferences(MockedStatic<OBDal> obDalStatic,
        List<Preference> preferences) {
      OBDal obDal = mock(OBDal.class);
      scanCriteria = mock(OBCriteria.class);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.createCriteria(Preference.class)).thenReturn(scanCriteria);
      when(scanCriteria.list()).thenReturn(preferences);
    }

    /**
     * The scan must consider only LIST-style rows. The key is registered in {@code AD_Ref_List},
     * so it lives in {@code AD_Preference.Property}; an attribute-style row carrying the same text
     * in the same column is a different thing and must not be read as a window value. This is the
     * one place the list/attribute distinction the class documents so carefully could leak, and it
     * leaks silently — the maximum would simply come out wrong.
     */
    @Test
    void theScanConsidersOnlyListStylePreferences() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenPreferenceValue(preferences, "5");
        givenConfiguredPreferences(obDalStatic, Collections.emptyList());

        UsageSettings.getMaxSettlingWindowDays();

        ArgumentCaptor<Criterion> restrictions = ArgumentCaptor.forClass(Criterion.class);
        verify(scanCriteria, atLeastOnce()).add(restrictions.capture());

        assertTrue(
            restrictions.getAllValues().stream()
                .anyMatch(c -> String.valueOf(c).contains(Preference.PROPERTY_PROPERTYLIST)),
            "expected a propertyList restriction, got: " + restrictions.getAllValues());
      }
    }

    /**
     * The scan reads across tenants, so the readable-client and readable-organisation filters must
     * be off. With them on, a system-level run would see only its own client and silently compute
     * a maximum that ignores every tenant override.
     */
    @Test
    void theScanIgnoresContextVisibility() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenPreferenceValue(preferences, "5");
        givenConfiguredPreferences(obDalStatic, Collections.emptyList());

        UsageSettings.getMaxSettlingWindowDays();

        verify(scanCriteria).setFilterOnReadableClients(false);
        verify(scanCriteria).setFilterOnReadableOrganization(false);
      }
    }

    /**
     * The scheduled run iterates the LARGEST configured window, because it counts every tenant in
     * one grouped query per day and so cannot use a per-tenant range. Taking anything smaller
     * would leave a longer-windowed tenant's older days flagged unsettled yet never recomputed —
     * a value frozen while it was still supposed to change.
     */
    @Test
    void returnsTheLargestConfiguredValue() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenPreferenceValue(preferences, "5");
        givenConfiguredPreferences(obDalStatic, Arrays.asList(preferenceHolding("3", CLIENT_ID),
            preferenceHolding("14", "0FEDCBA98765432100FEDCBA98765432"),
            preferenceHolding("7", CLIENT_ID)));

        assertEquals(14, UsageSettings.getMaxSettlingWindowDays());
      }
    }

    /** The system value is the floor: a shorter tenant override must not lower it. */
    @Test
    void isNeverLessThanTheSystemValue() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenPreferenceValue(preferences, "10");
        givenConfiguredPreferences(obDalStatic,
            Arrays.asList(preferenceHolding("2", CLIENT_ID), preferenceHolding("1", CLIENT_ID)));

        assertEquals(10, UsageSettings.getMaxSettlingWindowDays());
      }
    }

    @Test
    void fallsBackToTheSystemValueWhenNoTenantOverridesAnything() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenPreferenceValue(preferences, "6");
        givenConfiguredPreferences(obDalStatic, Collections.emptyList());

        assertEquals(6, UsageSettings.getMaxSettlingWindowDays());
      }
    }

    /** A malformed tenant override degrades to the default and must not drag the maximum down. */
    @Test
    void ignoresAMalformedTenantOverride() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenPreferenceValue(preferences, "2");
        givenConfiguredPreferences(obDalStatic,
            Arrays.asList(preferenceHolding("not a number", CLIENT_ID),
                preferenceHolding("-4", CLIENT_ID)));

        assertAll(
            () -> assertEquals(UsageSettings.DEFAULT_SETTLING_WINDOW_DAYS,
                UsageSettings.getMaxSettlingWindowDays(),
                "both overrides degrade to the default, which is larger than the system value"),
            () -> assertTrue(
                UsageSettings.getMaxSettlingWindowDays() >= UsageSettings
                    .getSettlingWindowDays(),
                "and the result is still at least the system value"));
      }
    }

    /** A system-scope preference row carries no visible-at client; it must not NPE. */
    @Test
    void handlesAPreferenceRowWithNoVisibleAtClient() throws PropertyException {
      try (MockedStatic<Preferences> preferences = mockStatic(Preferences.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        givenPreferenceValue(preferences, "5");
        givenConfiguredPreferences(obDalStatic,
            Collections.singletonList(preferenceHolding("9", null)));

        assertEquals(9, UsageSettings.getMaxSettlingWindowDays());
      }
    }
  }
}
