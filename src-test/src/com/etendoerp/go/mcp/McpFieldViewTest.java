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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Covers {@link McpFieldView} — the single resolver every MCP reader of a field's curation now
 * goes through, and the {@code isEditable()} precedence it settles.
 *
 * <p>Two of these tests are regression guards for defects that actually happened and are worth
 * more than the rest:</p>
 * <ul>
 *   <li>{@link Unconfigured#agreesWithTheOldRuleOnEveryCombinationThatExists} — the F0 invariant.
 *       For an entity with no {@code fields} section anywhere, {@code isEditable()} must answer
 *       exactly what the pre-change {@code isIncluded && !isReadOnly} rule answered. The seven
 *       {@code visibility}/{@code ISINCLUDED}/{@code ISREADONLY} combinations below are the only
 *       ones present across the 6661 active {@code ETGO_SF_FIELD} rows of the reference instance,
 *       and none of them diverges — so introducing the resolver has to stay a strict no-op for
 *       every entity that does not configure it.</li>
 *   <li>{@link Overrides#anEntityLevelOverrideCannotIncludeAnExcludedField} — the {@code &&
 *       included} term. An entity-level {@code visibility:"editable"} must reclassify the fields
 *       the spec already exposes and leave the excluded ones excluded. Without the term,
 *       {@code bp-location/bpLocation}'s override promoted all ten {@code C_Location} rows where
 *       the spec includes six, primary key among them ({@code C_Location_ID}, mandatory and
 *       {@code AD_Column.isUpdateable = 'N'}). {@code ISINCLUDED} — is the field on the MCP
 *       surface at all — and {@code VISIBILITY} — how it is classified once it is — are different
 *       axes, and a classification override may never flip inclusion.</li>
 * </ul>
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpFieldView")
class McpFieldViewTest {

  /** Unique record ids per field/entity/spec mock: McpConfigCache is static and keyed by id. */
  private static final AtomicInteger SEQ = new AtomicInteger();

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

  /**
   * A field carrying raw curation and no {@code MCP_CONFIG} anywhere in its chain.
   *
   * @param visibility the {@code VISIBILITY} column, may be {@code null}
   * @param included   the {@code ISINCLUDED} column
   * @param readOnly   the {@code ISREADONLY} column
   */
  private static SFField field(String visibility, Boolean included, Boolean readOnly) {
    return field(visibility, included, readOnly, null, null, null);
  }

  /**
   * A field with raw curation plus an {@code MCP_CONFIG} payload at any of the three levels.
   *
   * @param specConfig   the spec-level column, or {@code null} for none
   * @param entityConfig the entity-level column, or {@code null} for none
   * @param fieldConfig  the field-level column, or {@code null} for none
   */
  private static SFField field(String visibility, Boolean included, Boolean readOnly,
      String specConfig, String entityConfig, String fieldConfig) {
    int id = SEQ.incrementAndGet();
    SFField field = mock(SFField.class);
    when(field.getId()).thenReturn("field-" + id);
    when(field.getVisibility()).thenReturn(visibility);
    when(field.isIncluded()).thenReturn(included);
    when(field.isReadOnly()).thenReturn(readOnly);
    when(field.isBusinessCritical()).thenReturn(Boolean.FALSE);
    when(field.get(SFField.PROPERTY_MCPCONFIG)).thenReturn(fieldConfig);

    if (specConfig == null && entityConfig == null) {
      // No entity in the chain: forField() then reads the field level alone, which is enough for
      // every raw-curation case and keeps those mocks minimal.
      return field;
    }
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("entity-" + id);
    when(entity.get(SFEntity.PROPERTY_MCPCONFIG)).thenReturn(entityConfig);
    when(field.getETGOSFEntity()).thenReturn(entity);
    if (specConfig != null) {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-" + id);
      when(spec.get(SFSpec.PROPERTY_MCPCONFIG)).thenReturn(specConfig);
      when(entity.getETGOSFSpec()).thenReturn(spec);
    }
    return field;
  }

  /** A {@code fields} section body, wrapped in the section envelope the column holds. */
  private static String fieldsSection(String keys) {
    return "{\"fields\":{" + keys + ",\"reason\":\"test fixture\"}}";
  }

  @Nested
  @DisplayName("unconfigured entities — the F0 invariant")
  class Unconfigured {

    /**
     * The pre-change derivation, spelled out so the assertion compares against the rule rather
     * than against a hand-copied expectation.
     */
    private boolean oldRule(Boolean included, Boolean readOnly) {
      return Boolean.TRUE.equals(included) && !Boolean.TRUE.equals(readOnly);
    }

    private void assertNoOp(String visibility, Boolean included, Boolean readOnly, int liveRows) {
      boolean expected = oldRule(included, readOnly);
      assertEquals(expected, McpFieldView.of(field(visibility, included, readOnly)).isEditable(),
          "visibility=" + visibility + " included=" + included + " readOnly=" + readOnly
              + " (" + liveRows + " live rows) must answer what isIncluded && !isReadOnly answered");
    }

    @Test
    @DisplayName("agrees with the old isIncluded && !isReadOnly rule on every combination that "
        + "exists in the data")
    void agreesWithTheOldRuleOnEveryCombinationThatExists() {
      // The seven combinations across 6661 active ETGO_SF_FIELD rows, with their row counts. If
      // the resolver diverged on any of them, introducing it would have changed neo_selectors'
      // answer for entities nobody configured.
      assertNoOp("system", Boolean.TRUE, Boolean.TRUE, 1906);
      assertNoOp(null, Boolean.FALSE, Boolean.FALSE, 1739);
      assertNoOp("discarded", Boolean.FALSE, Boolean.FALSE, 1106);
      assertNoOp("editable", Boolean.TRUE, Boolean.FALSE, 1040);
      assertNoOp("readOnly", Boolean.TRUE, Boolean.TRUE, 457);
      assertNoOp(null, Boolean.TRUE, Boolean.TRUE, 285);
      assertNoOp(null, Boolean.TRUE, Boolean.FALSE, 128);
    }

    @Test
    @DisplayName("a null visibility falls back to inclusion and read-only, nothing else")
    void nullVisibilityFallsBack() {
      assertTrue(McpFieldView.of(field(null, Boolean.TRUE, Boolean.FALSE)).isEditable());
      assertFalse(McpFieldView.of(field(null, Boolean.TRUE, Boolean.TRUE)).isEditable());
      assertFalse(McpFieldView.of(field(null, Boolean.FALSE, Boolean.FALSE)).isEditable());
      assertFalse(McpFieldView.of(field(null, Boolean.FALSE, Boolean.TRUE)).isEditable());
    }

    @Test
    @DisplayName("a blank visibility is the same as none — 1739 rows carry NULL, and a whitespace "
        + "one must not read as a fourth classification")
    void blankVisibilityIsUncurated() {
      McpFieldView view = McpFieldView.of(field("   ", Boolean.TRUE, Boolean.FALSE));
      assertNull(view.getVisibility(), "an uncurated field must keep an ABSENT visibility, because"
          + " that is what neo_schema has always emitted for it");
      assertTrue(view.isEditable());
    }

    @Test
    @DisplayName("a null SFField is unconfigured and never editable")
    void nullField() {
      McpFieldView view = McpFieldView.of(null);
      assertNull(view.getVisibility());
      assertFalse(view.isReadOnly());
      assertFalse(view.isBusinessCritical());
      assertFalse(view.isEditable());
    }

    @Test
    @DisplayName("a null ISINCLUDED/ISREADONLY reads as false, never as the permissive answer")
    void nullBooleanColumns() {
      assertFalse(McpFieldView.of(field("editable", null, null)).isEditable(),
          "included=null must not be read as included");
      assertTrue(McpFieldView.of(field("editable", Boolean.TRUE, null)).isEditable());
      assertFalse(McpFieldView.of(field("editable", Boolean.TRUE, null)).isReadOnly());
    }
  }

  @Nested
  @DisplayName("isEditable precedence")
  class Precedence {

    @Test
    @DisplayName("included=false wins over everything, including an editable visibility")
    void notIncludedIsNeverEditable() {
      assertFalse(McpFieldView.of(field("editable", Boolean.FALSE, Boolean.FALSE)).isEditable());
      assertFalse(McpFieldView.of(field("editable", Boolean.FALSE, Boolean.TRUE)).isEditable());
      assertFalse(McpFieldView.of(field(null, Boolean.FALSE, Boolean.FALSE)).isEditable());
    }

    @Test
    @DisplayName("readOnly=true wins over everything, including an editable visibility")
    void readOnlyIsNeverEditable() {
      assertFalse(McpFieldView.of(field("editable", Boolean.TRUE, Boolean.TRUE)).isEditable());
      assertFalse(McpFieldView.of(field(null, Boolean.TRUE, Boolean.TRUE)).isEditable());
    }

    @Test
    @DisplayName("visibility=editable on an included, writable field is editable")
    void editableIsEditable() {
      assertTrue(McpFieldView.of(field("editable", Boolean.TRUE, Boolean.FALSE)).isEditable());
    }

    @Test
    @DisplayName("system and discarded can never be editable, whatever the booleans say")
    void systemAndDiscardedAreNeverEditable() {
      // The narrowing the resolver adds: where a curated classification exists it further
      // restricts the boolean derivation, and never widens it. No live row is in these
      // combinations (see the F0 table), so this is the guard, not a behaviour change.
      for (Boolean readOnly : new Boolean[] { Boolean.TRUE, Boolean.FALSE, null }) {
        assertFalse(McpFieldView.of(field("system", Boolean.TRUE, readOnly)).isEditable(),
            "system must never be editable (readOnly=" + readOnly + ")");
        assertFalse(McpFieldView.of(field("discarded", Boolean.TRUE, readOnly)).isEditable(),
            "discarded must never be editable (readOnly=" + readOnly + ")");
      }
    }

    @Test
    @DisplayName("visibility=readOnly is not editable even when ISREADONLY says otherwise")
    void readOnlyVisibilityIsNotEditable() {
      assertFalse(McpFieldView.of(field("readOnly", Boolean.TRUE, Boolean.FALSE)).isEditable());
    }

    @Test
    @DisplayName("an unknown visibility string is not editable — an unrecognised classification "
        + "must not read as editable")
    void unknownVisibilityIsNotEditable() {
      assertFalse(McpFieldView.of(field("writable", Boolean.TRUE, Boolean.FALSE)).isEditable());
    }
  }

  @Nested
  @DisplayName("MCP_CONFIG fields overrides")
  class Overrides {

    @Test
    @DisplayName("an entity-level visibility replaces the raw SFField value")
    void entityLevelVisibilityWins() {
      // bp-location/bpLocation: all ten C_Location rows carry VISIBILITY = NULL, so the MCP's
      // method gate hid POST and no BP address could be created by an agent at all.
      SFField sfField = field(null, Boolean.TRUE, Boolean.FALSE, null,
          fieldsSection("\"visibility\":\"editable\""), null);
      McpFieldView view = McpFieldView.of(sfField);
      assertEquals("editable", view.getVisibility());
      assertTrue(view.isEditable());
    }

    @Test
    @DisplayName("an entity-level override cannot make an excluded field editable")
    void anEntityLevelOverrideCannotIncludeAnExcludedField() {
      // The && included term. C_Location_ID is the primary key, ISINCLUDED = 'N', and AD marks it
      // non-updatable; the entity-level editable override must leave it excluded. Widening the
      // surface is ISINCLUDED's job and belongs in the shared contract, not in an MCP override.
      SFField primaryKey = field(null, Boolean.FALSE, Boolean.FALSE, null,
          fieldsSection("\"visibility\":\"editable\""), null);
      McpFieldView view = McpFieldView.of(primaryKey);
      assertEquals("editable", view.getVisibility(),
          "the classification is reclassified — that half of the override still applies");
      assertFalse(view.isEditable(),
          "but inclusion is a different axis: an excluded field stays excluded");
    }

    @Test
    @DisplayName("an override can narrow as well as widen — editable plus readOnly:true is not "
        + "editable")
    void overrideCanNarrow() {
      SFField sfField = field("editable", Boolean.TRUE, Boolean.FALSE, null,
          fieldsSection("\"readOnly\":true"), null);
      McpFieldView view = McpFieldView.of(sfField);
      assertTrue(view.isReadOnly());
      assertFalse(view.isEditable());
    }

    @Test
    @DisplayName("readOnly:false clears the row's own ISREADONLY")
    void overrideClearsReadOnly() {
      SFField sfField = field("editable", Boolean.TRUE, Boolean.TRUE, null,
          fieldsSection("\"readOnly\":false"), null);
      McpFieldView view = McpFieldView.of(sfField);
      assertFalse(view.isReadOnly());
      assertTrue(view.isEditable());
    }

    @Test
    @DisplayName("businessCritical is overridden independently of editability")
    void overridesBusinessCritical() {
      SFField sfField = field("editable", Boolean.TRUE, Boolean.FALSE, null,
          fieldsSection("\"businessCritical\":true"), null);
      assertTrue(McpFieldView.of(sfField).isBusinessCritical());
    }

    @Test
    @DisplayName("a key the override omits leaves the row's value alone")
    void absentKeysDoNotOverwrite() {
      // The section is REPLACE across levels, not across keys: an absent readOnly must not be
      // read as a configured false, or every override would silently unlock its fields.
      SFField sfField = field("system", Boolean.TRUE, Boolean.TRUE, null,
          fieldsSection("\"businessCritical\":true"), null);
      McpFieldView view = McpFieldView.of(sfField);
      assertEquals("system", view.getVisibility());
      assertTrue(view.isReadOnly());
      assertFalse(view.isEditable());
    }

    @Test
    @DisplayName("the field level wins over the entity level (REPLACE, most specific first)")
    void fieldLevelWinsOverEntityLevel() {
      SFField sfField = field(null, Boolean.TRUE, Boolean.FALSE, null,
          fieldsSection("\"visibility\":\"editable\""),
          fieldsSection("\"visibility\":\"system\""));
      McpFieldView view = McpFieldView.of(sfField);
      assertEquals("system", view.getVisibility());
      assertFalse(view.isEditable(), "the more specific level is the one that decides");
    }

    @Test
    @DisplayName("the entity level wins over the spec level")
    void entityLevelWinsOverSpecLevel() {
      SFField sfField = field(null, Boolean.TRUE, Boolean.FALSE,
          fieldsSection("\"visibility\":\"discarded\""),
          fieldsSection("\"visibility\":\"editable\""), null);
      McpFieldView view = McpFieldView.of(sfField);
      assertEquals("editable", view.getVisibility());
      assertTrue(view.isEditable());
    }

    @Test
    @DisplayName("a payload that failed validation is ignored and the raw values stand")
    void invalidPayloadIsNotActedOn() {
      // Acting on a body that was reported as broken is the one way this resolver could widen the
      // agent surface without anybody having asked it to. Missing reason == invalid.
      SFField sfField = field("system", Boolean.TRUE, Boolean.TRUE, null,
          "{\"fields\":{\"visibility\":\"editable\"}}", null);
      McpFieldView view = McpFieldView.of(sfField);
      assertEquals("system", view.getVisibility());
      assertTrue(view.isReadOnly());
      assertFalse(view.isEditable());
    }

    @Test
    @DisplayName("an unknown key inside the section is not acted on")
    void anUnknownKeyInsideTheSectionIsNotActedOn() {
      // End to end for the allowed-keys policy: 'readonly' is a typo, and a typo that read as
      // "unconfigured" would be indistinguishable from a decision. The whole chain is unusable,
      // so even the sibling visibility key is ignored.
      SFField sfField = field("system", Boolean.TRUE, Boolean.TRUE, null,
          fieldsSection("\"visibility\":\"editable\",\"readonly\":false"), null);
      McpFieldView view = McpFieldView.of(sfField);
      assertEquals("system", view.getVisibility());
      assertFalse(view.isEditable());
    }

    @Test
    @DisplayName("unparseable JSON is ignored rather than read as 'no configuration'")
    void unparseableConfigIsIgnored() {
      SFField sfField = field("editable", Boolean.TRUE, Boolean.FALSE, null, "{not json", null);
      assertEquals("editable", McpFieldView.of(sfField).getVisibility());
      assertTrue(McpFieldView.of(field("editable", Boolean.TRUE, Boolean.FALSE, null,
          "{not json", null)).isEditable(), "the raw row still stands");
    }

    @Test
    @DisplayName("a chain with no fields section at all leaves every raw value untouched")
    void otherSectionsDoNotInterfere() {
      SFField sfField = field("editable", Boolean.TRUE, Boolean.FALSE, null,
          "{\"parent\":{\"field\":\"businessPartner\"}}", null);
      McpFieldView view = McpFieldView.of(sfField);
      assertEquals("editable", view.getVisibility());
      assertTrue(view.isEditable(), "another feature's section must not change this answer");
    }
  }
}
