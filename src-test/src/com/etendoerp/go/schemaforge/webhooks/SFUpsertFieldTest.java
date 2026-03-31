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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.module.Module;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Unit tests for {@link SFUpsertField}.
 * Covers create/update paths, required/optional params, boolean parsing,
 * edge cases and save/flush verification.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFUpsertFieldTest extends BaseWebhookTest {

    private SFUpsertField webhook;

    private SFEntity mockEntity;
    private Column mockColumn;
    private Module mockModule;

    @BeforeEach
    void setUp() {
        webhook = new SFUpsertField();
        mockEntity = mock(SFEntity.class);
        mockColumn = mock(Column.class);
        mockModule = mock(Module.class);

        // Stub OBProvider to return a mock SFField that tracks state via setters.
        SFField newField = mock(SFField.class);
        when(newField.getId()).thenReturn("new-field-id");

        AtomicBoolean fieldIncluded = new AtomicBoolean(false);
        doAnswer(inv -> { fieldIncluded.set(inv.getArgument(0)); return null; })
            .when(newField).setIncluded(anyBoolean());
        when(newField.isIncluded()).thenAnswer(inv -> fieldIncluded.get());

        AtomicBoolean fieldReadOnly = new AtomicBoolean(false);
        doAnswer(inv -> { fieldReadOnly.set(inv.getArgument(0)); return null; })
            .when(newField).setReadOnly(anyBoolean());
        when(newField.isReadOnly()).thenAnswer(inv -> fieldReadOnly.get());

        when(obProvider.get(SFField.class)).thenReturn(newField);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void setupValidRequestParams() {
        parameters.put(ENTITY_ID, TEST_ID_1);
        parameters.put(COLUMN_ID, TEST_ID_2);
        parameters.put(MODULE_ID, "mod1");
    }

    private void stubSuccessfulLookups() {
        when(obDal.get(SFEntity.class, TEST_ID_1)).thenReturn(mockEntity);
        when(obDal.get(Column.class, TEST_ID_2)).thenReturn(mockColumn);
        when(obDal.get(Module.class, "mod1")).thenReturn(mockModule);
    }

    private SFField stubExistingField(String fieldId) {
        SFField existing = mock(SFField.class);
        when(existing.getId()).thenReturn(fieldId);
        when(obDal.get(SFField.class, fieldId)).thenReturn(existing);
        return existing;
    }

    // ── happy paths ─────────────────────────────────────────────────────

    /** Verifies a new field is created successfully. */
    @Test
    @DisplayName("Create field - happy path")
    void testCreateFieldHappyPath() {
        setupValidRequestParams();
        stubSuccessfulLookups();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertTrue(responseVars.get(MESSAGE).contains("Field upserted"));
        assertNotNull(responseVars.get(FIELD_ID));
        verify(obDal).save(any(SFField.class));
        verify(obDal).flush();
    }

    /** Verifies new field defaults: included=true, readOnly=false. */
    @Test
    @DisplayName("New field defaults: included=true, readOnly=false")
    void testNewFieldDefaults() {
        setupValidRequestParams();
        stubSuccessfulLookups();

        ArgumentCaptor<SFField> captor = ArgumentCaptor.forClass(SFField.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        SFField saved = captor.getValue();
        assertTrue(saved.isIncluded());
        assertFalse(saved.isReadOnly());
    }

    // ── update / existing entity ────────────────────────────────────────

    /** Verifies an existing field is loaded, updated, and saved. */
    @Test
    @DisplayName("Update existing field sets readOnly")
    void testUpdateExistingField() {
        parameters.put(FIELD_ID, "field1");
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("IsReadOnly", "Y");

        SFField existingField = stubExistingField("field1");

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingField).setReadOnly(true);
        verify(obDal).save(existingField);
        verify(obDal).flush();
    }

    // ── not-found errors ────────────────────────────────────────────────

    /** Verifies error when FieldID references a non-existent record. */
    @Test
    @DisplayName("Field not found returns error")
    void testFieldNotFoundOnUpdate() {
        parameters.put(FIELD_ID, "nonexistent");
        setupValidRequestParams();
        stubSuccessfulLookups();

        when(obDal.get(SFField.class, "nonexistent")).thenReturn(null);

        webhook.get(parameters, responseVars);

        assertEquals("Field not found: nonexistent", responseVars.get(ERROR));
        verify(obDal, never()).save(any());
        verify(obDal, never()).flush();
    }

    /** Verifies error when Entity is not found. */
    @Test
    @DisplayName("Entity not found returns error")
    void testEntityNotFound() {
        parameters.put(ENTITY_ID, "bad");
        parameters.put(COLUMN_ID, TEST_ID_2);
        parameters.put(MODULE_ID, "mod1");

        when(obDal.get(SFEntity.class, "bad")).thenReturn(null);

        webhook.get(parameters, responseVars);

        assertEquals("Entity not found: bad", responseVars.get(ERROR));
    }

    /** Verifies error when Column is not found. */
    @Test
    @DisplayName("Column not found returns error")
    void testColumnNotFound() {
        parameters.put(ENTITY_ID, TEST_ID_1);
        parameters.put(COLUMN_ID, "bad");
        parameters.put(MODULE_ID, "mod1");

        when(obDal.get(SFEntity.class, TEST_ID_1)).thenReturn(mockEntity);
        when(obDal.get(Column.class, "bad")).thenReturn(null);

        webhook.get(parameters, responseVars);

        assertEquals("Column not found: bad", responseVars.get(ERROR));
    }

    /** Verifies error when Module is not found. */
    @Test
    @DisplayName("Module not found returns error")
    void testModuleNotFound() {
        parameters.put(ENTITY_ID, TEST_ID_1);
        parameters.put(COLUMN_ID, TEST_ID_2);
        parameters.put(MODULE_ID, "bad");

        when(obDal.get(SFEntity.class, TEST_ID_1)).thenReturn(mockEntity);
        when(obDal.get(Column.class, TEST_ID_2)).thenReturn(mockColumn);
        when(obDal.get(Module.class, "bad")).thenReturn(null);

        webhook.get(parameters, responseVars);

        assertEquals("Module not found: bad", responseVars.get(ERROR));
    }

    // ── boolean Y/N/case-insensitive ────────────────────────────────────

    /** Verifies IsIncluded=Y sets included to true. */
    @Test
    @DisplayName("IsIncluded Y -> true")
    void testIsIncludedYes() {
        parameters.put(FIELD_ID, "field1");
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("IsIncluded", "Y");

        SFField existingField = stubExistingField("field1");

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingField).setIncluded(true);
    }

    /** Verifies IsIncluded=N sets included to false. */
    @Test
    @DisplayName("IsIncluded N -> false")
    void testIsIncludedNo() {
        parameters.put(FIELD_ID, "field1");
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("IsIncluded", "N");

        SFField existingField = stubExistingField("field1");

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingField).setIncluded(false);
    }

    /** Verifies IsReadOnly is case-insensitive (lowercase y). */
    @Test
    @DisplayName("IsReadOnly case-insensitive (lowercase y)")
    void testIsReadOnlyCaseInsensitive() {
        parameters.put(FIELD_ID, "field1");
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("IsReadOnly", "y");

        SFField existingField = stubExistingField("field1");

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingField).setReadOnly(true);
    }

    /** Verifies non-Y value for IsIncluded resolves to false. */
    @Test
    @DisplayName("Non-Y boolean value resolves to false")
    void testBooleanNonYValueIsFalse() {
        parameters.put(FIELD_ID, "field1");
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("IsIncluded", "X");

        SFField existingField = stubExistingField("field1");

        webhook.get(parameters, responseVars);

        verify(existingField).setIncluded(false);
    }

    // ── optional params ─────────────────────────────────────────────────

    /** Verifies DefaultValue is set when provided. */
    @Test
    @DisplayName("DefaultValue is applied")
    void testDefaultValueIsSet() {
        parameters.put(FIELD_ID, "field1");
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("DefaultValue", "myDefault");

        SFField existingField = stubExistingField("field1");

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingField).setDefaultValue("myDefault");
    }

    /** Verifies JavaQualifier is set when provided. */
    @Test
    @DisplayName("JavaQualifier is applied")
    void testJavaQualifierIsSet() {
        parameters.put(FIELD_ID, "field1");
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("JavaQualifier", "com.example.Qualifier");

        SFField existingField = stubExistingField("field1");

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingField).setJavaQualifier("com.example.Qualifier");
    }

    /** Verifies SeqNo is parsed as Long and set. */
    @Test
    @DisplayName("SeqNo is parsed and applied")
    void testSeqNoIsSet() {
        parameters.put(FIELD_ID, "field1");
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("SeqNo", "20");

        SFField existingField = stubExistingField("field1");

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingField).setSeqNo(20L);
    }

    /** Verifies optional params are not applied when absent. */
    @Test
    @DisplayName("Optional params absent -> not called")
    void testOptionalParamsNotSetWhenAbsent() {
        parameters.put(FIELD_ID, "field1");
        setupValidRequestParams();
        stubSuccessfulLookups();

        SFField existingField = stubExistingField("field1");

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingField, never()).setIncluded(anyBoolean());
        verify(existingField, never()).setReadOnly(anyBoolean());
        verify(existingField, never()).setDefaultValue(any());
        verify(existingField, never()).setJavaQualifier(any());
        verify(existingField, never()).setSeqNo(any(Long.class));
    }

    // ── exception handling ──────────────────────────────────────────────

    /** Verifies exception is caught and error message set. */
    @Test
    @DisplayName("Exception caught and error set")
    void testExceptionSetsError() {
        setupValidRequestParams();
        when(obDal.get(SFEntity.class, TEST_ID_1)).thenThrow(new RuntimeException("Crash"));

        webhook.get(parameters, responseVars);

        assertEquals("Crash", responseVars.get(ERROR));
        assertNull(responseVars.get(MESSAGE));
    }

    // ── edge cases ──────────────────────────────────────────────────────

    /** Verifies special characters in DefaultValue are preserved. */
    @Test
    @DisplayName("Special characters in DefaultValue preserved")
    void testSpecialCharactersInDefaultValue() {
        parameters.put(FIELD_ID, "field1");
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("DefaultValue", "<script>alert('xss')</script>");

        SFField existingField = stubExistingField("field1");

        webhook.get(parameters, responseVars);

        verify(existingField).setDefaultValue("<script>alert('xss')</script>");
    }

    /** Verifies input parameters map is not mutated. */
    @Test
    @DisplayName("Input params map not mutated")
    void testInputParamsNotMutated() {
        setupValidRequestParams();
        stubSuccessfulLookups();

        Map<String, String> originalParams = new HashMap<>(parameters);

        webhook.get(parameters, responseVars);

        assertEquals(originalParams, parameters);
    }

    /** Verifies a long DefaultValue string is handled. */
    @Test
    @DisplayName("Long DefaultValue accepted")
    void testLongDefaultValue() {
        parameters.put(FIELD_ID, "field1");
        setupValidRequestParams();
        stubSuccessfulLookups();
        String longValue = "V".repeat(2000);
        parameters.put("DefaultValue", longValue);

        SFField existingField = stubExistingField("field1");

        webhook.get(parameters, responseVars);

        verify(existingField).setDefaultValue(longValue);
    }

    /** Verifies save and flush are each called exactly once. */
    @Test
    @DisplayName("Save and flush called exactly once")
    void testSaveAndFlushCalledOnce() {
        setupValidRequestParams();
        stubSuccessfulLookups();

        webhook.get(parameters, responseVars);

        verify(obDal, times(1)).save(any(SFField.class));
        verify(obDal, times(1)).flush();
    }

    /** Verifies blank FieldID is treated as create (not update). */
    @Test
    @DisplayName("Blank FieldID creates new field")
    void testBlankFieldIdCreatesNew() {
        parameters.put(FIELD_ID, "");
        setupValidRequestParams();
        stubSuccessfulLookups();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal, never()).get(SFField.class, "");
        verify(obDal).save(any(SFField.class));
    }
}
