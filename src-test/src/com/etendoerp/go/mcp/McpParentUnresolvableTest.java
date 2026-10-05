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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * ETP-5558 BUG-1 — a child write whose {@code parentId} cannot be mapped is refused, not written.
 *
 * <p><b>The defect.</b> {@code etendo_create(spec:"payment-out", entity:"lines", parentId:<payment>)}
 * targets {@code FIN_Payment_ScheduleDetail}, whose parent-link columns point at
 * {@code FIN_Payment_Detail} and {@code FIN_Payment_Schedule} — never at {@code FIN_Payment}, the
 * table of its parent tab. {@link McpParentScope} correctly answers {@code UNRESOLVABLE}, but
 * {@code resolveParentFK} only logged a WARN and returned, so the create carried on without its
 * parent and the mandatory-defaults pass filled {@code paymentDetails} on its own: the new row
 * ended up attached to an unrelated, already processed customer collection. Its javadoc said "the
 * caller's gate is what refuses such an entity" — there was no such gate.</p>
 *
 * <p>The refusal is asserted on the envelope an agent reads, because the point is not only to stop
 * the write but to say why: a bare "bad request" would leave the caller retrying the same call.</p>
 */
@DisplayName("ETP-5558 BUG-1 — an unmappable parentId is refused (parent_unresolvable)")
class McpParentUnresolvableTest {

  private static final String SPEC_NAME = "payment-out";
  private static final String ENTITY_NAME = "lines";
  private static final String PARENT_ID = "PAYMENT-1";
  private static final String PARENT_FIELD = "invoice";
  private static final String PARENT_ENTITY = "header";
  private static final String PROBLEM = "cannot determine the parent of tab 'Lines': none of its "
      + "parent-link fields [paymentDetails, invoicePaymentSchedule] points at the parent tab "
      + "table 'FIN_Payment'";

  private MockedStatic<McpParentScope> parentScopeMock;
  private Logger log;
  private Tab childTab;
  private SFEntity sfEntity;

  @BeforeEach
  void setUp() {
    parentScopeMock = mockStatic(McpParentScope.class);
    log = mock(Logger.class);
    childTab = mock(Tab.class);
    when(childTab.getTabLevel()).thenReturn(1L);
    when(childTab.getName()).thenReturn("Lines");
    SFSpec spec = mock(SFSpec.class);
    when(spec.getName()).thenReturn(SPEC_NAME);
    sfEntity = mock(SFEntity.class);
    when(sfEntity.getName()).thenReturn(ENTITY_NAME);
    when(sfEntity.getETGOSFSpec()).thenReturn(spec);
  }

  @AfterEach
  void tearDown() {
    parentScopeMock.close();
  }

  /** A real {@link McpParentScope.Scope}, built through its private constructor. */
  private static McpParentScope.Scope scope(McpParentScope.Kind kind, String parentField,
      String problem) throws Exception {
    return scope(kind, parentField, PARENT_ENTITY, problem);
  }

  private static McpParentScope.Scope scope(McpParentScope.Kind kind, String parentField,
      String parentEntity, String problem) throws Exception {
    Constructor<McpParentScope.Scope> ctor = McpParentScope.Scope.class.getDeclaredConstructor(
        McpParentScope.Kind.class, String.class, String.class, Set.class, String.class,
        String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(kind, parentField, parentEntity, Set.of(), null, problem);
  }

  private void withScope(McpParentScope.Scope resolved) {
    parentScopeMock.when(() -> McpParentScope.forEntity(any())).thenReturn(resolved);
  }

  // ── the repro ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("etendo_create's parent mapping refuses an unresolvable scope instead of writing on")
  void resolveParentFkRefusesUnresolvable() throws Exception {
    withScope(scope(McpParentScope.Kind.UNRESOLVABLE, null, PROBLEM));
    JSONObject body = new JSONObject();

    McpRoutingException refusal = assertThrows(McpRoutingException.class,
        () -> McpWriteRequestSupport.resolveParentFK(childTab, body, PARENT_ID, log, sfEntity),
        "a parentId that cannot be applied must stop the write — logging and carrying on is what "
            + "attached a new payment line to an unrelated processed collection");
    assertEquals(0, body.length(), "nothing may be written into the body on a refusal");

    JSONObject envelope = refusal.toEnvelope();
    assertEquals(McpConstants.STATUS_UNPROCESSABLE, envelope.getInt(McpConstants.KEY_STATUS));
    assertEquals("parent_unresolvable", envelope.getString(McpConstants.KEY_ERROR));
    assertEquals(McpConstants.PARAM_PARENT_ID, envelope.getString(McpConstants.PARAM_FIELD));
    String detail = envelope.getString(McpConstants.KEY_DETAIL);
    assertTrue(detail.contains(SPEC_NAME) && detail.contains(ENTITY_NAME),
        "the refusal must name the spec and entity: " + detail);
    assertTrue(detail.contains(PROBLEM), "the refusal must carry the reason: " + detail);
    assertTrue(envelope.has(McpConstants.KEY_HINT), "a refusal without a next step is a dead end");
  }

  // ── what must keep working ────────────────────────────────────────────

  @Test
  @DisplayName("a resolvable child still gets its parent field (sales-invoice lines shape)")
  void resolvedChildIsUnchanged() throws Exception {
    withScope(scope(McpParentScope.Kind.RESOLVED, PARENT_FIELD, null));
    JSONObject body = new JSONObject();

    McpWriteRequestSupport.resolveParentFK(childTab, body, PARENT_ID, log, sfEntity);

    assertEquals(PARENT_ID, body.getString(PARENT_FIELD));
  }

  @Test
  @DisplayName("a header entity ignores parentId without consulting the scope")
  void headerIsUnchanged() throws Exception {
    withScope(scope(McpParentScope.Kind.UNRESOLVABLE, null, PROBLEM));
    when(childTab.getTabLevel()).thenReturn(0L);
    JSONObject body = new JSONObject();

    assertDoesNotThrow(
        () -> McpWriteRequestSupport.resolveParentFK(childTab, body, PARENT_ID, log, sfEntity));
    assertEquals(0, body.length());
  }

  @Test
  @DisplayName("a same-record tab (contacts/customer shape) is not refused")
  void sameRecordIsNotRefused() throws Exception {
    withScope(scope(McpParentScope.Kind.SAME_RECORD, null, null));

    assertDoesNotThrow(() -> McpWriteRequestSupport.requireApplicableParent(sfEntity, PARENT_ID));
  }

  @ParameterizedTest
  @EnumSource(value = McpParentScope.Kind.class, names = { "UNRESOLVABLE", "UNPARENTED" })
  @DisplayName("every scope with no parent field to write the id into is refused")
  void everyUnmappableKindIsRefused(McpParentScope.Kind kind) throws Exception {
    withScope(scope(kind, null, kind == McpParentScope.Kind.UNRESOLVABLE ? PROBLEM : null));

    McpRoutingException refusal = assertThrows(McpRoutingException.class,
        () -> McpWriteRequestSupport.requireApplicableParent(sfEntity, PARENT_ID));
    assertEquals("parent_unresolvable", refusal.toEnvelope().getString(McpConstants.KEY_ERROR));
  }

  /**
   * WARN-1 of the review: the corruption does not need a parentId. Without one the create reaches
   * the mandatory-defaults pass all the same, which fills the unmappable link on its own. An
   * unresolvable scope therefore refuses every create, whatever the caller sent.
   */
  @Test
  @DisplayName("an unresolvable scope refuses the create even when no parentId is sent")
  void unresolvableIsRefusedWithoutParentId() throws Exception {
    withScope(scope(McpParentScope.Kind.UNRESOLVABLE, null, PROBLEM));

    for (String absent : new String[] { null, "", "  " }) {
      McpRoutingException refusal = assertThrows(McpRoutingException.class,
          () -> McpWriteRequestSupport.requireApplicableParent(sfEntity, absent),
          "omitting parentId must not be a way around the refusal");
      JSONObject envelope = refusal.toEnvelope();
      assertEquals("parent_unresolvable", envelope.getString(McpConstants.KEY_ERROR));
      assertTrue(envelope.getString(McpConstants.KEY_DETAIL).contains("Nothing was written"));
    }
  }

  @ParameterizedTest
  @EnumSource(value = McpParentScope.Kind.class, names = { "UNRESOLVABLE" }, mode =
      EnumSource.Mode.EXCLUDE)
  @DisplayName("without parentId, every other scope kind is left alone")
  void blankParentIdIsOnlyJudgedForUnresolvable(McpParentScope.Kind kind) throws Exception {
    withScope(scope(kind, kind == McpParentScope.Kind.RESOLVED ? PARENT_FIELD : null, null));

    assertDoesNotThrow(() -> McpWriteRequestSupport.requireApplicableParent(sfEntity, null));
  }

  @Test
  @DisplayName("with no reason from the scope, the refusal still names the spec and entity")
  void refusalWithoutProblemTextStillNamesTheEntity() throws Exception {
    withScope(scope(McpParentScope.Kind.UNPARENTED, null, null));

    McpRoutingException refusal = assertThrows(McpRoutingException.class,
        () -> McpWriteRequestSupport.requireApplicableParent(sfEntity, PARENT_ID));
    String detail = refusal.toEnvelope().getString(McpConstants.KEY_DETAIL);
    assertTrue(detail.contains(ENTITY_NAME) && detail.contains(SPEC_NAME), detail);
    assertFalse(detail.contains("null"), "a missing reason must not print as 'null': " + detail);
  }

  /**
   * WARN-2 of the review: the hint must not invite the agent to fill the link field by hand. On
   * these entities that field points at an intermediate record (a payment detail, a schedule) the
   * agent has no safe way to pick, so the invitation leads straight back to a wrong parent.
   */
  @Test
  @DisplayName("the hint points to the action that creates the record, never to a hand-set link")
  void hintDoesNotInviteAHandSetLink() throws Exception {
    withScope(scope(McpParentScope.Kind.UNRESOLVABLE, null, PROBLEM));

    McpRoutingException refusal = assertThrows(McpRoutingException.class,
        () -> McpWriteRequestSupport.requireApplicableParent(sfEntity, PARENT_ID));
    String hint = refusal.toEnvelope().getString(McpConstants.KEY_HINT);
    assertFalse(hint.contains("fields"), "the hint must not suggest setting the link: " + hint);
    assertTrue(hint.contains("action"), "the hint must name the way forward: " + hint);
  }

  @Test
  @DisplayName("the hint names the real parent entity, so the next call is direct")
  void hintNamesTheParentEntity() throws Exception {
    withScope(scope(McpParentScope.Kind.UNRESOLVABLE, null, PROBLEM));

    McpRoutingException refusal = assertThrows(McpRoutingException.class,
        () -> McpWriteRequestSupport.requireApplicableParent(sfEntity, null));
    String hint = refusal.toEnvelope().getString(McpConstants.KEY_HINT);
    assertTrue(hint.contains("etendo_schema(spec:'" + SPEC_NAME + "', entity:'" + PARENT_ENTITY
        + "', view:'actions')"), "the hint must be a call the agent can make as is: " + hint);
    assertFalse(hint.contains("<"), "no placeholder may reach the agent: " + hint);
  }

  @Test
  @DisplayName("with no parent entity known, the hint sends the agent to etendo_discover instead")
  void hintFallsBackWithoutParentEntity() throws Exception {
    withScope(scope(McpParentScope.Kind.UNRESOLVABLE, null, null, PROBLEM));

    McpRoutingException refusal = assertThrows(McpRoutingException.class,
        () -> McpWriteRequestSupport.requireApplicableParent(sfEntity, null));
    String hint = refusal.toEnvelope().getString(McpConstants.KEY_HINT);
    assertTrue(hint.contains("etendo_discover"), hint);
    assertFalse(hint.contains("<") || hint.contains("null"), "no placeholder or null: " + hint);
  }

  // ── etendo_create applies the gate before the defaults ───────────────────

  /**
   * NIT-3 of the review: the refusal is only worth anything if it runs before
   * {@code injectMandatoryDefaults}, the step that fills the link on its own. Called outside the
   * {@code parentId} branch, so a create without one is judged too.
   */
  @Test
  @DisplayName("etendo_create runs the parent gate before injectMandatoryDefaults, for every create")
  void createPathAppliesTheGateBeforeDefaults() {
    String body = McpSourceScanner.methodBody(
        McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleCreate");

    Matcher gate = Pattern.compile("McpWriteRequestSupport\\s*\\.\\s*requireApplicableParent"
        + "\\s*\\(\\s*sfEntity\\s*,").matcher(body);
    assertTrue(gate.find(), "handleCreate must call "
        + "McpWriteRequestSupport.requireApplicableParent(sfEntity, ...) unconditionally — inside "
        + "resolveParentFK alone it only runs when a parentId was sent");
    Matcher defaults = Pattern.compile("injectMandatoryDefaults\\s*\\(").matcher(body);
    assertTrue(defaults.find(), "anchor injectMandatoryDefaults moved; update this guard");
    assertTrue(gate.start() < defaults.start(),
        "the parent gate must run before injectMandatoryDefaults fills the link on its own");
    Matcher parentBranch = Pattern.compile("if\\s*\\(\\s*filteredBody\\s*\\.\\s*has\\s*"
        + "\\(\\s*McpConstants\\s*\\.\\s*PARAM_PARENT_ID").matcher(body);
    assertTrue(parentBranch.find(), "anchor 'if (filteredBody.has(PARAM_PARENT_ID))' moved");
    assertTrue(gate.start() < parentBranch.start(),
        "the gate must not live only inside the parentId branch");
  }

  // ── etendo_batch applies the same gate ───────────────────────────────────

  /**
   * {@code etendo_batch} never reaches {@code resolveParentFK}: it hands the body to
   * {@code BatchService}, whose shared create path maps the parent on its own. The MCP gate must
   * therefore be called from the batch preprocessor too, before the operation is written — the
   * method is private and needs a live DAL, so the call site is pinned by source, the same way
   * {@code McpBillToInjectorCallSiteTest} pins its injector.
   */
  @Test
  @DisplayName("etendo_batch refuses an unmappable parent before the curation gates run")
  void batchPreprocessorAppliesTheGate() {
    String body = McpSourceScanner.methodBody(
        McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"),
        "preprocessBatchOperation");

    Matcher gate = Pattern.compile("requireApplicableParent\\s*\\(\\s*sfEntity\\s*,\\s*op\\s*\\."
        + "\\s*parentId\\s*\\(\\s*\\)\\s*\\)").matcher(body);
    assertTrue(gate.find(), "preprocessBatchOperation must call "
        + "McpWriteRequestSupport.requireApplicableParent(sfEntity, op.parentId()) — without it a "
        + "batched child with an unmappable parent is written exactly like BUG-1");
    Matcher writeGates = Pattern.compile("applyWriteGatesToDalBody\\s*\\(").matcher(body);
    assertTrue(writeGates.find(), "anchor applyWriteGatesToDalBody moved; update this guard");
    assertTrue(gate.start() < writeGates.start(),
        "the parent gate must run before any other gate or injection touches the body");
  }
}
