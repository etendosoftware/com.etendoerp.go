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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Architecture regression test for ETP-5184 — the three MCP readers of a field's curation cannot
 * disagree.
 *
 * <p>{@code neo_schema}'s field metadata, {@code neo_selectors}' editable-property set and the
 * resource provider's field list each derived {@code visibility}/{@code readOnly}/
 * {@code businessCritical} straight off {@code SFField}, with their own arithmetic — one read the
 * curated string, one ignored it entirely and computed {@code isIncluded && !isReadOnly}, one read
 * {@code isReadOnly} alone. An {@code MCP_CONFIG} {@code fields} override honoured by only some of
 * them is a worse state than no override at all: the agent is told a field is {@code editable} by
 * one tool and not editable by another, with nothing in either response admitting the
 * disagreement.</p>
 *
 * <p>Nothing in a signature or a type can catch a reader that stops routing through
 * {@link McpFieldView} — the drift is a <b>call site</b>, and each reader's own unit tests would
 * keep passing. All four methods need an {@code OBContext}, a live DAL and an {@code AD_Tab}, so
 * the call site cannot be asserted behaviourally; the behavioural half of the contract is pinned at
 * the {@link McpFieldView} seam instead, both here
 * ({@link #oneViewAnswersEveryReaderTheSameWay}) and in {@code McpFieldViewTest}.</p>
 *
 * <p>{@code McpQuerySupport.summaryFields} was the fourth reader, found after the first three were
 * unified: it read {@code sfField.isBusinessCritical()} straight off the row, so a {@code fields}
 * override setting {@code businessCritical} was honoured by {@code neo_schema} and ignored by
 * {@code neo_list}/{@code neo_get} with {@code view:"summary"} — an agent told a field is
 * business-critical, then handed a projection that omits it. Routed and guarded here.</p>
 */
@DisplayName("ETP-5184 — every MCP reader of a field's curation goes through McpFieldView")
class McpFieldViewSingleResolverCallSiteTest {

  /**
   * The readers ETP-5184 unified, as {@code {source file, method name}} pairs.
   *
   * <p>A list rather than a map keyed by file: {@code McpQuerySupport} contributes two readers, and
   * under a map a third one added without a disambiguating key would silently <i>replace</i> a
   * sibling — the size would still be four and the displaced reader would quietly stop being
   * guarded. A guard whose whole purpose is not being mute must not be able to lose an entry.</p>
   */
  private static final List<String[]> READERS = List.of(
      new String[] { "com/etendoerp/go/mcp/McpSchemaFieldBuilder.java", "loadFieldMetadata" },
      new String[] { "com/etendoerp/go/mcp/McpQuerySupport.java", "editablePropertyNames" },
      new String[] { "com/etendoerp/go/mcp/McpQuerySupport.java", "summaryFields" },
      new String[] { "com/etendoerp/go/mcp/McpResourceProvider.java", "buildFieldsArray" });

  /** The one resolver every reader must go through. */
  private static final Pattern RESOLVER_CALL =
      Pattern.compile("McpFieldView\\s*\\.\\s*of\\s*\\(");

  /**
   * A curation property read straight off the {@code SFField} loop variable — the shape all four
   * readers used before ETP-5184, and the shape a regression would take.
   */
  private static final Pattern RAW_FIELD_READ = Pattern.compile(
      "\\b(sfField|field)\\s*\\.\\s*(getVisibility|isReadOnly|isBusinessCritical)\\s*\\(");

  @BeforeEach
  void registerRealSections() {
    // Other test classes in this package clear McpEntityConfig's registrations without resetting
    // the bootstrap flag, which would leave ensureRegistered() a no-op and make the real 'fields'
    // section read as unknown. Reset both so this class always resolves against the real sections.
    McpConfigSections.resetForTests();
    McpConfigCache.invalidateAll();
  }

  @AfterEach
  void clean() {
    McpConfigSections.resetForTests();
    McpConfigCache.invalidateAll();
  }

  @Test
  @DisplayName("every reader resolves through McpFieldView and none reads SFField raw")
  void everyReaderRoutesThroughTheResolver() {
    List<String> violations = new ArrayList<>();
    for (String[] reader : READERS) {
      String method = reader[1];
      String body = McpSourceScanner.methodBody(McpSourceScanner.read(reader[0]), method);
      if (!RESOLVER_CALL.matcher(body).find()) {
        violations.add(method + " does not call McpFieldView.of(...)");
      }
      if (RAW_FIELD_READ.matcher(body).find()) {
        violations.add(method + " reads a curation property straight off the SFField row");
      }
    }
    if (!violations.isEmpty()) {
      fail("MCP readers disagree about a field's curation: " + violations
          + ". Resolve through McpFieldView.of(sfField) instead. Without it an MCP_CONFIG 'fields'"
          + " override is honoured by some tools and not others, so neo_schema can report a field"
          + " as editable while neo_selectors reports otherwise — and neither response says so.");
    }
  }

  @Test
  @DisplayName("the scan still finds every reader — a guard that finds nothing is mute, "
      + "not passing")
  void theScanStillResolvesEveryReader() {
    assertEquals(4, READERS.size(), "ETP-5184 unified exactly four readers");
    for (String[] reader : READERS) {
      String method = reader[1];
      String body = McpSourceScanner.methodBody(McpSourceScanner.read(reader[0]), method);
      assertTrue(body.length() > 100,
          method + " was resolved to a body of " + body.length() + " chars, which means"
              + " the extractor matched the wrong thing — fix this test, not the source");
      assertTrue(body.contains("SFField"),
          method + " no longer mentions SFField, so the guard above has nothing left to"
              + " check — the reader was probably refactored elsewhere");
    }
  }

  /**
   * The behavioural half: one {@link McpFieldView} instance is the single answer every reader
   * reports, so the properties they each consume are mutually consistent by construction.
   *
   * <p>The drift scenario spelled out: {@code neo_schema} publishes {@code visibility} and
   * {@code readOnly} from the view, {@code neo_selectors} publishes {@code isEditable()}, and the
   * resource provider publishes {@code readOnly}, and {@code summaryFields} publishes
   * {@code isBusinessCritical()}. If the override moved only one of them, the readers would
   * contradict each other for the same field.</p>
   */
  @Test
  @DisplayName("one resolved view answers every reader the same way")
  void oneViewAnswersEveryReaderTheSameWay() {
    McpFieldView view = McpFieldView.of(overriddenField("editable"));

    // neo_schema's two properties, neo_selectors' one, the resource provider's one.
    assertEquals("editable", view.getVisibility());
    assertFalse(view.isReadOnly());
    assertTrue(view.isEditable());
    // summaryFields' one: the fixture leaves it false, so the view must not invent a true.
    assertFalse(view.isBusinessCritical());

    // And the same field narrowed: no reader can be left on the pre-override answer.
    McpFieldView demoted = McpFieldView.of(overriddenField("system"));
    assertEquals("system", demoted.getVisibility());
    assertFalse(demoted.isEditable());
    assertFalse(demoted.isReadOnly(), "the override moved visibility only, so readOnly stands");
  }

  /**
   * A field whose {@code VISIBILITY} column is {@code NULL} — the bp-location shape — with an
   * entity-level {@code fields} override reclassifying it.
   */
  private SFField overriddenField(String visibility) {
    SFField field = mock(SFField.class);
    when(field.getId()).thenReturn("call-site-field-" + visibility);
    when(field.getVisibility()).thenReturn(null);
    when(field.isIncluded()).thenReturn(Boolean.TRUE);
    when(field.isReadOnly()).thenReturn(Boolean.FALSE);
    when(field.isBusinessCritical()).thenReturn(Boolean.FALSE);
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("call-site-entity-" + visibility);
    when(entity.get(SFEntity.PROPERTY_MCPCONFIG)).thenReturn("{\"fields\":{\"visibility\":\""
        + visibility + "\",\"reason\":\"call-site fixture\"}}");
    when(field.getETGOSFEntity()).thenReturn(entity);
    return field;
  }
}
