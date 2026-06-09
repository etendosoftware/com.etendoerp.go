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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.BankStatementsSupport.deriveStatementStatus;
import static com.etendoerp.go.schemaforge.BankStatementsSupport.nullSafeBigDecimal;

import java.math.BigDecimal;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

/**
 * Recomputes and persists the read-only {@code EM_ETGO_*} aggregate columns of a
 * {@link FIN_BankStatement} (line count, matched count, total in / out and the
 * reconciliation status). These columns are real stored values — never computed
 * columns — so they can feed list views, conditional filters and reports without
 * recomputing on every read.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #recompute(FIN_BankStatement)} — authoritative full recount over
 *       the already-persisted lines. Called by {@link BankStatementsHandler} at
 *       the end of every write flow and by the backfill process.</li>
 *   <li>{@link #recomputeWithDelta(FIN_BankStatement, FIN_BankStatementLine, Op,
 *       EntityPersistenceEvent)} — used by {@link BankStatementLineAggregateHandler}
 *       while a line is being persisted (before commit): it aggregates the other
 *       active lines via a query and folds in the in-flight line from the event
 *       state, avoiding a flush mid-event.</li>
 * </ul>
 *
 * <p>No recursion: the recompute saves the <em>parent</em> statement (a different
 * entity than the line), so it never re-triggers the line observer.
 *
 * <p>Defensive: if the {@code EM_ETGO_*} columns are not yet in the in-memory
 * model (e.g. before {@code update.database} runs after deploy), the property
 * lookups return {@code null} and the recompute is a no-op — line persistence
 * is never broken.
 */
public final class BankStatementAggregates {

  private static final Logger log = LogManager.getLogger(BankStatementAggregates.class);

  static final String COL_LINE_COUNT = "EM_ETGO_LINE_COUNT";
  static final String COL_MATCHED_COUNT = "EM_ETGO_MATCHED_COUNT";
  static final String COL_TOTAL_IN = "EM_ETGO_TOTAL_IN";
  static final String COL_TOTAL_OUT = "EM_ETGO_TOTAL_OUT";
  static final String COL_STATUS = "EM_ETGO_STATUS";

  /** Persistence operation driving a delta recompute. */
  public enum Op { NEW, UPDATE, DELETE }

  private BankStatementAggregates() {
    // utility class — no instances
  }

  /**
   * Full, authoritative recount over the statement's already-persisted active
   * lines. Use this after the lines have been flushed (our write flows and the
   * backfill). Saves the statement when the aggregate columns exist.
   *
   * @param st the statement to recompute (no-op when {@code null})
   */
  public static void recompute(FIN_BankStatement st) {
    if (st == null) {
      return;
    }
    Counters c = new Counters();
    for (FIN_BankStatementLine line : activeLines(st, null)) {
      c.add(nullSafeBigDecimal(line.getCramount()), nullSafeBigDecimal(line.getDramount()),
          line.getFinancialAccountTransaction() != null);
    }
    apply(st, c);
  }

  /**
   * Recompute used by the line observer while a line is mid-persist. Aggregates
   * the <em>other</em> active lines of the parent (excluding the in-flight one by
   * id) and folds in the event line read from {@link EntityPersistenceEvent}
   * current state — except on {@link Op#DELETE}, where it is omitted. This avoids
   * relying on a flush in the middle of the persistence event.
   *
   * @param parent the owning statement (no-op when {@code null})
   * @param line   the line being persisted
   * @param op     the persistence operation
   * @param event  the persistence event carrying the line's current state
   */
  public static void recomputeWithDelta(FIN_BankStatement parent, FIN_BankStatementLine line,
      Op op, EntityPersistenceEvent event) {
    if (parent == null) {
      return;
    }
    Counters c = new Counters();
    for (FIN_BankStatementLine other : activeLines(parent, line.getId())) {
      c.add(nullSafeBigDecimal(other.getCramount()), nullSafeBigDecimal(other.getDramount()),
          other.getFinancialAccountTransaction() != null);
    }
    if (op != Op.DELETE) {
      Entity lineEntity = event.getTargetInstance().getEntity();
      BigDecimal cr = (BigDecimal) event.getCurrentState(
          lineEntity.getProperty(FIN_BankStatementLine.PROPERTY_CRAMOUNT));
      BigDecimal dr = (BigDecimal) event.getCurrentState(
          lineEntity.getProperty(FIN_BankStatementLine.PROPERTY_DRAMOUNT));
      Object tx = event.getCurrentState(
          lineEntity.getProperty(FIN_BankStatementLine.PROPERTY_FINANCIALACCOUNTTRANSACTION));
      c.add(nullSafeBigDecimal(cr), nullSafeBigDecimal(dr), tx != null);
    }
    apply(parent, c);
  }

  /**
   * Active lines of {@code st}, optionally excluding the line whose id is
   * {@code excludeId} (used by the delta recompute to drop the in-flight line).
   * Reads across organizations like {@code resolveBsfDocType} so the count is not
   * silently truncated by the user's readable-org filter.
   */
  private static List<FIN_BankStatementLine> activeLines(FIN_BankStatement st, String excludeId) {
    OBCriteria<FIN_BankStatementLine> crit =
        OBDal.getInstance().createCriteria(FIN_BankStatementLine.class);
    crit.add(Restrictions.eq(FIN_BankStatementLine.PROPERTY_BANKSTATEMENT, st));
    crit.add(Restrictions.eq(FIN_BankStatementLine.PROPERTY_ACTIVE, true));
    if (excludeId != null) {
      crit.add(Restrictions.ne("id", excludeId));
    }
    crit.setFilterOnReadableOrganization(false);
    return crit.list();
  }

  /**
   * Writes the five aggregate columns onto the statement and saves it. Resolves
   * the properties by column name so the class compiles without the generated
   * accessors; when the columns are absent from the model the call is a no-op.
   */
  private static void apply(FIN_BankStatement st, Counters c) {
    Entity entity = ModelProvider.getInstance().getEntity(FIN_BankStatement.ENTITY_NAME);
    Property lineCountProp = entity.getPropertyByColumnName(COL_LINE_COUNT);
    Property matchedCountProp = entity.getPropertyByColumnName(COL_MATCHED_COUNT);
    Property totalInProp = entity.getPropertyByColumnName(COL_TOTAL_IN);
    Property totalOutProp = entity.getPropertyByColumnName(COL_TOTAL_OUT);
    Property statusProp = entity.getPropertyByColumnName(COL_STATUS);

    if (lineCountProp == null || matchedCountProp == null || totalInProp == null
        || totalOutProp == null || statusProp == null) {
      log.debug("EM_ETGO_* aggregate columns not in model yet — skipping recompute for statement {}",
          st.getId());
      return;
    }

    String status = deriveStatementStatus(Boolean.TRUE.equals(st.isProcessed()),
        c.lineCount, c.matchedCount);

    st.set(lineCountProp.getName(), (long) c.lineCount);
    st.set(matchedCountProp.getName(), (long) c.matchedCount);
    st.set(totalInProp.getName(), c.totalIn);
    st.set(totalOutProp.getName(), c.totalOut);
    st.set(statusProp.getName(), status);

    OBDal.getInstance().save(st);
  }

  /** Running totals accumulated over a statement's lines. */
  private static final class Counters {
    private int lineCount;
    private int matchedCount;
    private BigDecimal totalIn = BigDecimal.ZERO;
    private BigDecimal totalOut = BigDecimal.ZERO;

    private void add(BigDecimal cramount, BigDecimal dramount, boolean matched) {
      lineCount++;
      totalIn = totalIn.add(cramount);
      totalOut = totalOut.add(dramount);
      if (matched) {
        matchedCount++;
      }
    }
  }
}
