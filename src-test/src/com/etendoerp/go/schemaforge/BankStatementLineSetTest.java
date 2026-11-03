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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

import com.etendoerp.go.schemaforge.BankStatementLineSet.BankIdentity;

/**
 * The part of a statement edit ({@code bank-statements?action=update}) that keeps a rebuilt line's
 * bank identity (ETP-5581): {@link BankStatementLineSet#bankIdentitiesOfUnmatched} reads, before
 * the unmatched lines are deleted, the PSD2 transaction id, the discard flag and button and the
 * bounce-pair link; {@link BankStatementLineSet#restoreBankIdentity} writes them back on the line
 * rebuilt from the same {@code "id"}; {@link BankStatementLineSet#relinkBouncePairs} relinks the
 * pairs once every line has its new id — between two rebuilt lines of one statement, or in both
 * directions with a counterpart kept in another statement.
 *
 * <p>The DAL model is simulated column by column ({@link ModelProvider} answers a property per
 * mapped column, each line keeps its values in a map), so the writes go through
 * {@link StatementLineDiscard#set} as in production. The handler wiring of the same flow is in
 * {@code BankStatementsHandlerTest}.
 *
 * @covers com.etendoerp.go.schemaforge.BankStatementLineSet
 */
@DisplayName("BankStatementLineSet — bank identity across a statement edit")
class BankStatementLineSetTest {

  private static final Date DISCARDED_AT = new Date(1_780_000_000_000L);

  private final Map<String, String> mappedColumns = new HashMap<>();
  private MockedStatic<ModelProvider> modelStatic;
  private MockedStatic<OBDal> dalStatic;
  private OBDal dal;

  /**
   * Simulates the running model with the PSD2 discard and bounce-link columns mapped, and mocks
   * the DAL.
   */
  @BeforeEach
  void setUp() {
    for (String column : new String[] { StatementLineDiscard.COL_IS_DISCARDED,
        StatementLineDiscard.COL_DISCARD_LINE, StatementLineDiscard.COL_TRANSACTION_IDENTIFIER,
        BankBouncePairService.COL_PAIR_LINE, BankBouncePairService.COL_DISCARDED_AT,
        BankBouncePairService.COL_SIGNAL, BankBouncePairService.COL_CONFIDENCE }) {
      mappedColumns.put(column.toUpperCase(Locale.ROOT), prop(column));
    }
    ModelProvider model = mock(ModelProvider.class);
    Entity entity = mock(Entity.class);
    when(model.getEntity(FIN_BankStatementLine.ENTITY_NAME)).thenReturn(entity);
    when(entity.getPropertyByColumnName(anyString(), eq(false))).thenAnswer(inv -> {
      String name = mappedColumns.get(((String) inv.getArgument(0)).toUpperCase(Locale.ROOT));
      if (name == null) {
        return null;
      }
      Property property = mock(Property.class);
      when(property.getName()).thenReturn(name);
      return property;
    });
    modelStatic = mockStatic(ModelProvider.class);
    modelStatic.when(ModelProvider::getInstance).thenReturn(model);
    dal = mock(OBDal.class);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
  }

  /** Closes the static mocks. */
  @AfterEach
  void tearDown() {
    dalStatic.close();
    modelStatic.close();
  }

  @Nested
  @DisplayName("bankIdentitiesOfUnmatched")
  class Read {

    /** Captures every unmatched line with something to keep, by line id. */
    @Test
    @DisplayName("captures every unmatched line with something to keep, by line id")
    void capturesWhatEachLineCarries() {
      Map<String, Object> paired = new HashMap<>();
      Map<String, Object> plainPsd2 = new HashMap<>();
      FIN_BankStatementLine counterpart = line("L-IN", new HashMap<>());
      FIN_BankStatementLine pairedLine = line("L-OUT", paired);
      paired.put(prop(StatementLineDiscard.COL_TRANSACTION_IDENTIFIER), " TX-1 ");
      paired.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);
      paired.put(prop(StatementLineDiscard.COL_DISCARD_LINE), "Y");
      paired.put(prop(BankBouncePairService.COL_PAIR_LINE), counterpart);
      paired.put(prop(BankBouncePairService.COL_DISCARDED_AT), DISCARDED_AT);
      paired.put(prop(BankBouncePairService.COL_SIGNAL), "return_text");
      paired.put(prop(BankBouncePairService.COL_CONFIDENCE), "high");
      FIN_BankStatementLine plainPsd2Line = line("L-PSD2", plainPsd2);
      plainPsd2.put(prop(StatementLineDiscard.COL_TRANSACTION_IDENTIFIER), "TX-2");
      FIN_BankStatementLine manual = line("L-MANUAL", new HashMap<>());
      OBCriteria<FIN_BankStatementLine> crit = criteria(
          List.of(pairedLine, plainPsd2Line, manual));
      FIN_BankStatement statement = mock(FIN_BankStatement.class);

      Map<String, BankIdentity> identities =
          BankStatementLineSet.bankIdentitiesOfUnmatched(statement);

      assertEquals(2, identities.size(), "a manual line with nothing to keep is left out");
      assertEquals(new BankIdentity("TX-1", true, "Y", "L-IN", DISCARDED_AT, "return_text",
          "high"), identities.get("L-OUT"));
      assertEquals(new BankIdentity("TX-2", false, null, null, null, null, null),
          identities.get("L-PSD2"));
      ArgumentCaptor<Criterion> restrictions = ArgumentCaptor.forClass(Criterion.class);
      verify(crit, org.mockito.Mockito.atLeastOnce()).add(restrictions.capture());
      List<String> applied = restrictions.getAllValues().stream().map(Object::toString)
          .collect(Collectors.toList());
      assertTrue(applied.contains(
          FIN_BankStatementLine.PROPERTY_FINANCIALACCOUNTTRANSACTION + " is null"),
          "only UNMATCHED lines are read, the ones deleteUnmatched removes: " + applied);
    }

    /** A discarded line with no bank id and no pair is still captured. */
    @Test
    @DisplayName("a discarded line with no bank id and no pair is still captured")
    void discardedLineAloneIsKept() {
      Map<String, Object> values = new HashMap<>();
      values.put(prop(StatementLineDiscard.COL_IS_DISCARDED), true);
      criteria(List.of(line("L-1", values)));

      BankIdentity identity = BankStatementLineSet
          .bankIdentitiesOfUnmatched(mock(FIN_BankStatement.class)).get("L-1");

      assertTrue(identity.discarded());
      assertFalse(identity.isEmpty());
    }
  }

  @Nested
  @DisplayName("restoreBankIdentity")
  class Restore {

    /** Writes back the bank id, the discard flag and button and the bounce data. */
    @Test
    @DisplayName("writes back the bank id, the discard flag and button and the bounce data")
    void writesEverythingBack() {
      Map<String, Object> values = new HashMap<>();
      FIN_BankStatementLine rebuilt = line("NEW-1", values);

      BankStatementLineSet.restoreBankIdentity(rebuilt, new BankIdentity("TX-1", true, "Y",
          "L-IN", DISCARDED_AT, "pis_link", "high"));

      assertEquals("TX-1", values.get(prop(StatementLineDiscard.COL_TRANSACTION_IDENTIFIER)));
      assertEquals(Boolean.TRUE, values.get(prop(StatementLineDiscard.COL_IS_DISCARDED)));
      assertEquals("Y", values.get(prop(StatementLineDiscard.COL_DISCARD_LINE)));
      assertEquals(DISCARDED_AT, values.get(prop(BankBouncePairService.COL_DISCARDED_AT)));
      assertEquals("pis_link", values.get(prop(BankBouncePairService.COL_SIGNAL)));
      assertEquals("high", values.get(prop(BankBouncePairService.COL_CONFIDENCE)));
      assertFalse(values.containsKey(prop(BankBouncePairService.COL_PAIR_LINE)),
          "the link itself waits for relinkBouncePairs, once every line has its new id");
    }

    /** A discarded identity with no captured button gets the discarded value Y. */
    @Test
    @DisplayName("a discarded identity with no captured button gets the discarded value Y")
    void missingButtonDefaultsToDiscarded() {
      Map<String, Object> values = new HashMap<>();
      BankStatementLineSet.restoreBankIdentity(line("NEW-1", values),
          new BankIdentity(null, true, null, null, null, null, null));

      assertEquals(StatementLineDiscard.BUTTON_DISCARDED,
          values.get(prop(StatementLineDiscard.COL_DISCARD_LINE)));
    }

    /** PSD2 discard not installed: the bank id is kept, the discard is not written. */
    @Test
    @DisplayName("PSD2 discard not installed: the bank id is kept, the discard is not written")
    void discardSkippedWithoutPsd2Columns() {
      mappedColumns.remove(StatementLineDiscard.COL_DISCARD_LINE.toUpperCase(Locale.ROOT));
      Map<String, Object> values = new HashMap<>();

      BankStatementLineSet.restoreBankIdentity(line("NEW-1", values),
          new BankIdentity("TX-1", true, "Y", null, null, null, null));

      assertEquals("TX-1", values.get(prop(StatementLineDiscard.COL_TRANSACTION_IDENTIFIER)));
      assertFalse(values.containsKey(prop(StatementLineDiscard.COL_IS_DISCARDED)));
    }

    /** No pair: the bounce columns are not touched; no identity: nothing is written. */
    @Test
    @DisplayName("no pair: the bounce columns are not touched; no identity: nothing is written")
    void writesOnlyWhatThereIs() {
      FIN_BankStatementLine rebuilt = line("NEW-1", new HashMap<>());
      BankStatementLineSet.restoreBankIdentity(rebuilt,
          new BankIdentity("TX-1", false, "N", null, null, null, null));
      verify(rebuilt).set(prop(StatementLineDiscard.COL_TRANSACTION_IDENTIFIER), "TX-1");
      verify(rebuilt, never()).set(eq(prop(BankBouncePairService.COL_SIGNAL)), any());
      verify(rebuilt, never()).set(eq(prop(StatementLineDiscard.COL_IS_DISCARDED)), any());

      FIN_BankStatementLine fresh = line("NEW-2", new HashMap<>());
      BankStatementLineSet.restoreBankIdentity(fresh, null);
      verify(fresh, never()).set(anyString(), any());
    }
  }

  @Nested
  @DisplayName("relinkBouncePairs")
  class Relink {

    /** A pair inside one statement is linked between the two rebuilt lines. */
    @Test
    @DisplayName("a pair inside one statement is linked between the two rebuilt lines")
    void pairInsideOneStatement() {
      Map<String, Object> outValues = new HashMap<>();
      Map<String, Object> inValues = new HashMap<>();
      FIN_BankStatementLine newOut = line("NEW-OUT", outValues);
      FIN_BankStatementLine newIn = line("NEW-IN", inValues);
      Map<String, BankIdentity> identities = Map.of(
          "OLD-OUT", identity("TX-OUT", "OLD-IN"),
          "OLD-IN", identity("TX-IN", "OLD-OUT"));
      Map<String, FIN_BankStatementLine> rebuilt = new LinkedHashMap<>();
      rebuilt.put("OLD-OUT", newOut);
      rebuilt.put("OLD-IN", newIn);

      BankStatementLineSet.relinkBouncePairs(identities, rebuilt);

      assertSame(newIn, outValues.get(prop(BankBouncePairService.COL_PAIR_LINE)));
      assertSame(newOut, inValues.get(prop(BankBouncePairService.COL_PAIR_LINE)));
      verify(dal).save(newOut);
      verify(dal).save(newIn);
      verify(dal, never()).get(eq(FIN_BankStatementLine.class), anyString());
    }

    /** A pair across two statements is linked again in both directions. */
    @Test
    @DisplayName("a pair across two statements is linked again in both directions")
    void pairAcrossTwoStatements() {
      Map<String, Object> rebuiltValues = new HashMap<>();
      Map<String, Object> keptValues = new HashMap<>();
      FIN_BankStatementLine newOut = line("NEW-OUT", rebuiltValues);
      // The counterpart lives in another statement: kept, its link nulled by the foreign key.
      FIN_BankStatementLine keptIn = line("KEPT-IN", keptValues);
      when(dal.get(FIN_BankStatementLine.class, "KEPT-IN")).thenReturn(keptIn);

      BankStatementLineSet.relinkBouncePairs(Map.of("OLD-OUT", identity("TX-OUT", "KEPT-IN")),
          Map.of("OLD-OUT", newOut));

      assertSame(keptIn, rebuiltValues.get(prop(BankBouncePairService.COL_PAIR_LINE)));
      assertSame(newOut, keptValues.get(prop(BankBouncePairService.COL_PAIR_LINE)));
      verify(dal).save(newOut);
      verify(dal).save(keptIn);
    }

    /** A counterpart that no longer exists leaves the rebuilt line unlinked. */
    @Test
    @DisplayName("a counterpart that no longer exists leaves the rebuilt line unlinked")
    void deletedCounterpartLeavesNoLink() {
      Map<String, Object> values = new HashMap<>();
      FIN_BankStatementLine newOut = line("NEW-OUT", values);

      BankStatementLineSet.relinkBouncePairs(Map.of("OLD-OUT", identity("TX-OUT", "GONE")),
          Map.of("OLD-OUT", newOut));

      assertNull(values.get(prop(BankBouncePairService.COL_PAIR_LINE)));
      verify(dal, never()).save(any());
    }

    /** A rebuilt line whose identity has no pair is skipped. */
    @Test
    @DisplayName("a rebuilt line whose identity has no pair is skipped")
    void unpairedLineIsSkipped() {
      FIN_BankStatementLine newLine = line("NEW-1", new HashMap<>());

      BankStatementLineSet.relinkBouncePairs(
          Map.of("OLD-1", new BankIdentity("TX-1", false, null, null, null, null, null)),
          Map.of("OLD-1", newLine));

      verify(newLine, never()).set(anyString(), any());
      verify(dal, never()).save(any());
    }
  }

  // ---------------------------------------------------------------------------

  /**
   * The identity of a discarded line, linked to {@code pairLineId}, stored for review.
   *
   * @param transactionId the PSD2 transaction id
   * @param pairLineId    the counterpart's id, or {@code null}
   * @return the identity
   */
  private static BankIdentity identity(String transactionId, String pairLineId) {
    return new BankIdentity(transactionId, true, "Y", pairLineId, DISCARDED_AT, "counterparty",
        "review");
  }

  /**
   * Makes the next statement-line criteria answer {@code lines}.
   *
   * @param lines the lines to answer
   * @return the criteria mock
   */
  @SuppressWarnings("unchecked")
  private OBCriteria<FIN_BankStatementLine> criteria(List<FIN_BankStatementLine> lines) {
    OBCriteria<FIN_BankStatementLine> crit = mock(OBCriteria.class);
    when(dal.createCriteria(FIN_BankStatementLine.class)).thenReturn(crit);
    when(crit.add(any())).thenReturn(crit);
    when(crit.list()).thenReturn(lines);
    return crit;
  }

  /** A line mock whose columns live in {@code values}, keyed by property name. */
  private static FIN_BankStatementLine line(String id, Map<String, Object> values) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    when(line.getId()).thenReturn(id);
    when(line.get(anyString())).thenAnswer(inv -> values.get(inv.getArgument(0)));
    doAnswer(inv -> values.put(inv.getArgument(0), inv.getArgument(1)))
        .when(line).set(anyString(), any());
    return line;
  }

  /**
   * The DAL property name the simulated model maps a column to.
   *
   * @param column the column name
   * @return the property name
   */
  private static String prop(String column) {
    return "p_" + column.toLowerCase(Locale.ROOT);
  }
}
