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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
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
   * The three curated properties. {@link McpFieldView} exposes these under exactly the same names
   * as {@code SFField} does, so the method name cannot tell a raw row read from a resolved-view
   * read — only the <b>receiver's declared type</b> can.
   */
  private static final String CURATION_PROPERTIES = "getVisibility|isReadOnly|isBusinessCritical";

  /**
   * Every variable of type {@code SFField} declared inside a body — the enhanced-for loop
   * variable, a plain local, or a parameter. {@code List<SFField>} and
   * {@code OBCriteria<SFField>} are not matched: the type name there is followed by {@code >},
   * not by whitespace and an identifier.
   */
  private static final Pattern SF_FIELD_VARIABLE =
      Pattern.compile("\\bSFField\\s+(\\w+)\\s*[:=;)]");

  /**
   * Locate the {@code SFField}-typed variables a body reads from.
   *
   * <p>Type-driven rather than name-driven, and that is the whole point. The previous version of
   * this guard hardcoded the receiver names {@code sfField|field}, so a reader refactored to
   * {@code for (SFField row : crit.list())} reading {@code row.getVisibility()} passed both
   * assertions — it still called {@code McpFieldView.of(} for some other property and still
   * contained the string {@code "SFField"}. The guard read as covered while being blind to the
   * exact regression it exists to catch, which is worse than no guard, because the next reader
   * trusts it (REVIEW W6).</p>
   *
   * @param body the method body, comments already stripped
   * @return the declared names; empty means the scan found nothing to check, which is a failure
   */
  private static Set<String> sfFieldVariables(String body) {
    Set<String> names = new LinkedHashSet<>();
    Matcher matcher = SF_FIELD_VARIABLE.matcher(body);
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names;
  }

  /**
   * A curation property read straight off an {@code SFField} row — the shape all four readers
   * used before ETP-5184, and the shape a regression would take.
   *
   * <p>The receiver must be the variable itself: {@code row.getVisibility()} matches, while
   * {@code McpFieldView.of(row).getVisibility()} does not, because there the token before the dot
   * is {@code )}. That is what keeps the legitimate resolved-view chain — and any local holding a
   * resolved view, whatever it is named — out of the match, without the guard having to know any
   * name on the correct side.</p>
   */
  private static Pattern rawReadOf(String variable) {
    return Pattern.compile("\\b" + Pattern.quote(variable) + "\\s*\\.\\s*("
        + CURATION_PROPERTIES + ")\\s*\\(");
  }

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
      violations.addAll(rawReadViolations(method, body));
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
      assertFalse(sfFieldVariables(body).isEmpty(),
          method + " declares no SFField-typed variable, so the raw-read check above has nothing"
              + " to look for and passes vacuously — the reader was probably refactored"
              + " elsewhere. Fix this test, not the source");
    }
  }

  /**
   * Prove the raw-read check fires. A guard nobody has seen fail is a guard nobody knows works,
   * and this one has already been blind once: the name-coupled version accepted a reader that
   * read straight off a loop variable called anything other than {@code sfField}/{@code field}.
   */
  @Test
  @DisplayName("the raw-read check flags a row read under any receiver name, and spares the "
      + "resolved-view chain")
  void theRawReadCheckIsNotMute() {
    // The regression, under a receiver name the old regex did not know about. It also calls the
    // resolver for another property, so the McpFieldView.of() assertion alone would pass it.
    String regressed = "{ for (SFField row : crit.list()) {"
        + " String v = row.getVisibility();"
        + " boolean ro = McpFieldView.of(row).isReadOnly(); } }";
    assertEquals(Set.of("row"), sfFieldVariables(regressed));
    assertFalse(rawReadViolations("synthetic", regressed).isEmpty(),
        "a raw row read must be flagged whatever the loop variable is called");

    // The correct shape, with the view held in a local named nothing the guard knows.
    String correct = "{ for (SFField anySfFieldName : crit.list()) {"
        + " McpFieldView resolved = McpFieldView.of(anySfFieldName);"
        + " String v = resolved.getVisibility();"
        + " boolean bc = resolved.isBusinessCritical();"
        + " boolean ro = McpFieldView.of(anySfFieldName).isReadOnly();"
        + " Column col = anySfFieldName.getADColumn(); } }";
    assertTrue(rawReadViolations("synthetic", correct).isEmpty(),
        "reading through a resolved view — held in a local or chained — is the correct shape and"
            + " must never be flagged, whatever the variables are called");

    // A property that is not curated is not this guard's business either.
    String included = "{ for (SFField row : crit.list()) { boolean i = row.isIncluded(); } }";
    assertTrue(rawReadViolations("synthetic", included).isEmpty(),
        "isIncluded is deliberately not overridable, so reading it off the row is correct");
  }

  /**
   * Every curation property read straight off an {@code SFField}-typed variable in {@code body}.
   *
   * <p>An empty result from a body with no {@code SFField} variable at all is reported as a
   * violation rather than as cleanliness: it means the scan lost track of the receiver, and a
   * check that cannot find its subject must fail loudly instead of passing.</p>
   */
  private static List<String> rawReadViolations(String method, String body) {
    Set<String> variables = sfFieldVariables(body);
    if (variables.isEmpty()) {
      return List.of(method + " declares no SFField-typed variable — the raw-read check cannot"
          + " find its subject, so it would pass vacuously");
    }
    List<String> violations = new ArrayList<>();
    for (String variable : variables) {
      if (rawReadOf(variable).matcher(body).find()) {
        violations.add(method + " reads a curation property straight off the SFField row '"
            + variable + "'");
      }
    }
    return violations;
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
