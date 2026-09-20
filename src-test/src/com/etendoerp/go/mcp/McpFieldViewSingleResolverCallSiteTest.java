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
      new String[] { "com/etendoerp/go/mcp/McpResourceProvider.java", "buildFieldsArray" },
      // IMP-39 added two more, and they are the ones that decide whether a write lands and
      // whether a filter key is accepted. Both read inclusion, which is why isIncluded joined
      // CURATION_PROPERTIES below.
      new String[] { "com/etendoerp/go/mcp/McpQuerySupport.java", "writeGate" },
      new String[] { "com/etendoerp/go/mcp/McpQuerySupport.java", "filterablePropertyNames" });

  /**
   * The readers whose subject is <b>inclusion</b>, guarded a second way.
   *
   * <p>A raw {@code row.isIncluded()} is not the only bypass available to them, and it is not the
   * one they actually had: both of these used to answer the question with a
   * {@code Restrictions.eq(PROPERTY_ISINCLUDED, true)} in their own criteria. A criteria runs in
   * the database and an {@code MCP_CONFIG} {@code fields.included} override lives in a JSON column
   * nobody joins, so the override would have been honoured by {@code neo_schema} and silently
   * ignored by {@code neo_list} and both write verbs — rebuilding the exact three-way disagreement
   * IMP-39 exists to end, with no raw property read anywhere for the check above to catch.</p>
   *
   * <p>{@code activeFields} is listed because it is the shared query the other two draw from: a
   * predicate reintroduced there would bypass both at once.</p>
   *
   * <p>The last two joined after the same bypass was found in the MCP's two <b>advertised</b>
   * surfaces: {@code buildFieldsArray} backs the {@code schemaforge://} resource listing and
   * {@code buildProcessParamSchema} backs a process tool's declared parameter set. Both filtered
   * with a criteria, so a field reclaimed by a {@code fields.included} override was named by
   * {@code neo_schema} and absent from the resource and from the tool's own schema — with no error
   * and no log. {@code buildFieldsArray} is the sharper case, because it was already resolving
   * {@code readOnly} through the view in the same loop: one property honoured the override and the
   * other did not, inside one method.</p>
   */
  private static final List<String[]> INCLUSION_READERS = List.of(
      new String[] { "com/etendoerp/go/mcp/McpQuerySupport.java", "writeGate" },
      new String[] { "com/etendoerp/go/mcp/McpQuerySupport.java", "filterablePropertyNames" },
      new String[] { "com/etendoerp/go/mcp/McpQuerySupport.java", "activeFields" },
      new String[] { "com/etendoerp/go/mcp/McpResourceProvider.java", "buildFieldsArray" },
      new String[] { "com/etendoerp/go/mcp/ToolRegistry.java", "buildProcessParamSchema" });

  /** An {@code ISINCLUDED} predicate pushed into a criteria — the bypass a JSON override survives. */
  private static final Pattern ISINCLUDED_CRITERIA = Pattern.compile("ISINCLUDED");

  /**
   * The one {@code ISINCLUDED} predicate that is <b>correct</b> in a criteria: the entity-level
   * one.
   *
   * <p>{@code MCP_CONFIG} overrides the inclusion of <b>fields</b> and nothing else —
   * {@link McpFieldsSection#included} is the only inclusion key, {@link McpConfigSections}
   * registers exactly two sections ({@code parent} and {@code fields}), and there is no
   * {@code McpEntityView} for a resolver to route an entity through. So
   * {@code SFEntity.PROPERTY_ISINCLUDED} in a criteria cannot be out of step with an override:
   * there is no override for it to be out of step with. {@code buildProcessParamSchema} carries
   * one and must keep it.</p>
   *
   * <p><b>What exempting it costs.</b> The check below subtracts this shape and then scans for
   * {@code ISINCLUDED} in what is left, so it still catches an unqualified
   * {@code PROPERTY_ISINCLUDED} (static import), a bare {@code "ISINCLUDED"} column literal in
   * hand-written SQL, and the constant reached through any other type name. It does <b>not</b>
   * catch a field-inclusion predicate spelled in a way that never contains the uppercase token —
   * a lowercase HQL property literal ({@code Restrictions.eq("isIncluded", true)}) or a name built
   * by concatenation. That gap predates the exemption: the original pattern is case-sensitive
   * precisely so the legitimate {@code McpFieldView.of(field).isIncluded()} call is not flagged,
   * and no case-insensitive pattern can tell those two apart. The day it matters, add a shape,
   * do not widen the case.</p>
   */
  private static final Pattern ENTITY_ISINCLUDED =
      Pattern.compile("SFEntity\\s*\\.\\s*PROPERTY_ISINCLUDED");

  /**
   * Whether {@code body} pushes a <b>field</b>-inclusion predicate into a criteria.
   *
   * <p>Subtractive rather than narrow on purpose. Matching only
   * {@code SFField\.PROPERTY_ISINCLUDED} would be the obvious way to spare the entity restriction,
   * and it would also spare every other spelling of the same bypass — this keeps the broad token
   * scan and removes the one shape that is known-good.</p>
   *
   * @param body the method body, comments already stripped
   * @return {@code true} when an {@code ISINCLUDED} token survives the entity exemption
   */
  private static boolean restrictsOnFieldInclusion(String body) {
    String withoutEntityCheck = ENTITY_ISINCLUDED.matcher(body).replaceAll(" ");
    return ISINCLUDED_CRITERIA.matcher(withoutEntityCheck).find();
  }

  /** The one resolver every reader must go through. */
  private static final Pattern RESOLVER_CALL =
      Pattern.compile("McpFieldView\\s*\\.\\s*of\\s*\\(");

  /**
   * The curated properties. {@code isIncluded} joined them with IMP-39: the override may now
   * reclaim a field the shared curation excluded, or exclude one it exposes, so inclusion stopped
   * being a property of the row alone. {@link McpFieldView} exposes these under the same names
   * as {@code SFField} does, so the method name cannot tell a raw row read from a resolved-view
   * read — only the <b>receiver's declared type</b> can.
   */
  private static final String CURATION_PROPERTIES =
      "getVisibility|isReadOnly|isBusinessCritical|isIncluded";

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

  /**
   * Restore the real section registry around every test.
   *
   * <p>Other test classes in this package clear {@code McpEntityConfig}'s registrations without
   * resetting the bootstrap flag, which would leave {@code ensureRegistered()} a no-op and make
   * the real {@code fields} section read as unknown. Resetting both the flag and the parse cache
   * is needed <b>before</b> each test, so this class always resolves against the real sections,
   * and <b>after</b> each test — including the last — so this class does not leave its own
   * registry behind for whatever runs next.
   *
   * <p><b>One method carrying both annotations, deliberately.</b> The two callbacks need the
   * identical body, and as two methods that is Sonar S4144 (identical implementations) with
   * nothing to extract. JUnit discovers before-each and after-each with independent
   * {@code findAnnotatedMethods} scans and validates only that a lifecycle method is
   * {@code void}, non-{@code private} and non-{@code static}
   * ({@code LifecycleMethodUtils}), so a method annotated with both appears in both lists and is
   * invoked as both — same isolation, no duplication. Please do not split it back into two.
   */
  @BeforeEach
  @AfterEach
  void isolateSectionRegistry() {
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
    assertEquals(6, READERS.size(),
        "ETP-5184 unified four readers and IMP-39 added two more (writeGate,"
            + " filterablePropertyNames)");
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

    // IMP-39 reversed this case, and the reversal is the point. isIncluded used to be read off
    // the row legitimately — "deliberately not overridable" is what this assertion said — and now
    // fields.included can reclaim an excluded field or exclude an exposed one for the MCP alone.
    // A reader still reading the column decides a write and a filter on the pre-override answer
    // while neo_schema reports the override, which is the disagreement, not a style nit.
    String included = "{ for (SFField row : crit.list()) { boolean i = row.isIncluded(); } }";
    assertFalse(rawReadViolations("synthetic", included).isEmpty(),
        "since IMP-39 fields.included is overridable, so a raw row read of it must be flagged");

    // And the resolved-view read of the same property must not be.
    String viaView = "{ for (SFField row : crit.list()) {"
        + " boolean i = McpFieldView.of(row).isIncluded(); } }";
    assertTrue(rawReadViolations("synthetic", viaView).isEmpty(),
        "reading inclusion through the resolver is the correct shape");
  }

  /**
   * The second bypass, and the one the two IMP-39 readers actually had: answering "is this field
   * included" with a {@code Restrictions.eq} instead of a property read.
   *
   * <p>Nothing in the raw-read check above can see it — a criteria contains no property read at
   * all — and it is strictly worse than the raw read, because it is resolved in the database where
   * the {@code MCP_CONFIG} JSON is not joined. {@code excludedPropertyNames} and
   * {@code filterablePropertyNames} both queried {@code ISINCLUDED = 'Y'} before the fix, so an
   * override would have moved {@code neo_schema} and left {@code neo_list}, {@code neo_create} and
   * {@code neo_update} on the old answer.</p>
   */
  @Test
  @DisplayName("no inclusion reader pushes ISINCLUDED into a criteria, where an override cannot "
      + "reach it")
  void noInclusionReaderQueriesTheColumn() {
    assertEquals(5, INCLUSION_READERS.size(),
        "IMP-39 guarded three readers and the resource/tool surfaces added two more"
            + " (buildFieldsArray, buildProcessParamSchema)");
    List<String> violations = new ArrayList<>();
    for (String[] reader : INCLUSION_READERS) {
      String method = reader[1];
      String body = McpSourceScanner.methodBody(McpSourceScanner.read(reader[0]), method);
      assertTrue(body.length() > 40,
          method + " resolved to a " + body.length() + "-char body — the scanner matched the"
              + " wrong thing. Fix this test, not the source");
      if (restrictsOnFieldInclusion(body)) {
        violations.add(method + " restricts on ISINCLUDED in its own criteria");
      }
    }
    assertTrue(violations.isEmpty(),
        "An ISINCLUDED predicate is evaluated in the database, where the MCP_CONFIG"
            + " fields.included override does not exist: " + violations
            + ". Resolve inclusion with McpFieldView.of(sfField).isIncluded() over the entity's"
            + " active rows instead, as writeGate does.");
  }

  /**
   * Prove the criteria check fires, so it is not another guard that passes because it looks at
   * nothing — and, since the check now carries an exemption, that the exemption is not a hole the
   * regression fits through.
   */
  @Test
  @DisplayName("the criteria check flags a field ISINCLUDED restriction and spares the entity one")
  void theCriteriaCheckIsNotMute() {
    assertTrue(restrictsOnFieldInclusion(
        "{ crit.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true)); }"),
        "a field-inclusion predicate is the bypass this guard exists for");
    assertFalse(restrictsOnFieldInclusion(
        "{ crit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true)); }"),
        "ISACTIVE is not overridable by MCP_CONFIG and belongs in a criteria");

    // The exemption: entity inclusion carries no override, so restricting on it is correct.
    assertFalse(restrictsOnFieldInclusion(
        "{ crit.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true)); }"),
        "MCP_CONFIG overrides field inclusion only, so the entity restriction must not be flagged");

    // And the exemption must not swallow a field predicate standing beside it — which is exactly
    // buildProcessParamSchema's shape, and the case a naive substring pattern got wrong in the
    // other direction.
    assertTrue(restrictsOnFieldInclusion(
        "{ entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));"
            + " fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true)); }"),
        "an exempt entity restriction must not hide a field restriction in the same body");

    // Still broad where it can afford to be: a hand-written column literal is not spelled through
    // SFField at all, and is the same bypass.
    assertTrue(restrictsOnFieldInclusion("{ String sql = \"AND ISINCLUDED = 'Y'\"; }"),
        "the token scan must survive the exemption for spellings that never name SFField");
  }

  /**
   * The behavioural half of IMP-39 at the {@link McpFieldView} seam: an {@code included} override
   * moves in both directions, and the row still decides when the override is silent.
   *
   * <p>Both directions matter and for different reasons. Reclaiming an excluded field is what
   * makes the override useful; <b>excluding an exposed one is what makes the write gate's answer
   * depend on it</b> — {@code writeGate} puts exactly this field in {@code excluded} and
   * {@code neo_create} refuses it with {@code field_not_allowed}. Every fixture has the row and
   * the override disagree, so a passing assertion can only be reading the override; the third is
   * the control that proves the row is still read at all.</p>
   */
  @Test
  @DisplayName("an included override is honoured in both directions, and the row stands when it "
      + "is silent")
  void anIncludedOverrideIsHonoured() {
    // Row excludes, override reclaims: a true answer can only have come from the override.
    assertTrue(McpFieldView.of(inclusionField(Boolean.FALSE, Boolean.TRUE)).isIncluded());

    // Row exposes, override excludes — the direction the write gate turns into a refusal.
    assertFalse(McpFieldView.of(inclusionField(Boolean.TRUE, Boolean.FALSE)).isIncluded());

    // Control: override silent, so the row's own value must come through, both ways.
    assertTrue(McpFieldView.of(inclusionField(Boolean.TRUE, null)).isIncluded());
    assertFalse(McpFieldView.of(inclusionField(Boolean.FALSE, null)).isIncluded());
  }

  /**
   * An excluded field is not editable however the exclusion was decided — the two axes stay
   * wired together, which is what keeps {@code neo_selectors} from offering a field the write
   * gate refuses.
   */
  @Test
  @DisplayName("an excluded field is never editable, override or row")
  void anExcludedFieldIsNeverEditable() {
    assertFalse(McpFieldView.of(inclusionField(Boolean.TRUE, Boolean.FALSE)).isEditable(),
        "the override excluded it, so nothing may be sent for it");
    assertFalse(McpFieldView.of(inclusionField(Boolean.FALSE, null)).isEditable(),
        "the row excluded it and no override says otherwise");
    assertTrue(McpFieldView.of(inclusionField(Boolean.FALSE, Boolean.TRUE)).isEditable(),
        "reclaimed by the override, not read-only, and carrying no narrowing visibility");
  }

  /**
   * A field whose inclusion is decided on the row, on the override, or on both.
   *
   * @param rowIncluded      the {@code ISINCLUDED} column on the row itself
   * @param overrideIncluded the value the override declares, or {@code null} to leave the key out
   *                         — which is not the same as a declared {@code false}
   */
  private SFField inclusionField(Boolean rowIncluded, Boolean overrideIncluded) {
    String id = "incl-" + rowIncluded + "-" + overrideIncluded;
    SFField field = mock(SFField.class);
    when(field.getId()).thenReturn("call-site-field-" + id);
    when(field.getVisibility()).thenReturn(null);
    when(field.isIncluded()).thenReturn(rowIncluded);
    when(field.isReadOnly()).thenReturn(Boolean.FALSE);
    when(field.isBusinessCritical()).thenReturn(Boolean.FALSE);

    StringBuilder keys = new StringBuilder();
    if (overrideIncluded != null) {
      keys.append("\"included\":").append(overrideIncluded).append(',');
    }
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("call-site-entity-" + id);
    when(entity.get(SFEntity.PROPERTY_MCPCONFIG)).thenReturn(
        "{\"fields\":{" + keys + "\"reason\":\"call-site inclusion fixture\"}}");
    when(field.getETGOSFEntity()).thenReturn(entity);
    return field;
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
    // summaryFields' one: this fixture's override says nothing about businessCritical and the row
    // says false, so the view must not invent a true. The override's own effect on it is
    // aBusinessCriticalOverrideIsHonoured below, where row and override deliberately disagree.
    assertFalse(view.isBusinessCritical());

    // And the same field narrowed: no reader can be left on the pre-override answer.
    McpFieldView demoted = McpFieldView.of(overriddenField("system"));
    assertEquals("system", demoted.getVisibility());
    assertFalse(demoted.isEditable());
    assertFalse(demoted.isReadOnly(), "the override moved visibility only, so readOnly stands");
  }

  /**
   * {@code businessCritical} is overridable, and {@code summaryFields} is the reader that now
   * consumes it — {@code view:"summary"} on {@code neo_list}/{@code neo_get}. That reader has no
   * behavioural test of its own (it needs a {@code ModelProvider} entity and a live DAL), so the
   * capability it gained is pinned here, at the seam it gained it through.
   *
   * <p>Every fixture below has the row and the override <b>disagree</b>, which is what makes the
   * assertions about the override rather than about the fixture: a row and an override that agree
   * would pass whichever of the two the resolver actually read. The third case is the control —
   * with the override silent, the row's own value must come through, so the first two cannot be
   * passing merely because the resolver ignores the row entirely.</p>
   */
  @Test
  @DisplayName("a businessCritical override is honoured in both directions, and the row still "
      + "stands when the override is silent")
  void aBusinessCriticalOverrideIsHonoured() {
    // Row says false, override says true: a true answer can only have come from the override.
    assertTrue(McpFieldView.of(overriddenField("editable", Boolean.FALSE, Boolean.TRUE))
        .isBusinessCritical());

    // The other direction, which nothing covered: the override demotes a row that says true.
    assertFalse(McpFieldView.of(overriddenField("editable", Boolean.TRUE, Boolean.FALSE))
        .isBusinessCritical());

    // Control: override silent, row true — the row is genuinely being read.
    assertTrue(McpFieldView.of(overriddenField("editable", Boolean.TRUE, null))
        .isBusinessCritical());

    // And the override must not disturb the properties the other three readers consume.
    McpFieldView promoted = McpFieldView.of(overriddenField("editable", Boolean.FALSE,
        Boolean.TRUE));
    assertEquals("editable", promoted.getVisibility());
    assertFalse(promoted.isReadOnly());
    assertTrue(promoted.isEditable());
  }

  /**
   * A field whose {@code VISIBILITY} column is {@code NULL} — the bp-location shape — with an
   * entity-level {@code fields} override reclassifying it.
   */
  private SFField overriddenField(String visibility) {
    return overriddenField(visibility, Boolean.FALSE, null);
  }

  /**
   * The same shape, with {@code businessCritical} controllable on both sides of the override.
   *
   * @param visibility               the visibility the override declares
   * @param rowBusinessCritical      the {@code ISBUSINESSCRITICAL} column on the row itself
   * @param overrideBusinessCritical the value the override declares, or {@code null} to leave the
   *                                 key out — which is not the same as a declared {@code false}
   */
  private SFField overriddenField(String visibility, Boolean rowBusinessCritical,
      Boolean overrideBusinessCritical) {
    // The id doubles as the McpConfigCache key, so it has to vary with the payload.
    String id = visibility + "-" + rowBusinessCritical + "-" + overrideBusinessCritical;
    SFField field = mock(SFField.class);
    when(field.getId()).thenReturn("call-site-field-" + id);
    when(field.getVisibility()).thenReturn(null);
    when(field.isIncluded()).thenReturn(Boolean.TRUE);
    when(field.isReadOnly()).thenReturn(Boolean.FALSE);
    when(field.isBusinessCritical()).thenReturn(rowBusinessCritical);

    StringBuilder keys = new StringBuilder("\"visibility\":\"" + visibility + "\",");
    if (overrideBusinessCritical != null) {
      keys.append("\"businessCritical\":").append(overrideBusinessCritical).append(',');
    }
    SFEntity entity = mock(SFEntity.class);
    when(entity.getId()).thenReturn("call-site-entity-" + id);
    when(entity.get(SFEntity.PROPERTY_MCPCONFIG)).thenReturn(
        "{\"fields\":{" + keys + "\"reason\":\"call-site fixture\"}}");
    when(field.getETGOSFEntity()).thenReturn(entity);
    return field;
  }
}
