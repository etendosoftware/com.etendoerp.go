/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2025 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.webhooks;

import static com.etendoerp.go.schemaforge.TestConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.PopulateSpecHelper;

/**
 * Unit tests for {@link SFPopulateSpec}.
 * This webhook delegates to PopulateSpecHelper so tests focus on parameter
 * parsing (defaults, case-insensitivity), response building, exception
 * handling, and flush verification.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFPopulateSpecTest {

    private MockedStatic<OBDal> obDalMock;
    private MockedStatic<OBContext> obContextMock;
    private MockedStatic<PopulateSpecHelper> populateMock;
    private OBDal mockDal;
    private SFPopulateSpec webhook;
    private Map<String, String> parameters;
    private Map<String, String> responseVars;

    @BeforeEach
    void setUp() {
        obDalMock = mockStatic(OBDal.class);
        obContextMock = mockStatic(OBContext.class);
        populateMock = mockStatic(PopulateSpecHelper.class);

        mockDal = mock(OBDal.class);
        obDalMock.when(OBDal::getInstance).thenReturn(mockDal);

        webhook = new SFPopulateSpec();
        parameters = new HashMap<>();
        responseVars = new HashMap<>();
    }

    @AfterEach
    void tearDown() {
        populateMock.close();
        obDalMock.close();
        obContextMock.close();
    }

    // ── happy paths ─────────────────────────────────────────────────────

    /** Verifies default parameters: excludeSystemColumns=true, includeAllMethods=false. */
    @Test
    @DisplayName("Populate with defaults - excludeSystemColumns=Y, includeAllMethods=N")
    void testPopulateHappyPathDefaults() {
        parameters.put(SPEC_ID, TEST_ID_1);

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, true, false))
            .thenReturn(new int[]{3, 15});

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("3", responseVars.get("EntitiesCreated"));
        assertEquals("15", responseVars.get("FieldsCreated"));
        assertTrue(responseVars.get(MESSAGE).contains("3 entities"));
        assertTrue(responseVars.get(MESSAGE).contains("15 fields"));
    }

    // ── IncludeAllMethods parsing ───────────────────────────────────────

    /** Verifies IncludeAllMethods=Y is parsed correctly. */
    @Test
    @DisplayName("IncludeAllMethods=Y -> true")
    void testIncludeAllMethodsY() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put("IncludeAllMethods", "Y");

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, true, true))
            .thenReturn(new int[]{2, 10});

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("2", responseVars.get("EntitiesCreated"));
        assertEquals("10", responseVars.get("FieldsCreated"));
    }

    /** Verifies IncludeAllMethods=N is parsed correctly. */
    @Test
    @DisplayName("IncludeAllMethods=N -> false")
    void testIncludeAllMethodsN() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put("IncludeAllMethods", "N");

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, true, false))
            .thenReturn(new int[]{1, 5});

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("1", responseVars.get("EntitiesCreated"));
    }

    /** Verifies IncludeAllMethods is case-insensitive. */
    @Test
    @DisplayName("IncludeAllMethods case-insensitive (lowercase y)")
    void testIncludeAllMethodsCaseInsensitive() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put("IncludeAllMethods", "y");

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, true, true))
            .thenReturn(new int[]{1, 3});

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("1", responseVars.get("EntitiesCreated"));
    }

    // ── ExcludeSystemColumns parsing ────────────────────────────────────

    /** Verifies ExcludeSystemColumns=N -> excludeSystemColumns=false. */
    @Test
    @DisplayName("ExcludeSystemColumns=N -> false (include system columns)")
    void testExcludeSystemColumnsN() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put("ExcludeSystemColumns", "N");

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, false, false))
            .thenReturn(new int[]{4, 20});

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("4", responseVars.get("EntitiesCreated"));
        assertEquals("20", responseVars.get("FieldsCreated"));
    }

    /** Verifies ExcludeSystemColumns=Y -> excludeSystemColumns=true. */
    @Test
    @DisplayName("ExcludeSystemColumns=Y -> true")
    void testExcludeSystemColumnsY() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put("ExcludeSystemColumns", "Y");

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, true, false))
            .thenReturn(new int[]{2, 8});

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("2", responseVars.get("EntitiesCreated"));
        assertEquals("8", responseVars.get("FieldsCreated"));
    }

    /** Verifies ExcludeSystemColumns is case-insensitive (lowercase n). */
    @Test
    @DisplayName("ExcludeSystemColumns case-insensitive (lowercase n)")
    void testExcludeSystemColumnsCaseInsensitive() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put("ExcludeSystemColumns", "n");

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, false, false))
            .thenReturn(new int[]{2, 6});

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("2", responseVars.get("EntitiesCreated"));
    }

    /** Verifies unknown ExcludeSystemColumns value is treated as true (not N). */
    @Test
    @DisplayName("ExcludeSystemColumns unknown value -> true")
    void testExcludeSystemColumnsUnknownValue() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put("ExcludeSystemColumns", "X");

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, true, false))
            .thenReturn(new int[]{1, 1});

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
    }

    // ── both options combined ───────────────────────────────────────────

    /** Verifies both options set simultaneously. */
    @Test
    @DisplayName("Both IncludeAllMethods=Y and ExcludeSystemColumns=N")
    void testBothOptionsSet() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put("IncludeAllMethods", "Y");
        parameters.put("ExcludeSystemColumns", "N");

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, false, true))
            .thenReturn(new int[]{5, 25});

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("5", responseVars.get("EntitiesCreated"));
        assertEquals("25", responseVars.get("FieldsCreated"));
    }

    // ── zero counts ─────────────────────────────────────────────────────

    /** Verifies zero counts are reported correctly. */
    @Test
    @DisplayName("Zero entities and fields returns correct counts")
    void testZeroCounts() {
        parameters.put(SPEC_ID, TEST_ID_1);

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, true, false))
            .thenReturn(new int[]{0, 0});

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("0", responseVars.get("EntitiesCreated"));
        assertEquals("0", responseVars.get("FieldsCreated"));
        assertTrue(responseVars.get(MESSAGE).contains("0 entities"));
    }

    // ── exception handling ──────────────────────────────────────────────

    /** Verifies exception from PopulateSpecHelper is caught and error set. */
    @Test
    @DisplayName("Exception sets error in response")
    void testExceptionSetsError() {
        parameters.put(SPEC_ID, TEST_ID_1);

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, true, false))
            .thenThrow(new RuntimeException("Spec not found"));

        webhook.get(parameters, responseVars);

        assertEquals("Spec not found", responseVars.get(ERROR));
        assertNull(responseVars.get("EntitiesCreated"));
    }

    // ── flush verification ──────────────────────────────────────────────

    /** Verifies OBDal.flush() is called after successful populate. */
    @Test
    @DisplayName("Flush is called after populate")
    void testFlushIsCalled() {
        parameters.put(SPEC_ID, TEST_ID_1);

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, true, false))
            .thenReturn(new int[]{1, 2});

        webhook.get(parameters, responseVars);

        verify(mockDal, times(1)).flush();
    }

    // ── edge cases ──────────────────────────────────────────────────────

    /** Verifies input parameters map is not mutated. */
    @Test
    @DisplayName("Input params not mutated")
    void testInputParamsNotMutated() {
        parameters.put(SPEC_ID, TEST_ID_1);

        populateMock.when(() -> PopulateSpecHelper.populate(TEST_ID_1, true, false))
            .thenReturn(new int[]{1, 1});

        Map<String, String> originalParams = new HashMap<>(parameters);

        webhook.get(parameters, responseVars);

        assertEquals(originalParams, parameters);
    }

    /** Verifies special characters in SpecID are passed through. */
    @Test
    @DisplayName("Special characters in SpecID passed through")
    void testSpecialCharactersInSpecId() {
        String specialId = "spec-id/with<special>&chars";
        parameters.put(SPEC_ID, specialId);

        populateMock.when(() -> PopulateSpecHelper.populate(specialId, true, false))
            .thenReturn(new int[]{0, 0});

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
    }

    /** Verifies whitespace-only SpecID is passed to helper as-is. */
    @Test
    @DisplayName("Whitespace SpecID passed to helper")
    void testWhitespaceSpecId() {
        parameters.put(SPEC_ID, "  ");

        populateMock.when(() -> PopulateSpecHelper.populate("  ", true, false))
            .thenReturn(new int[]{0, 0});

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
    }
}
