/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.PlanChange;

/**
 * ETP-5053 — reads the <b>Allowed plan changes</b> configuration ({@code ETGO_PLAN_CHANGE}, the
 * child tab of the Plans window): which plans a customer on a given plan may move to, and whether
 * each move applies now or at the end of the current billing period.
 *
 * <p><b>Configuration, not code.</b> A transition with no active row is refused by the API and
 * not offered by the customer UI; adding a row offers it with no code change, and deactivating or
 * deleting the row withdraws it. The direction is what the row says ({@link PlanChangeTiming}),
 * never inferred from the two plans' prices or names, so a mixed change is settled explicitly.
 * Rows whose target plan is inactive are left out: a plan withdrawn from the catalog cannot be
 * moved to, whatever its rows say.
 *
 * <p>Rows live at {@code AD_CLIENT_ID = '0'} like the plans they join, so every read runs in
 * admin mode with the client and organization filters off — the same reading as
 * {@link PlanCatalogService#listActiveQuotas}.
 */
public class PlanChangeCatalog {

  private static final String PARAM_PLAN_ID = "planId";
  private static final String PARAM_TARGET_ID = "targetId";
  private static final String ALLOWED_FROM_PLAN = "as change"
      + " where change." + PlanChange.PROPERTY_PLAN + ".id = :" + PARAM_PLAN_ID
      + " and change." + PlanChange.PROPERTY_ACTIVE + " = true"
      + " and change." + PlanChange.PROPERTY_TARGETPLAN + "." + Plan.PROPERTY_ACTIVE + " = true";

  /**
   * The active transitions offered from a plan, cheapest target first within each billing
   * interval (monthly targets before annual ones).
   *
   * @param from the plan the customer is on, may be null
   * @return the active rows whose target plan is active, never null; empty for a null plan
   */
  public List<PlanChange> listAllowed(Plan from) {
    if (from == null) {
      return new ArrayList<>();
    }
    OBContext.setAdminMode(true);
    try {
      OBQuery<PlanChange> query = OBDal.getInstance().createQuery(PlanChange.class,
          ALLOWED_FROM_PLAN
              + " order by change." + PlanChange.PROPERTY_TARGETPLAN + "."
              + Plan.PROPERTY_BILLINGINTERVAL + ", change." + PlanChange.PROPERTY_TARGETPLAN + "."
              + Plan.PROPERTY_DISPLAYPRICE + ", change." + PlanChange.PROPERTY_TARGETPLAN + "."
              + Plan.PROPERTY_SEARCHKEY);
      query.setNamedParameter(PARAM_PLAN_ID, from.getId());
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      return query.list();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The one active row that allows moving from one plan to another.
   *
   * @param from the plan the customer is on, may be null
   * @param target the plan asked for, may be null
   * @return the row, or empty when the change is not allowed (no row, an inactive row, an
   *     inactive target, the same plan twice, or a null argument)
   */
  public Optional<PlanChange> findAllowed(Plan from, Plan target) {
    if (from == null || target == null || StringUtils.equals(from.getId(), target.getId())) {
      return Optional.empty();
    }
    OBContext.setAdminMode(true);
    try {
      OBQuery<PlanChange> query = OBDal.getInstance().createQuery(PlanChange.class,
          ALLOWED_FROM_PLAN
              + " and change." + PlanChange.PROPERTY_TARGETPLAN + ".id = :" + PARAM_TARGET_ID);
      query.setNamedParameter(PARAM_PLAN_ID, from.getId());
      query.setNamedParameter(PARAM_TARGET_ID, target.getId());
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setMaxResult(1);
      return Optional.ofNullable(query.uniqueResult());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The timing a row declares.
   *
   * @param change a configuration row, never null
   * @return its timing
   * @throws IllegalStateException when the stored value is not one the check constraint allows —
   *     a row written around the constraint, which must be seen rather than defaulted
   */
  public static PlanChangeTiming timingOf(PlanChange change) {
    return PlanChangeTiming.of(change.getTiming()).orElseThrow(() -> new IllegalStateException(
        "Allowed plan change " + change.getId() + " stores the unknown timing '"
            + change.getTiming() + "'"));
  }
}
