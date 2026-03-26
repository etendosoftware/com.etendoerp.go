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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link SFUpsertSpec}.
 * Covers happy paths (create/update), validation errors, optional params,
 * edge cases and save/flush verification.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFUpsertSpecTest extends BaseWebhookTest {

    @InjectMocks
    private SFUpsertSpec webhook;

    private Window mockWindow;
    private Process mockProcess;
    private Module mockModule;

    @BeforeEach
    void setUp() {
        webhook = new SFUpsertSpec();
        mockWindow = mock(Window.class);
        mockProcess = mock(Process.class);
        mockModule = mock(Module.class);

        // Stub duplicate-name criteria to return empty list (no existing spec with same name).
        OBCriteria<SFSpec> specCriteria = mockCriteria(SFSpec.class);
        when(specCriteria.list()).thenReturn(Collections.emptyList());

        // Stub OBProvider to return a mock SFSpec that tracks state via setters.
        SFSpec newSpec = mock(SFSpec.class);
        when(newSpec.getId()).thenReturn("new-spec-id");

        AtomicReference<String> specName = new AtomicReference<>("");
        doAnswer(inv -> { specName.set(inv.getArgument(0)); return null; })
            .when(newSpec).setName(any());
        when(newSpec.getName()).thenAnswer(inv -> specName.get());

        when(obProvider.get(SFSpec.class)).thenReturn(newSpec);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void setupValidWindowParams() {
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(WINDOW_ID, TEST_ID_2);
    }

    private void setupValidProcessParams() {
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(PROCESS_ID, TEST_ID_2);
        parameters.put(SPEC_TYPE, "P");
    }

    private void stubWindowAndModule() {
        when(obDal.get(Window.class, TEST_ID_2)).thenReturn(mockWindow);
        when(obDal.get(Module.class, TEST_ID_1)).thenReturn(mockModule);
    }

    private void stubProcessAndModule() {
        when(obDal.get(Process.class, TEST_ID_2)).thenReturn(mockProcess);
        when(obDal.get(Module.class, TEST_ID_1)).thenReturn(mockModule);
    }

    // ── happy paths ─────────────────────────────────────────────────────

    /** Verifies a new Window spec is created with correct response keys. */
    @Test
    @DisplayName("Create Window spec - happy path")
    void testCreateWindowSpecHappyPath() {
        setupValidWindowParams();
        stubWindowAndModule();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertTrue(responseVars.get(MESSAGE).contains("Window Spec upserted"));
        assertEquals("W", responseVars.get(SPEC_TYPE));
        assertNotNull(responseVars.get(SPEC_ID));
        verify(obDal).save(any(SFSpec.class));
        verify(obDal).flush();
    }

    /** Verifies a new Process spec is created with correct response keys. */
    @Test
    @DisplayName("Create Process spec - happy path")
    void testCreateProcessSpecHappyPath() {
        setupValidProcessParams();
        stubProcessAndModule();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertTrue(responseVars.get(MESSAGE).contains("Process Spec upserted"));
        assertEquals("P", responseVars.get(SPEC_TYPE));
        verify(obDal).save(any(SFSpec.class));
        verify(obDal).flush();
    }

    // ── update / existing entity ────────────────────────────────────────

    /** Verifies an existing spec is loaded and updated when SpecID is provided. */
    @Test
    @DisplayName("Update existing spec sets name and window")
    void testUpdateExistingSpec() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(NAME, "Updated");
        parameters.put(MODULE_ID, "mod1");
        parameters.put(WINDOW_ID, "win1");

        SFSpec existingSpec = mock(SFSpec.class);
        when(existingSpec.getId()).thenReturn(TEST_ID_1);

        when(obDal.get(SFSpec.class, TEST_ID_1)).thenReturn(existingSpec);
        when(obDal.get(Window.class, "win1")).thenReturn(mockWindow);
        when(obDal.get(Module.class, "mod1")).thenReturn(mockModule);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingSpec).setName("Updated");
        verify(existingSpec).setADWindow(mockWindow);
        verify(obDal).save(existingSpec);
        verify(obDal).flush();
    }

    // ── not-found / missing param errors ────────────────────────────────

    /** Verifies error when SpecID references a non-existent record. */
    @Test
    @DisplayName("Spec not found returns error")
    void testSpecNotFoundOnUpdate() {
        parameters.put(SPEC_ID, "nonexistent");
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(WINDOW_ID, TEST_ID_2);

        when(obDal.get(SFSpec.class, "nonexistent")).thenReturn(null);

        webhook.get(parameters, responseVars);

        assertEquals("Spec not found: nonexistent", responseVars.get(ERROR));
        verify(obDal, never()).save(any());
        verify(obDal, never()).flush();
    }

    /** Verifies error when an invalid SpecType is provided. */
    @Test
    @DisplayName("Invalid SpecType returns error")
    void testInvalidSpecType() {
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(SPEC_TYPE, "Z");

        webhook.get(parameters, responseVars);

        assertEquals("Invalid SpecType: Z. Must be W or P.", responseVars.get(ERROR));
        verify(obDal, never()).save(any());
    }

    /** Verifies error when WindowID is missing for a Window spec. */
    @Test
    @DisplayName("Missing WindowID for SpecType=W returns error")
    void testWindowSpecMissingWindowID() {
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(SPEC_TYPE, "W");

        webhook.get(parameters, responseVars);

        assertEquals("WindowID is required when SpecType is W", responseVars.get(ERROR));
    }

    /** Verifies error when ProcessID is missing for a Process spec. */
    @Test
    @DisplayName("Missing ProcessID for SpecType=P returns error")
    void testProcessSpecMissingProcessID() {
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(SPEC_TYPE, "P");

        webhook.get(parameters, responseVars);

        assertEquals("ProcessID is required when SpecType is P", responseVars.get(ERROR));
    }

    /** Verifies error when the referenced Window does not exist. */
    @Test
    @DisplayName("Window not found returns error")
    void testWindowNotFound() {
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(WINDOW_ID, "bad");

        when(obDal.get(Window.class, "bad")).thenReturn(null);

        webhook.get(parameters, responseVars);

        assertEquals("Window not found: bad", responseVars.get(ERROR));
    }

    /** Verifies error when the referenced Process does not exist. */
    @Test
    @DisplayName("Process not found returns error")
    void testProcessNotFound() {
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(PROCESS_ID, "bad");
        parameters.put(SPEC_TYPE, "P");

        when(obDal.get(Process.class, "bad")).thenReturn(null);

        webhook.get(parameters, responseVars);

        assertEquals("Process not found: bad", responseVars.get(ERROR));
    }

    /** Verifies error when the referenced Module does not exist. */
    @Test
    @DisplayName("Module not found returns error")
    void testModuleNotFound() {
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, "bad");
        parameters.put(WINDOW_ID, TEST_ID_2);

        when(obDal.get(Window.class, TEST_ID_2)).thenReturn(mockWindow);
        when(obDal.get(Module.class, "bad")).thenReturn(null);

        webhook.get(parameters, responseVars);

        assertEquals("Module not found: bad", responseVars.get(ERROR));
        verify(obDal, never()).save(any());
    }

    // ── defaults ────────────────────────────────────────────────────────

    /** Verifies SpecType defaults to W when not provided. */
    @Test
    @DisplayName("SpecType defaults to W when absent")
    void testSpecTypeDefaultsToW() {
        setupValidWindowParams();
        stubWindowAndModule();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("W", responseVars.get(SPEC_TYPE));
    }

    /** Verifies SpecType defaults to W when provided as empty string. */
    @Test
    @DisplayName("Empty SpecType defaults to W")
    void testEmptySpecTypeDefaultsToW() {
        setupValidWindowParams();
        parameters.put(SPEC_TYPE, "");
        stubWindowAndModule();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("W", responseVars.get(SPEC_TYPE));
    }

    // ── optional params ─────────────────────────────────────────────────

    /** Verifies Description is set when a non-empty value is provided. */
    @Test
    @DisplayName("Description is set when provided")
    void testDescriptionIsSetWhenProvided() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, "mod1");
        parameters.put(WINDOW_ID, "win1");
        parameters.put(DESCRIPTION, TEST_DESCRIPTION);

        SFSpec existingSpec = mock(SFSpec.class);
        when(existingSpec.getId()).thenReturn(TEST_ID_1);
        when(obDal.get(SFSpec.class, TEST_ID_1)).thenReturn(existingSpec);
        when(obDal.get(Window.class, "win1")).thenReturn(mockWindow);
        when(obDal.get(Module.class, "mod1")).thenReturn(mockModule);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingSpec).setDescription(TEST_DESCRIPTION);
    }

    /** Verifies Description is NOT set when provided as empty string. */
    @Test
    @DisplayName("Empty Description is not applied")
    void testDescriptionNotSetWhenEmpty() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, "mod1");
        parameters.put(WINDOW_ID, "win1");
        parameters.put(DESCRIPTION, "");

        SFSpec existingSpec = mock(SFSpec.class);
        when(existingSpec.getId()).thenReturn(TEST_ID_1);
        when(obDal.get(SFSpec.class, TEST_ID_1)).thenReturn(existingSpec);
        when(obDal.get(Window.class, "win1")).thenReturn(mockWindow);
        when(obDal.get(Module.class, "mod1")).thenReturn(mockModule);

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(existingSpec, never()).setDescription(any());
    }

    /** Verifies Description is NOT set when absent from params. */
    @Test
    @DisplayName("Absent Description is not applied")
    void testDescriptionNotSetWhenAbsent() {
        setupValidWindowParams();
        stubWindowAndModule();

        ArgumentCaptor<SFSpec> captor = ArgumentCaptor.forClass(SFSpec.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        // Description should remain null on new spec since it was never set
        assertNull(captor.getValue().getDescription());
    }

    // ── cross-type clearing ─────────────────────────────────────────────

    /** Verifies Process spec clears window reference. */
    @Test
    @DisplayName("Process spec clears window reference")
    void testProcessSpecClearsWindow() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, "mod1");
        parameters.put(PROCESS_ID, "proc1");
        parameters.put(SPEC_TYPE, "P");

        SFSpec existingSpec = mock(SFSpec.class);
        when(existingSpec.getId()).thenReturn(TEST_ID_1);
        when(obDal.get(SFSpec.class, TEST_ID_1)).thenReturn(existingSpec);
        when(obDal.get(Process.class, "proc1")).thenReturn(mockProcess);
        when(obDal.get(Module.class, "mod1")).thenReturn(mockModule);

        webhook.get(parameters, responseVars);

        verify(existingSpec).setProcess(mockProcess);
        verify(existingSpec).setADWindow(null);
    }

    /** Verifies Window spec clears process reference. */
    @Test
    @DisplayName("Window spec clears process reference")
    void testWindowSpecClearsProcess() {
        parameters.put(SPEC_ID, TEST_ID_1);
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, "mod1");
        parameters.put(WINDOW_ID, "win1");
        parameters.put(SPEC_TYPE, "W");

        SFSpec existingSpec = mock(SFSpec.class);
        when(existingSpec.getId()).thenReturn(TEST_ID_1);
        when(obDal.get(SFSpec.class, TEST_ID_1)).thenReturn(existingSpec);
        when(obDal.get(Window.class, "win1")).thenReturn(mockWindow);
        when(obDal.get(Module.class, "mod1")).thenReturn(mockModule);

        webhook.get(parameters, responseVars);

        verify(existingSpec).setADWindow(mockWindow);
        verify(existingSpec).setProcess(null);
    }

    // ── exception handling ──────────────────────────────────────────────

    /** Verifies that a runtime exception is caught and put into error response. */
    @Test
    @DisplayName("Exception is caught and sets error in response")
    void testExceptionSetsError() {
        setupValidWindowParams();
        when(obDal.get(Window.class, TEST_ID_2)).thenThrow(new RuntimeException("DB down"));

        webhook.get(parameters, responseVars);

        assertEquals("DB down", responseVars.get(ERROR));
        assertNull(responseVars.get(MESSAGE));
    }

    // ── edge cases ──────────────────────────────────────────────────────

    /** Verifies special characters in Name are passed through correctly. */
    @Test
    @DisplayName("Special characters in Name are preserved")
    void testSpecialCharactersInName() {
        parameters.put(NAME, "Test <Spec> & \"Quoted\" / 'Apostrophe'");
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(WINDOW_ID, TEST_ID_2);
        stubWindowAndModule();

        ArgumentCaptor<SFSpec> captor = ArgumentCaptor.forClass(SFSpec.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        assertEquals("Test <Spec> & \"Quoted\" / 'Apostrophe'", captor.getValue().getName());
    }

    /** Verifies that whitespace-only WindowID is treated as missing. */
    @Test
    @DisplayName("Blank WindowID treated as missing")
    void testBlankWindowIdTreatedAsMissing() {
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(WINDOW_ID, "");
        parameters.put(SPEC_TYPE, "W");

        webhook.get(parameters, responseVars);

        assertEquals("WindowID is required when SpecType is W", responseVars.get(ERROR));
    }

    /** Verifies that whitespace-only ProcessID is treated as missing. */
    @Test
    @DisplayName("Blank ProcessID treated as missing")
    void testBlankProcessIdTreatedAsMissing() {
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(PROCESS_ID, "");
        parameters.put(SPEC_TYPE, "P");

        webhook.get(parameters, responseVars);

        assertEquals("ProcessID is required when SpecType is P", responseVars.get(ERROR));
    }

    /** Verifies that the input parameters map is not mutated by the webhook. */
    @Test
    @DisplayName("Input params map is not mutated")
    void testInputParamsNotMutated() {
        setupValidWindowParams();
        stubWindowAndModule();

        Map<String, String> originalParams = new HashMap<>(parameters);

        webhook.get(parameters, responseVars);

        assertEquals(originalParams, parameters);
    }

    /** Verifies a long string name is handled without issues. */
    @Test
    @DisplayName("Long string name is accepted")
    void testLongStringName() {
        String longName = "A".repeat(500);
        parameters.put(NAME, longName);
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(WINDOW_ID, TEST_ID_2);
        stubWindowAndModule();

        ArgumentCaptor<SFSpec> captor = ArgumentCaptor.forClass(SFSpec.class);
        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        verify(obDal).save(captor.capture());
        assertEquals(longName, captor.getValue().getName());
    }

    /** Verifies save and flush are each called exactly once on success. */
    @Test
    @DisplayName("Save and flush called exactly once")
    void testSaveAndFlushCalledOnce() {
        setupValidWindowParams();
        stubWindowAndModule();

        webhook.get(parameters, responseVars);

        verify(obDal, times(1)).save(any(SFSpec.class));
        verify(obDal, times(1)).flush();
    }

    /** Verifies that null SpecType (absent key) defaults correctly to W. */
    @Test
    @DisplayName("Null SpecType defaults to W")
    void testNullSpecTypeDefaultsToW() {
        parameters.put(NAME, TEST_NAME);
        parameters.put(MODULE_ID, TEST_ID_1);
        parameters.put(WINDOW_ID, TEST_ID_2);
        // SpecType key not present at all
        stubWindowAndModule();

        webhook.get(parameters, responseVars);

        assertNull(responseVars.get(ERROR));
        assertEquals("W", responseVars.get(SPEC_TYPE));
    }
}
