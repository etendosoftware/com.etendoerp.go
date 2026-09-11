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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.mcp.McpConfigSection.Merge;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Covers the parts of {@link McpEntityConfig} and {@link McpConfigSection} that do not need a DAL:
 * section registration, and the validation contract that decides whether a payload is usable.
 *
 * <p>The chain resolution itself ({@code forEntity} / {@code forField} / {@code forSpec}) reads
 * {@code SFEntity} and its {@code MCP_CONFIG} property, so it belongs in an integration test
 * against a real instance rather than here.</p>
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpEntityConfig")
class McpEntityConfigTest {

  private static final String SECTION = "parent";

  private static McpConfigSection sectionRejecting(String problem) {
    return McpConfigSection.of(SECTION, Set.of("field", "entity"), Merge.REPLACE,
        body -> problem == null ? Collections.emptyList() : List.of(problem));
  }

  @BeforeEach
  void clean() {
    McpEntityConfig.clearRegistrations();
  }

  @AfterEach
  void tearDown() {
    McpEntityConfig.clearRegistrations();
  }

  @Nested
  @DisplayName("section registration")
  class Registration {

    @Test
    @DisplayName("a section needs a non-blank name")
    void blankNameRejected() {
      assertThrows(IllegalArgumentException.class,
          () -> McpConfigSection.of("  ", Set.of(), Merge.REPLACE, body -> List.of()));
    }

    @Test
    @DisplayName("merge and validator are required")
    void nullArgumentsRejected() {
      assertThrows(IllegalArgumentException.class,
          () -> McpConfigSection.of(SECTION, Set.of(), null, body -> List.of()));
      assertThrows(IllegalArgumentException.class,
          () -> McpConfigSection.of(SECTION, Set.of(), Merge.REPLACE, null));
    }

    @Test
    @DisplayName("registering the same instance twice is a no-op")
    void idempotentForSameInstance() {
      McpConfigSection section = sectionRejecting(null);
      McpEntityConfig.register(section);
      McpEntityConfig.register(section);
    }

    @Test
    @DisplayName("two different sections cannot share a name")
    void duplicateNameRejected() {
      McpEntityConfig.register(sectionRejecting(null));
      assertThrows(IllegalStateException.class,
          () -> McpEntityConfig.register(sectionRejecting("other")));
    }

    @Test
    @DisplayName("REPLACE is what the parent section needs, and allowed keys are exposed as declared")
    void declarationIsReadable() {
      McpConfigSection section = sectionRejecting(null);
      assertEquals(Merge.REPLACE, section.getMerge());
      assertTrue(section.getAllowedKeys().contains("field"));
      assertFalse(section.getAllowedKeys().contains("parentField"));
    }

    @Test
    @DisplayName("allowed keys cannot be mutated by a caller")
    void allowedKeysAreImmutable() {
      Set<String> keys = sectionRejecting(null).getAllowedKeys();
      assertThrows(UnsupportedOperationException.class, () -> keys.add("smuggled"));
    }
  }

  @Nested
  @DisplayName("section validation")
  class Validation {

    @Test
    @DisplayName("a body the section accepts yields no problems")
    void acceptedBody() {
      assertTrue(sectionRejecting(null).validate(new JSONObject()).isEmpty());
    }

    @Test
    @DisplayName("the section's own problems are reported verbatim")
    void rejectedBody() {
      List<String> problems = sectionRejecting("field 'nope' is not a foreign key")
          .validate(new JSONObject());
      assertEquals(1, problems.size());
      assertTrue(problems.get(0).contains("not a foreign key"));
    }

    @Test
    @DisplayName("a validator that throws is reported, not propagated")
    void throwingValidatorIsContained() {
      McpConfigSection section = McpConfigSection.of(SECTION, Set.of(), Merge.REPLACE, body -> {
        throw new IllegalStateException("boom");
      });
      List<String> problems = section.validate(new JSONObject());
      assertEquals(1, problems.size());
      assertTrue(problems.get(0).contains("boom"),
          "the operator needs the cause, not a generic failure");
    }

    @Test
    @DisplayName("a validator returning null is treated as 'no problems', never an NPE")
    void nullFromValidatorIsEmpty() {
      McpConfigSection section =
          McpConfigSection.of(SECTION, Set.of(), Merge.REPLACE, body -> null);
      assertTrue(section.validate(new JSONObject()).isEmpty());
    }
  }

  @Nested
  @DisplayName("column property name")
  class ColumnProperty {

    @Test
    @DisplayName("matches the generated property on all three levels")
    void matchesGeneratedProperty() {
      // This is the assertion that would have caught the live bug: the column is MCP_Config, but
      // Etendo's generator uppercases the leading acronym, so the DAL property is "mCPConfig" —
      // not the "mcpConfig" this class was originally written against. Reading a nonexistent
      // property is the one failure the resolver cannot report, because an absent property looks
      // exactly like an unconfigured record: every payload was being ignored, silently, which is
      // the degradation the whole loud-failure path exists to prevent.
      assertEquals(SFEntity.PROPERTY_MCPCONFIG, McpEntityConfig.PROPERTY_MCP_CONFIG);
      assertEquals(SFSpec.PROPERTY_MCPCONFIG, McpEntityConfig.PROPERTY_MCP_CONFIG,
          "the three levels must map to one property name — the resolver reads them all the "
              + "same way");
      assertEquals(SFField.PROPERTY_MCPCONFIG, McpEntityConfig.PROPERTY_MCP_CONFIG);
    }

    @Test
    @DisplayName("is not the name a human would have guessed")
    void notTheObviousName() {
      // Pinned deliberately. If a future regeneration makes the property "mcpConfig" after all,
      // this test fails and someone reads the note above instead of rediscovering it the hard way.
      assertNotEquals("mcpConfig", McpEntityConfig.PROPERTY_MCP_CONFIG,
          "if this now passes, the generator changed — update the javadoc, not just the constant");
    }
  }
}
