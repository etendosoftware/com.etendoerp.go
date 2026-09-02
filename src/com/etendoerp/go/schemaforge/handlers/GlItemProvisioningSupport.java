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

package com.etendoerp.go.schemaforge.handlers;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.gl.GLItemAccounts;

/**
 * ETP-5020 — makes {@code C_Glitem} ("GL Item") invisible plumbing behind a chart-of-accounts
 * subaccount ("Cuenta contable" / {@code C_ElementValue}).
 *
 * <p>Etendo Classic's posting engine routes journal entries through GL Items, a separate entity
 * from the subaccount itself. Since {@link GLItem} carries no accounting-use field of its own —
 * only {@code name}/{@code description}/{@code enableInCash}/{@code enableInFinancialInvoices}/
 * {@code taxCategory}/{@code tax}/{@code withholding} — the ONLY way it can diverge from its
 * subaccount is through the accounts wired on its {@link GLItemAccounts} rows
 * ({@code glitemDebitAcct}/{@code glitemCreditAcct}, both {@link AccountingCombination}). Setting
 * both to the subaccount's own <b>natural</b> combination (the one where every dimension except
 * {@code Account_ID}/{@code C_AcctSchema_ID} is {@code NULL}) structurally guarantees the two can
 * never disagree — there is nothing else left on {@code GLItem} to diverge.
 *
 * <p>The natural combination is never created here — {@code C_ELEMENTVALUE_TRG} (a native Postgres
 * trigger) already creates it for every {@code elementlevel = 'S'} leaf, once per active
 * {@link AcctSchema}, at {@code C_ElementValue} insert time (live app-level saves) or via the bulk
 * {@code C_VALIDCOMBINATION} dataset import (onboarding's bulk chart-of-accounts provisioning —
 * see {@code OnboardingDatasetDefinition#getIncludedTables}). {@link #resolveNaturalCombination}
 * only ever LOOKS UP that row, mirroring the exact 11-dimension shape
 * {@code C_ELEMENTVALUE_TRG} inserts (see
 * {@code src-db/database/model/triggers/C_ELEMENTVALUE_TRG.xml:64-75}). When the lookup
 * returns nothing — a summary/heading account (any {@code elementlevel != 'S'}) never got one —
 * that {@code null} IS the "no accounting use" filter (Case 3): no separate
 * {@code elementLevel}/{@code accountType} check is needed or added.
 *
 * <p>Two call sites share this class (see {@code docs/plans/santo_ETP-5020-gl-item-auto-management.md}):
 * <ul>
 *   <li>{@link ChartOfAccountsHandler#afterHandle} — a live subaccount POST (new subaccount) or a
 *   PATCH/PUT that flips {@code active} (the ETP-4884 deactivate/reactivate toggle).</li>
 *   <li>{@code OnboardingAccountingWiringService#wire} — the bulk default chart-of-accounts case,
 *   once per leaf {@code ElementValue} of the tenant's freshly-imported chart.</li>
 * </ul>
 *
 * <p><b>Idempotency.</b> Re-running {@link #ensureGlItemForSubaccount} for the same subaccount
 * (onboarding re-run, or a second schema becoming active later) must never mint a duplicate
 * {@link GLItem} or {@link GLItemAccounts} row. The key used is NOT the subaccount's name (an
 * unrelated, manually-created {@code GLItem} could coincidentally share it — the dev DB already
 * has hand-made rows like "Capital social"/"Sueldos y salarios", see
 * {@code modules/com.etendoerp.go/referencedata/sampledata/GOClient/C_GLITEM.xml}) — it is the
 * natural {@link AccountingCombination} itself: {@link #findGlItemAccountsByCombination} checks
 * whether some {@code GLItemAccounts} row already wires that exact combination as its
 * {@code glitemDebitAcct} before creating anything. {@link #findGlItemLinkedToAnyCombinationOf} is
 * the multi-schema twin of that check — when schema N+1 becomes active later for a subaccount that
 * already has a GL Item (created for schema 1..N), it finds and reuses that SAME {@link GLItem}
 * instead of minting a second one, so the "one subaccount, one GL Item" invariant survives the
 * multi-accounting-schema roadmap this ticket flags.
 *
 * <p><b>Best-effort contract.</b> Both public entry points swallow every exception (log + return),
 * mirroring {@code UserRoleAssignmentHandler}'s established contract for a companion-record side
 * effect: a GL Item provisioning defect must NEVER block or roll back the caller's subaccount
 * save. Every DB-touching step is factored into its own {@code protected} seam precisely so unit
 * tests can override the OBDal-touching bits without a live database (see
 * {@code OnboardingAccountingWiringServiceTest}'s {@code TestableService} for the established
 * pattern this mirrors).
 */
public class GlItemProvisioningSupport {

  private static final Logger log = LogManager.getLogger(GlItemProvisioningSupport.class);

  /**
   * Resolves every active {@link AcctSchema} for {@code client}. Shared by both call sites (the
   * {@code ChartOfAccountsHandler} hook and onboarding's bulk provisioning) so they always agree on
   * exactly which schemas get a {@code GLItemAccounts} row — the concrete "one row per active
   * schema" requirement for a multi-schema tenant.
   *
   * @param client the tenant client
   * @return every active {@link AcctSchema} belonging to {@code client}, ordered by id for
   *     deterministic iteration; never {@code null}
   */
  public List<AcctSchema> resolveActiveSchemas(Client client) {
    OBCriteria<AcctSchema> criteria = OBDal.getInstance().createCriteria(AcctSchema.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(AcctSchema.PROPERTY_CLIENT, client));
    criteria.add(Restrictions.eq(AcctSchema.PROPERTY_ACTIVE, true));
    criteria.addOrderBy(AcctSchema.PROPERTY_ID, true);
    return criteria.list();
  }

  /**
   * Ensures a GL Item exists behind {@code subaccount} for every schema in {@code activeSchemas}
   * that has a natural accounting combination for it (see class javadoc). Best-effort: any failure
   * is logged and swallowed, never propagated to the caller.
   *
   * @param subaccount    the just-created (or being re-provisioned) leaf {@code ElementValue}
   * @param activeSchemas every active {@link AcctSchema} to provision a row for (see
   *                      {@link #resolveActiveSchemas})
   */
  public void ensureGlItemForSubaccount(ElementValue subaccount, List<AcctSchema> activeSchemas) {
    if (subaccount == null || activeSchemas == null || activeSchemas.isEmpty()) {
      return;
    }
    try {
      doEnsureGlItemForSubaccount(subaccount, activeSchemas);
    } catch (Exception e) {
      log.warn("GlItemProvisioningSupport.ensureGlItemForSubaccount failed for subaccount {}: {}",
          subaccount.getId(), e.getMessage(), e);
    }
  }

  /**
   * Mirrors a subaccount deactivate/reactivate onto every {@code GLItemAccounts} row already linked
   * to {@code subaccount} (one per schema) — the coupling ETP-4884's active toggle surfaced (see
   * class javadoc): without this, deactivating a subaccount would silently leave its GL Item
   * active. No-ops for a schema with no natural combination, or with a combination but no
   * {@code GLItemAccounts} row yet (nothing provisioned yet for it — this is expected for any
   * subaccount created before ETP-5020 shipped; see the corrective-remediation note in the caller).
   * Best-effort, same contract as {@link #ensureGlItemForSubaccount}.
   *
   * @param subaccount    the subaccount whose active state just changed
   * @param activeSchemas every active {@link AcctSchema} to sync (see {@link #resolveActiveSchemas})
   * @param active        the subaccount's new active state to mirror onto its GL Item accounts
   */
  public void setGlItemAccountsActiveForSubaccount(ElementValue subaccount,
      List<AcctSchema> activeSchemas, boolean active) {
    if (subaccount == null || activeSchemas == null || activeSchemas.isEmpty()) {
      return;
    }
    try {
      doSetGlItemAccountsActive(subaccount, activeSchemas, active);
    } catch (Exception e) {
      log.warn("GlItemProvisioningSupport.setGlItemAccountsActiveForSubaccount failed for "
          + "subaccount {}: {}", subaccount.getId(), e.getMessage(), e);
    }
  }

  // ── internal implementation (protected seams for unit testing) ────────────────────────────

  /**
   * Unwrapped implementation of {@link #ensureGlItemForSubaccount}, split out so the public method
   * keeps the try/catch boundary as its only responsibility.
   */
  protected void doEnsureGlItemForSubaccount(ElementValue subaccount, List<AcctSchema> activeSchemas) {
    GLItem glItem = null;
    for (AcctSchema schema : activeSchemas) {
      try {
        glItem = ensureGlItemForSchema(subaccount, schema, glItem);
      } catch (Exception e) {
        log.warn("GlItemProvisioningSupport skipped GL Item provisioning for subaccount {} "
            + "and schema {}: {}", subaccount.getId(), schema.getId(), e.getMessage(), e);
      }
    }
  }

  /**
   * Provisions one schema for {@code subaccount}. Returns the GL Item that should be reused by
   * later schemas in the same call.
   */
  protected GLItem ensureGlItemForSchema(ElementValue subaccount, AcctSchema schema, GLItem reusableGlItem) {
    AccountingCombination combo = resolveNaturalCombination(subaccount, schema);
    if (combo == null) {
      return reusableGlItem; // Case 3 — no accounting use for this schema (summary/heading account)
    }
    GLItemAccounts existingLink = findGlItemAccountsByCombination(combo);
    if (existingLink != null) {
      // Idempotent re-run: already provisioned for this schema. Still resync the name in case
      // the subaccount was renamed since the link was created (see class javadoc).
      GLItem glItem = existingLink.getGLItem();
      syncGlItemName(glItem, subaccount);
      return glItem;
    }
    GLItem glItem = reusableGlItem;
    if (glItem == null) {
      glItem = findGlItemLinkedToAnyCombinationOf(subaccount);
    }
    if (glItem == null) {
      glItem = createGlItem(subaccount);
    }
    createGlItemAccounts(glItem, schema, combo);
    return glItem;
  }

  /**
   * Unwrapped implementation of {@link #setGlItemAccountsActiveForSubaccount}.
   */
  protected void doSetGlItemAccountsActive(ElementValue subaccount, List<AcctSchema> activeSchemas,
      boolean active) {
    for (AcctSchema schema : activeSchemas) {
      try {
        setGlItemAccountsActiveForSchema(subaccount, schema, active);
      } catch (Exception e) {
        log.warn("GlItemProvisioningSupport skipped GL Item active-state sync for subaccount {} "
            + "and schema {}: {}", subaccount.getId(), schema.getId(), e.getMessage(), e);
      }
    }
  }

  /** Mirrors one schema's already-provisioned GL Item account link to {@code active}. */
  protected void setGlItemAccountsActiveForSchema(ElementValue subaccount, AcctSchema schema,
      boolean active) {
    AccountingCombination combo = resolveNaturalCombination(subaccount, schema);
    if (combo == null) {
      return;
    }
    GLItemAccounts link = findGlItemAccountsByCombination(combo);
    if (link == null) {
      return; // nothing provisioned yet for this schema — no-op
    }
    if (!Boolean.valueOf(active).equals(link.isActive())) {
      link.setActive(active);
      OBDal.getInstance().save(link);
    }
  }

  /**
   * Looks up (never creates) the natural {@link AccountingCombination} for {@code subaccount} +
   * {@code schema}: {@code Account_ID = subaccount}, {@code C_AcctSchema_ID = schema}, every other
   * dimension {@code NULL}. Mirrors the exact dimension shape {@code C_ELEMENTVALUE_TRG} inserts
   * (see {@code C_ELEMENTVALUE_TRG.xml:64-75}). Returns {@code null} for a summary/heading account,
   * which never gets one — see class javadoc, Case 3.
   */
  protected AccountingCombination resolveNaturalCombination(ElementValue subaccount, AcctSchema schema) {
    OBCriteria<AccountingCombination> criteria =
        OBDal.getInstance().createCriteria(AccountingCombination.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(AccountingCombination.PROPERTY_ACCOUNT, subaccount));
    criteria.add(Restrictions.eq(AccountingCombination.PROPERTY_ACCOUNTINGSCHEMA, schema));
    criteria.add(Restrictions.isNull(AccountingCombination.PROPERTY_PRODUCT));
    criteria.add(Restrictions.isNull(AccountingCombination.PROPERTY_BUSINESSPARTNER));
    criteria.add(Restrictions.isNull(AccountingCombination.PROPERTY_TRXORGANIZATION));
    criteria.add(Restrictions.isNull(AccountingCombination.PROPERTY_LOCATIONFROMADDRESS));
    criteria.add(Restrictions.isNull(AccountingCombination.PROPERTY_LOCATIONTOADDRESS));
    criteria.add(Restrictions.isNull(AccountingCombination.PROPERTY_SALESREGION));
    criteria.add(Restrictions.isNull(AccountingCombination.PROPERTY_PROJECT));
    criteria.add(Restrictions.isNull(AccountingCombination.PROPERTY_SALESCAMPAIGN));
    criteria.add(Restrictions.isNull(AccountingCombination.PROPERTY_ACTIVITY));
    criteria.add(Restrictions.isNull(AccountingCombination.PROPERTY_STDIMENSION));
    criteria.add(Restrictions.isNull(AccountingCombination.PROPERTY_NDDIMENSION));
    criteria.addOrderBy(AccountingCombination.PROPERTY_ID, true);
    criteria.setMaxResults(1);
    return (AccountingCombination) criteria.uniqueResult();
  }

  /**
   * Idempotency key: any {@link GLItemAccounts} row that already wires {@code combo} as its
   * {@code glitemDebitAcct} (debit and credit are always the same combination for a GL-Item-behind-
   * a-subaccount row — see class javadoc — so checking debit alone is sufficient).
   */
  protected GLItemAccounts findGlItemAccountsByCombination(AccountingCombination combo) {
    OBCriteria<GLItemAccounts> criteria = OBDal.getInstance().createCriteria(GLItemAccounts.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(GLItemAccounts.PROPERTY_GLITEMDEBITACCT, combo));
    criteria.setMaxResults(1);
    return (GLItemAccounts) criteria.uniqueResult();
  }

  /**
   * Multi-schema idempotency fallback: scans every combination {@code subaccount} has (across ALL
   * schemas, not just the one currently being provisioned) for one already linked to a
   * {@link GLItem}, so a newly-active schema reuses the subaccount's existing GL Item instead of
   * minting a second one. Only consulted when {@link #findGlItemAccountsByCombination} found no
   * match for the CURRENT schema (i.e., this schema genuinely has nothing yet).
   */
  protected GLItem findGlItemLinkedToAnyCombinationOf(ElementValue subaccount) {
    OBCriteria<AccountingCombination> comboCriteria =
        OBDal.getInstance().createCriteria(AccountingCombination.class);
    comboCriteria.setFilterOnReadableClients(false);
    comboCriteria.setFilterOnReadableOrganization(false);
    comboCriteria.add(Restrictions.eq(AccountingCombination.PROPERTY_ACCOUNT, subaccount));
    for (AccountingCombination combo : comboCriteria.list()) {
      GLItemAccounts link = findGlItemAccountsByCombination(combo);
      if (link != null) {
        return link.getGLItem();
      }
    }
    return null;
  }

  /**
   * {@code C_Glitem.Name} is {@code varchar(60)} — narrower than {@code C_ElementValue.Name}'s
   * {@code varchar(255)}. A live check against this DB's own data found 422 of GOClient's 1317
   * leaf subaccounts already exceed 60 characters on the BARE name alone (pre-existing, not
   * introduced by ETP-5101), and appending the code (see {@link #composeGlItemName}) pushes 166
   * more over the edge that previously fit. Without a guard, {@code OBDal.save} on any of those
   * throws (or the DB truncates/rejects, backend-dependent) — and since both call sites
   * ({@link #createGlItem}, this class's {@code afterHandle} caller) are best-effort/swallowed,
   * that failure is currently silent: the subaccount save succeeds, its GL Item just never gets
   * created, with nothing surfacing the gap short of reading logs.
   */
  private static final int GL_ITEM_NAME_MAX_LENGTH = 60;

  /**
   * Builds the {@link GLItem} name for {@code subaccount} — ETP-5101: the subaccount's 8-digit
   * code plus its name, so "Cuenta contable" and its GL Item read as the same account even when
   * several subaccounts happen to share a name, and so the code — the more useful sort/scan key
   * in a flat GL Item list — leads. Single source of truth for the format: both
   * {@link #createGlItem} and {@link #syncGlItemName} build the name through this method, so their
   * comparison never drifts — see the warning on {@link #syncGlItemName}.
   *
   * <p>Truncates the NAME portion, never the code — the code is what disambiguates two
   * subaccounts sharing a name (the entire point of prepending it), so it must always survive
   * intact within the {@link #GL_ITEM_NAME_MAX_LENGTH} budget.
   *
   * @return {@code "<searchKey>-<name, truncated to fit>"}, or the (possibly truncated) bare name
   *     if {@code searchKey} is blank (should not happen for a real leaf account, but keeps this
   *     method total)
   */
  protected static String composeGlItemName(ElementValue subaccount) {
    String name = subaccount.getName();
    String code = subaccount.getSearchKey();
    if (code == null || code.isEmpty()) {
      return truncateToFit(name, GL_ITEM_NAME_MAX_LENGTH);
    }
    String prefix = code + "-";
    String truncatedName = truncateToFit(name, GL_ITEM_NAME_MAX_LENGTH - prefix.length());
    return prefix + truncatedName;
  }

  /** Hard-truncates {@code value} to {@code maxLength}. Null-safe; {@code maxLength <= 0} yields "". */
  private static String truncateToFit(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    return value.length() <= maxLength ? value : value.substring(0, Math.max(0, maxLength));
  }

  /** Creates a brand-new {@link GLItem} for {@code subaccount} (see {@link #composeGlItemName}), in its client/org. */
  protected GLItem createGlItem(ElementValue subaccount) {
    GLItem glItem = OBProvider.getInstance().get(GLItem.class);
    glItem.setNewOBObject(true);
    glItem.setClient(subaccount.getClient());
    glItem.setOrganization(subaccount.getOrganization());
    glItem.setName(composeGlItemName(subaccount));
    glItem.setActive(true);
    OBDal.getInstance().save(glItem);
    return glItem;
  }

  /**
   * Keeps {@code glItem}'s name in sync with {@code subaccount}'s current name/code, so a
   * subaccount rename (PUT {@code /elementValue}) can never leave "Cuenta contable" and its
   * underlying GL Item silently diverged. No-op when the composed name already matches.
   *
   * <p><b>Must always compare against {@link #composeGlItemName}'s output, never the bare
   * {@code subaccount.getName()}</b> — comparing against the bare name here while
   * {@link #createGlItem} sets the composed one would make every {@code afterHandle} call think
   * the name drifted and rewrite it back down to the bare name on every single save, silently
   * undoing ETP-5101 on the very next edit.
   */
  protected void syncGlItemName(GLItem glItem, ElementValue subaccount) {
    if (glItem == null) {
      return;
    }
    String composedName = composeGlItemName(subaccount);
    if (composedName != null && !composedName.equals(glItem.getName())) {
      glItem.setName(composedName);
      OBDal.getInstance().save(glItem);
    }
  }

  /** Creates the {@code GLItemAccounts} row for {@code schema}, debit = credit = {@code combo}. */
  protected void createGlItemAccounts(GLItem glItem, AcctSchema schema, AccountingCombination combo) {
    GLItemAccounts link = OBProvider.getInstance().get(GLItemAccounts.class);
    link.setNewOBObject(true);
    link.setClient(glItem.getClient());
    link.setOrganization(glItem.getOrganization());
    link.setGLItem(glItem);
    link.setAccountingSchema(schema);
    link.setGlitemDebitAcct(combo);
    link.setGlitemCreditAcct(combo);
    link.setActive(true);
    OBDal.getInstance().save(link);
  }
}
