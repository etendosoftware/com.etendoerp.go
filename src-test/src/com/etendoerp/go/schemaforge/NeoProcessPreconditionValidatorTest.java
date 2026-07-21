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
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.base.model.Entity;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link NeoProcessPreconditionValidator} and its condition evaluator
 * {@link PreconditionConditionEvaluator}. Pure logic — no database and no static mocking
 * required: the {@link SFEntity} preconditions JSON and the record property values are
 * supplied via Mockito stubs.
 *
 * <p>The fixture uses the real "Create Amortization" rules (AD_Process 800125) so the
 * {@code @amortize@} (DB {@code Assetschedule}, values YE/MO) and {@code @calculateType@}
 * (DB {@code amortizationcalctype}, values PE/TI) mappings are exercised end to end.</p>
 */
public class NeoProcessPreconditionValidatorTest {

  private static final String PROCESS_ID = "800125";

  /** Real assets preconditions declaration (data-only; no Java branches). */
  private static final String ASSETS_PRECONDITIONS =
      "{ \"800125\": ["
      + "{ \"field\": \"usableLifeMonths\", \"requiredWhen\": \"@calculateType@ != 'PE' && @amortize@ != 'YE'\" },"
      + "{ \"field\": \"usableLifeYears\",  \"requiredWhen\": \"@amortize@ == 'YE'\" },"
      + "{ \"field\": \"currency\" }"
      + "] }";

  private Process mockProcess() {
    Process process = mock(Process.class);
    when(process.getId()).thenReturn(PROCESS_ID);
    return process;
  }

  private SFEntity mockEntity(String preconditionsJson) {
    SFEntity entity = mock(SFEntity.class);
    when(entity.get(NeoProcessPreconditionValidator.PRECONDITIONS_PROPERTY))
        .thenReturn(preconditionsJson);
    return entity;
  }

  // ===================== findUnmetPreconditions edge cases =====================

  @Test
  public void allPreconditionsMetReturnsEmpty() {
    // MO-scheduled, TI calc type: usableLifeMonths required and present; currency present;
    // usableLifeYears rule skipped (amortize != YE).
    BaseOBObject record = mock(BaseOBObject.class);
    when(record.get("calculateType")).thenReturn("TI");
    when(record.get("amortize")).thenReturn("MO");
    when(record.get("usableLifeMonths")).thenReturn(24L);
    when(record.get("currency")).thenReturn("EUR");

    List<String> missing = NeoProcessPreconditionValidator.findUnmetPreconditions(
        mockProcess(), mockEntity(ASSETS_PRECONDITIONS), record, new JSONObject());

    assertNotNull(missing);
    assertTrue("Expected no unmet preconditions, got " + missing, missing.isEmpty());
  }

  @Test
  public void oneUnmetPreconditionReturnsThatField() {
    // usableLifeMonths NULL with calc != PE and amortize != YE => rule applies and is unmet.
    BaseOBObject record = mock(BaseOBObject.class);
    when(record.get("calculateType")).thenReturn("TI");
    when(record.get("amortize")).thenReturn("MO");
    when(record.get("usableLifeMonths")).thenReturn(null);
    when(record.get("currency")).thenReturn("EUR");

    List<String> missing = NeoProcessPreconditionValidator.findUnmetPreconditions(
        mockProcess(), mockEntity(ASSETS_PRECONDITIONS), record, new JSONObject());

    assertEquals(1, missing.size());
    assertEquals("usableLifeMonths", missing.get(0));
  }

  @Test
  public void multipleUnmetPreconditionsReturnAll() {
    // usableLifeMonths NULL (rule applies) + currency NULL (unconditional) => both missing.
    BaseOBObject record = mock(BaseOBObject.class);
    when(record.get("calculateType")).thenReturn("TI");
    when(record.get("amortize")).thenReturn("MO");
    when(record.get("usableLifeMonths")).thenReturn(null);
    when(record.get("currency")).thenReturn(null);

    List<String> missing = NeoProcessPreconditionValidator.findUnmetPreconditions(
        mockProcess(), mockEntity(ASSETS_PRECONDITIONS), record, new JSONObject());

    assertEquals(2, missing.size());
    assertTrue(missing.contains("usableLifeMonths"));
    assertTrue(missing.contains("currency"));
  }

  @Test
  public void requiredWhenFalseRuleIsSkipped() {
    // YE schedule: usableLifeMonths rule (amortize != YE) is FALSE and must be skipped even
    // though usableLifeMonths is NULL; usableLifeYears rule (amortize == YE) applies and is met.
    BaseOBObject record = mock(BaseOBObject.class);
    when(record.get("calculateType")).thenReturn("TI");
    when(record.get("amortize")).thenReturn("YE");
    when(record.get("usableLifeMonths")).thenReturn(null);
    when(record.get("usableLifeYears")).thenReturn(5L);
    when(record.get("currency")).thenReturn("EUR");

    List<String> missing = NeoProcessPreconditionValidator.findUnmetPreconditions(
        mockProcess(), mockEntity(ASSETS_PRECONDITIONS), record, new JSONObject());

    assertTrue("usableLifeMonths rule should have been skipped, got " + missing, missing.isEmpty());
  }

  @Test
  public void yearlyScheduleMissingUsableLifeYearsIsReported() {
    // YE schedule with usableLifeYears NULL => the amortize == YE rule applies and is unmet.
    BaseOBObject record = mock(BaseOBObject.class);
    when(record.get("calculateType")).thenReturn("TI");
    when(record.get("amortize")).thenReturn("YE");
    when(record.get("usableLifeMonths")).thenReturn(null);
    when(record.get("usableLifeYears")).thenReturn(null);
    when(record.get("currency")).thenReturn("EUR");

    List<String> missing = NeoProcessPreconditionValidator.findUnmetPreconditions(
        mockProcess(), mockEntity(ASSETS_PRECONDITIONS), record, new JSONObject());

    assertEquals(1, missing.size());
    assertEquals("usableLifeYears", missing.get(0));
  }

  @Test
  public void noDeclaredPreconditionsForProcessReturnsEmpty() {
    BaseOBObject record = mock(BaseOBObject.class);
    // Declaration exists but for a different process id.
    SFEntity entity = mockEntity("{ \"999999\": [ { \"field\": \"currency\" } ] }");

    List<String> missing = NeoProcessPreconditionValidator.findUnmetPreconditions(
        mockProcess(), entity, record, new JSONObject());

    assertTrue(missing.isEmpty());
  }

  @Test
  public void nullPreconditionsColumnReturnsEmpty() {
    BaseOBObject record = mock(BaseOBObject.class);
    List<String> missing = NeoProcessPreconditionValidator.findUnmetPreconditions(
        mockProcess(), mockEntity(null), record, new JSONObject());
    assertTrue(missing.isEmpty());
  }

  @Test
  public void nullProcessOrEntityReturnsEmpty() {
    BaseOBObject record = mock(BaseOBObject.class);
    assertTrue(NeoProcessPreconditionValidator
        .findUnmetPreconditions(null, mockEntity(ASSETS_PRECONDITIONS), record, new JSONObject())
        .isEmpty());
    assertTrue(NeoProcessPreconditionValidator
        .findUnmetPreconditions(mockProcess(), null, record, new JSONObject())
        .isEmpty());
  }

  @Test
  public void malformedPreconditionsJsonReturnsEmpty() {
    BaseOBObject record = mock(BaseOBObject.class);
    List<String> missing = NeoProcessPreconditionValidator.findUnmetPreconditions(
        mockProcess(), mockEntity("{ not valid json"), record, new JSONObject());
    assertTrue(missing.isEmpty());
  }

  @Test
  public void paramValueSatisfiesPreconditionOverRecord() {
    // Record has no currency, but the request params supply it => precondition met.
    BaseOBObject record = mock(BaseOBObject.class);
    when(record.get("calculateType")).thenReturn("PE");
    when(record.get("amortize")).thenReturn("YE");
    when(record.get("usableLifeYears")).thenReturn(5L);
    when(record.get("currency")).thenReturn(null);

    JSONObject params = new JSONObject();
    try {
      params.put("currency", "USD");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }

    List<String> missing = NeoProcessPreconditionValidator.findUnmetPreconditions(
        mockProcess(), mockEntity(ASSETS_PRECONDITIONS), record, params);

    assertTrue("currency supplied via params should satisfy the rule, got " + missing,
        missing.isEmpty());
  }

  // ===================== unknown-property fail-open =====================

  /**
   * Stubs {@code record.getEntity()} to return a mocked {@link Entity} whose
   * {@code hasProperty(field)} answers according to {@code known}. Required because the
   * validator now consults the record's entity model on every field check; a bare
   * {@code mock(BaseOBObject.class)} would return a {@code null} entity and force the
   * fail-open NPE fallback, masking the property lookup under test.
   */
  private static void stubEntityHasProperty(BaseOBObject record, String field, boolean known) {
    Entity entity = mock(Entity.class);
    when(entity.hasProperty(field)).thenReturn(known);
    when(record.getEntity()).thenReturn(entity);
  }

  @Test
  public void unknownPreconditionFieldFailsOpenAndIsSkipped() {
    // Regression: a rule naming a property the entity does NOT have is a config typo, not an
    // unmet precondition. It must be logged and skipped (fail open), never reported missing —
    // even though the requiredWhen holds and the record has no such value.
    BaseOBObject record = mock(BaseOBObject.class);
    when(record.get("calculateType")).thenReturn("TI");
    when(record.get("amortize")).thenReturn("MO");
    when(record.get("nonExistentField")).thenReturn(null);
    stubEntityHasProperty(record, "nonExistentField", false);

    SFEntity entity = mockEntity(
        "{ \"800125\": [ { \"field\": \"nonExistentField\", "
        + "\"requiredWhen\": \"@calculateType@ != 'PE' && @amortize@ != 'YE'\" } ] }");

    List<String> missing = NeoProcessPreconditionValidator.findUnmetPreconditions(
        mockProcess(), entity, record, new JSONObject());

    assertNotNull(missing);
    assertFalse("Unknown precondition field must be skipped (fail open), got " + missing,
        missing.contains("nonExistentField"));
    assertTrue("No preconditions should be reported for a misconfigured rule, got " + missing,
        missing.isEmpty());
  }

  @Test
  public void knownButEmptyFieldStillReportedMissing() {
    // Positive control: the fix narrows behavior only for UNKNOWN properties. A field that IS a
    // known property of the entity but is empty on the record must still be reported missing,
    // proving genuine missing-value detection was not weakened.
    BaseOBObject record = mock(BaseOBObject.class);
    when(record.get("currency")).thenReturn(null);
    stubEntityHasProperty(record, "currency", true);

    SFEntity entity = mockEntity("{ \"800125\": [ { \"field\": \"currency\" } ] }");

    List<String> missing = NeoProcessPreconditionValidator.findUnmetPreconditions(
        mockProcess(), entity, record, new JSONObject());

    assertEquals(1, missing.size());
    assertEquals("currency", missing.get(0));
  }

  // ===================== condition evaluator =====================

  private static Function<String, String> resolver(Map<String, String> values) {
    return values::get;
  }

  @Test
  public void evaluatorBlankExpressionIsUnconditionalTrue() {
    assertTrue(PreconditionConditionEvaluator.evaluate(null, resolver(new HashMap<>())));
    assertTrue(PreconditionConditionEvaluator.evaluate("   ", resolver(new HashMap<>())));
  }

  @Test
  public void evaluatorEqualityAgainstLiteral() {
    Map<String, String> values = new HashMap<>();
    values.put("amortize", "YE");
    assertTrue(PreconditionConditionEvaluator.evaluate("@amortize@ == 'YE'", resolver(values)));
    assertFalse(PreconditionConditionEvaluator.evaluate("@amortize@ != 'YE'", resolver(values)));
  }

  @Test
  public void evaluatorInequalityAndNullField() {
    Map<String, String> values = new HashMap<>();
    // amortize resolves to null (absent) => null == 'YE' is false, null != 'YE' is true.
    assertFalse(PreconditionConditionEvaluator.evaluate("@amortize@ == 'YE'", resolver(values)));
    assertTrue(PreconditionConditionEvaluator.evaluate("@amortize@ != 'YE'", resolver(values)));
  }

  @Test
  public void evaluatorAndOperatorBothForms() {
    Map<String, String> values = new HashMap<>();
    values.put("calculateType", "TI");
    values.put("amortize", "MO");
    // Both terms true => AND true, for both '&&' and single '&'.
    assertTrue(PreconditionConditionEvaluator.evaluate(
        "@calculateType@ != 'PE' && @amortize@ != 'YE'", resolver(values)));
    assertTrue(PreconditionConditionEvaluator.evaluate(
        "@calculateType@ != 'PE' & @amortize@ != 'YE'", resolver(values)));

    // Flip amortize to YE => second term false => AND false.
    values.put("amortize", "YE");
    assertFalse(PreconditionConditionEvaluator.evaluate(
        "@calculateType@ != 'PE' && @amortize@ != 'YE'", resolver(values)));
  }

  @Test
  public void evaluatorOrOperator() {
    Map<String, String> values = new HashMap<>();
    values.put("amortize", "MO");
    // First OR-term false, second true => OR true.
    assertTrue(PreconditionConditionEvaluator.evaluate(
        "@amortize@ == 'YE' || @amortize@ == 'MO'", resolver(values)));
    // Neither true => OR false.
    values.put("amortize", "XX");
    assertFalse(PreconditionConditionEvaluator.evaluate(
        "@amortize@ == 'YE' || @amortize@ == 'MO'", resolver(values)));
  }
}
