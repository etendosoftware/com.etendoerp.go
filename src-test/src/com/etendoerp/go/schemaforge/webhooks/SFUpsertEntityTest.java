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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.anyBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link SFUpsertEntity}.
 * Covers create/update paths, required/optional params, boolean parsing,
 * tab-name fallback, edge cases, and save/flush verification.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFUpsertEntityTest extends BaseWebhookTest {

    private SFUpsertEntity webhook;

    private SFSpec mockSpec;
    private Tab mockTab;
    private Module mockModule;

    @BeforeEach
    void setUp() {
        webhook = new SFUpsertEntity();
        mockSpec = mock(SFSpec.class);
        mockTab = mock(Tab.class);
        mockModule = mock(Module.class);
        when(mockTab.getName()).thenReturn("Default Tab Name");

        // Stub OBProvider to return a mock SFEntity that tracks state via setters.
        SFEntity newEntity = mock(SFEntity.class);
        when(newEntity.getId()).thenReturn("new-entity-id");

        AtomicReference<String> entityName = new AtomicReference<>("");
        doAnswer(inv -> { entityName.set(inv.getArgument(0)); return null; })
            .when(newEntity).setName(any());
        when(newEntity.getName()).thenAnswer(inv -> entityName.get());

        AtomicBoolean included = new AtomicBoolean(false);
        doAnswer(inv -> { included.set(inv.getArgument(0)); return null; })
            .when(newEntity).setIncluded(anyBoolean());
        when(newEntity.isIncluded()).thenAnswer(inv -> included.get());

        AtomicBoolean get = new AtomicBoolean(false);
        doAnswer(inv -> { get.set(inv.getArgument(0)); return null; })
            .when(newEntity).setGet(anyBoolean());
        when(newEntity.isGet()).thenAnswer(inv -> get.get());

        AtomicBoolean getById = new AtomicBoolean(false);
        doAnswer(inv -> { getById.set(inv.getArgument(0)); return null; })
            .when(newEntity).setGetByID(anyBoolean());
        when(newEntity.isGetByID()).thenAnswer(inv -> getById.get());

        AtomicBoolean post = new AtomicBoolean(false);
        doAnswer(inv -> { post.set(inv.getArgument(0)); return null; })
            .when(newEntity).setPost(anyBoolean());
        when(newEntity.isPost()).thenAnswer(inv -> post.get());

        AtomicBoolean put = new AtomicBoolean(false);
        doAnswer(inv -> { put.set(inv.getArgument(0)); return null; })
            .when(newEntity).setPut(anyBoolean());
        when(newEntity.isPut()).thenAnswer(inv -> put.get());

        AtomicBoolean patch = new AtomicBoolean(false);
        doAnswer(inv -> { patch.set(inv.getArgument(0)); return null; })
            .when(newEntity).setPatch(anyBoolean());
        when(newEntity.isPatch()).thenAnswer(inv -> patch.get());

        AtomicBoolean delete = new AtomicBoolean(false);
        doAnswer(inv -> { delete.set(inv.getArgument(0)); return null; })
            .when(newEntity).setDelete(anyBoolean());
        when(newEntity.isDelete()).thenAnswer(inv -> delete.get());

        AtomicReference<String> javaQualifier = new AtomicReference<>("");
        doAnswer(inv -> { javaQualifier.set(inv.getArgument(0)); return null; })
            .when(newEntity).setJavaQualifier(any());
        when(newEntity.getJavaQualifier()).thenAnswer(inv -> javaQualifier.get());

        AtomicReference<Long> seqNo = new AtomicReference<>();
        doAnswer(inv -> { seqNo.set(inv.getArgument(0)); return null; })
            .when(newEntity).setSeqNo(any());
        when(newEntity.getSeqNo()).thenAnswer(inv -> seqNo.get());

        when(obProvider.get(SFEntity.class)).thenReturn(newEntity);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void setupValidRequestParams() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(TAB_ID, TEST_ID_2);
        parameters.put(MODULE_ID, "mod1");
        parameters.put(NAME, TEST_NAME);
    }

    private void stubSuccessfulLookups() {
        when(obDal.get(SFSpec.class, TEST_ID_1)).thenReturn(mockSpec);
        when(obDal.get(Tab.class, TEST_ID_2)).thenReturn(mockTab);
        when(obDal.get(Module.class, "mod1")).thenReturn(mockModule);
    }

    // ── happy paths ─────────────────────────────────────────────────────

    /** Verifies a new entity is created with explicit Name. */
    @Test
    @DisplayName("Create entity - happy path with explicit Name")
    void testCreateEntityHappyPath() {
        setupValidRequestParams();
        stubSuccessfulLookups();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertTrue(responseVars.get(MESSAGE).contains("Entity upserted"));
        assertNotNull(responseVars.get(ENTITY_ID));
        verify(obDal).save(any(SFEntity.class));
        verify(obDal).flush();
    }

    /** Verifies new entity uses Tab name when Name param is absent. */
    @Test
    @DisplayName("Create entity uses tab name when Name not provided")
    void testCreateEntityUsesTabNameWhenNameNotProvided() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(TAB_ID, TEST_ID_2);
        parameters.put(MODULE_ID, "mod1");
        // Name not provided
        stubSuccessfulLookups();

        ArgumentCaptor<SFEntity> captor = ArgumentCaptor.forClass(SFEntity.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        assertEquals("Default Tab Name", captor.getValue().getName());
    }

    // ── update / existing entity ────────────────────────────────────────

    /** Verifies an existing entity is loaded and updated. */
    @Test
    @DisplayName("Update existing entity sets name")
    void testUpdateExistingEntity() {
        parameters.put(ENTITY_ID, "ent1");
        setupValidRequestParams();
        stubSuccessfulLookups();

        SFEntity existingEntity = mock(SFEntity.class);
        when(existingEntity.getId()).thenReturn("ent1");
        when(obDal.get(SFEntity.class, "ent1")).thenReturn(existingEntity);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingEntity).setName(TEST_NAME);
        verify(obDal).save(existingEntity);
    }

    /** Verifies update path does NOT fall back to tab name when Name is absent. */
    @Test
    @DisplayName("Update entity does not use tab name when Name absent")
    void testUpdateEntityDoesNotUseTabNameWhenNameNotProvided() {
        parameters.put(ENTITY_ID, "ent1");
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(TAB_ID, TEST_ID_2);
        parameters.put(MODULE_ID, "mod1");
        stubSuccessfulLookups();

        SFEntity existingEntity = mock(SFEntity.class);
        when(existingEntity.getId()).thenReturn("ent1");
        when(obDal.get(SFEntity.class, "ent1")).thenReturn(existingEntity);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingEntity, never()).setName(any());
        verify(mockTab, never()).getName();
    }

    // ── not-found errors ────────────────────────────────────────────────

    /** Verifies error when EntityID references a non-existent record. */
    @Test
    @DisplayName("Entity not found returns error")
    void testEntityNotFoundOnUpdate() {
        parameters.put(ENTITY_ID, "nonexistent");
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(TAB_ID, TEST_ID_2);
        parameters.put(MODULE_ID, "mod1");
        stubSuccessfulLookups();

        when(obDal.get(SFEntity.class, "nonexistent")).thenReturn(null);

        webhook.get(parameters, responseVars);

        assertEquals("Entity not found: nonexistent", responseVars.get(ERROR));
        verify(obDal, never()).save(any());
        verify(obDal, never()).flush();
    }

    /** Verifies error when Spec is not found. */
    @Test
    @DisplayName("Spec not found returns error")
    void testSpecNotFound() {
        parameters.put(SPEC_ID, "bad");
        parameters.put(TAB_ID, TEST_ID_2);
        parameters.put(MODULE_ID, "mod1");

        when(obDal.get(SFSpec.class, "bad")).thenReturn(null);

        webhook.get(parameters, responseVars);

        assertEquals("Spec not found: bad", responseVars.get(ERROR));
    }

    /** Verifies error when Tab is not found. */
    @Test
    @DisplayName("Tab not found returns error")
    void testTabNotFound() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(TAB_ID, "bad");
        parameters.put(MODULE_ID, "mod1");

        when(obDal.get(SFSpec.class, TEST_ID_1)).thenReturn(mockSpec);
        when(obDal.get(Tab.class, "bad")).thenReturn(null);

        webhook.get(parameters, responseVars);

        assertEquals("Tab not found: bad", responseVars.get(ERROR));
    }

    /** Verifies error when Module is not found. */
    @Test
    @DisplayName("Module not found returns error")
    void testModuleNotFound() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(TAB_ID, TEST_ID_2);
        parameters.put(MODULE_ID, "bad");

        when(obDal.get(SFSpec.class, TEST_ID_1)).thenReturn(mockSpec);
        when(obDal.get(Tab.class, TEST_ID_2)).thenReturn(mockTab);
        when(obDal.get(Module.class, "bad")).thenReturn(null);

        webhook.get(parameters, responseVars);

        assertEquals("Module not found: bad", responseVars.get(ERROR));
    }

    // ── boolean Y/N/case-insensitive ────────────────────────────────────

    /** Verifies all boolean params set to Y are applied as true. */
    @Test
    @DisplayName("All boolean params Y -> true")
    void testBooleanParamsYValues() {
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("IsIncluded", "Y");
        parameters.put("IsGet", "Y");
        parameters.put("IsGetbyid", "Y");
        parameters.put("IsPost", "Y");
        parameters.put("IsPut", "Y");
        parameters.put("IsPatch", "Y");
        parameters.put("IsDelete", "Y");

        ArgumentCaptor<SFEntity> captor = ArgumentCaptor.forClass(SFEntity.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        SFEntity saved = captor.getValue();
        assertTrue(saved.isIncluded());
        assertTrue(saved.isGet());
        assertTrue(saved.isGetByID());
        assertTrue(saved.isPost());
        assertTrue(saved.isPut());
        assertTrue(saved.isPatch());
        assertTrue(saved.isDelete());
    }

    /** Verifies boolean params set to N are applied as false. */
    @Test
    @DisplayName("Boolean params N -> false")
    void testBooleanParamsNValues() {
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("IsIncluded", "N");
        parameters.put("IsGet", "N");
        parameters.put("IsPost", "N");

        ArgumentCaptor<SFEntity> captor = ArgumentCaptor.forClass(SFEntity.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        SFEntity saved = captor.getValue();
        assertFalse(saved.isIncluded());
        assertFalse(saved.isGet());
        assertFalse(saved.isPost());
    }

    /** Verifies boolean parsing is case-insensitive (lowercase y/n). */
    @Test
    @DisplayName("Boolean params case-insensitive")
    void testBooleanParamsCaseInsensitive() {
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("IsGet", "y");
        parameters.put("IsPost", "n");

        ArgumentCaptor<SFEntity> captor = ArgumentCaptor.forClass(SFEntity.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        SFEntity saved = captor.getValue();
        assertTrue(saved.isGet());
        assertFalse(saved.isPost());
    }

    // ── optional params ─────────────────────────────────────────────────

    /** Verifies JavaQualifier is set when provided. */
    @Test
    @DisplayName("JavaQualifier is applied")
    void testJavaQualifierIsSet() {
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("JavaQualifier", "com.example.MyQualifier");

        ArgumentCaptor<SFEntity> captor = ArgumentCaptor.forClass(SFEntity.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        assertEquals("com.example.MyQualifier", captor.getValue().getJavaQualifier());
    }

    /** Verifies SeqNo is parsed as Long and set. */
    @Test
    @DisplayName("SeqNo is parsed and applied")
    void testSeqNoIsSet() {
        setupValidRequestParams();
        stubSuccessfulLookups();
        parameters.put("SeqNo", "10");

        ArgumentCaptor<SFEntity> captor = ArgumentCaptor.forClass(SFEntity.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        assertEquals(10L, captor.getValue().getSeqNo());
    }

    /** Verifies optional params are not applied when absent. */
    @Test
    @DisplayName("Optional params absent -> defaults remain")
    void testOptionalParamsAbsent() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(TAB_ID, TEST_ID_2);
        parameters.put(MODULE_ID, "mod1");
        stubSuccessfulLookups();

        SFEntity existingEntity = mock(SFEntity.class);
        when(existingEntity.getId()).thenReturn("ent1");
        parameters.put(ENTITY_ID, "ent1");
        when(obDal.get(SFEntity.class, "ent1")).thenReturn(existingEntity);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingEntity, never()).setIncluded(anyBoolean());
        verify(existingEntity, never()).setGet(anyBoolean());
        verify(existingEntity, never()).setGetByID(anyBoolean());
        verify(existingEntity, never()).setPost(anyBoolean());
        verify(existingEntity, never()).setPut(anyBoolean());
        verify(existingEntity, never()).setPatch(anyBoolean());
        verify(existingEntity, never()).setDelete(anyBoolean());
        verify(existingEntity, never()).setJavaQualifier(any());
        verify(existingEntity, never()).setSeqNo(any());
    }

    // ── exception handling ──────────────────────────────────────────────

    /** Verifies exception is caught and error message is set. */
    @Test
    @DisplayName("Exception caught and error set")
    void testExceptionSetsError() {
        setupValidRequestParams();
        when(obDal.get(SFSpec.class, TEST_ID_1)).thenThrow(new RuntimeException("Unexpected"));

        webhook.get(parameters, responseVars);

        assertEquals("Unexpected", responseVars.get(ERROR));
        assertNull(responseVars.get(MESSAGE));
    }

    // ── edge cases ──────────────────────────────────────────────────────

    /** Verifies special characters in Name are preserved. */
    @Test
    @DisplayName("Special characters in Name preserved")
    void testSpecialCharactersInName() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(TAB_ID, TEST_ID_2);
        parameters.put(MODULE_ID, "mod1");
        parameters.put(NAME, "Entity <>&\"' /{test}");
        stubSuccessfulLookups();

        ArgumentCaptor<SFEntity> captor = ArgumentCaptor.forClass(SFEntity.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        assertEquals("Entity <>&\"' /{test}", captor.getValue().getName());
    }

    /** Verifies input parameters map is not mutated. */
    @Test
    @DisplayName("Input params map is not mutated")
    void testInputParamsNotMutated() {
        setupValidRequestParams();
        stubSuccessfulLookups();

        Map<String, String> originalParams = new HashMap<>(parameters);

        webhook.get(parameters, responseVars);

        assertEquals(originalParams, parameters);
    }

    /** Verifies that a very long Name is handled without issues. */
    @Test
    @DisplayName("Long Name is accepted")
    void testLongStringName() {
        String longName = "B".repeat(1000);
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(TAB_ID, TEST_ID_2);
        parameters.put(MODULE_ID, "mod1");
        parameters.put(NAME, longName);
        stubSuccessfulLookups();

        ArgumentCaptor<SFEntity> captor = ArgumentCaptor.forClass(SFEntity.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        assertEquals(longName, captor.getValue().getName());
    }

    /** Verifies save and flush are each called exactly once. */
    @Test
    @DisplayName("Save and flush called exactly once")
    void testSaveAndFlushCalledOnce() {
        setupValidRequestParams();
        stubSuccessfulLookups();

        webhook.get(parameters, responseVars);

        verify(obDal, times(1)).save(any(SFEntity.class));
        verify(obDal, times(1)).flush();
    }

    /** Verifies new entity defaults: included=true, all HTTP methods=false. */
    @Test
    @DisplayName("New entity defaults: included=true, HTTP methods=false")
    void testNewEntityDefaults() {
        setupValidRequestParams();
        stubSuccessfulLookups();

        ArgumentCaptor<SFEntity> captor = ArgumentCaptor.forClass(SFEntity.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        SFEntity saved = captor.getValue();
        assertTrue(saved.isIncluded());
        assertFalse(saved.isGet());
        assertFalse(saved.isGetByID());
        assertFalse(saved.isPost());
        assertFalse(saved.isPut());
        assertFalse(saved.isPatch());
        assertFalse(saved.isDelete());
    }
}
