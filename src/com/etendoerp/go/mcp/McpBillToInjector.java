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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.businesspartner.Location;

import com.etendoerp.go.schemaforge.BatchService;

/**
 * Fills the bill-to address of a document the agent created without one, resolving it from the
 * business partner — MCP write path only.
 *
 * <p><b>Why this exists (ETP-5335).</b> {@code SE_Order_BPartner} is the only writer of
 * {@code C_Order.BillTo_ID} in the whole platform, and it writes it from a single input:
 *
 * <pre>
 *   String strLocation = info.vars.getStringParameter("inpcBpartnerId_LOC");   // line 137
 *   if (strLocation != null &amp;&amp; !strLocation.isEmpty()) {
 *     info.addResult("inpbilltoId", strLocation);                              // line 267
 *   }
 * </pre>
 *
 * <p>{@code inpcBpartnerId_LOC} is a selector auxiliary value. NEO does resolve those
 * ({@code NeoSelectorService#resolveSelectorAuxForId} feeding
 * {@code CalloutRequestBuilder#mapAuxValuesToParams}), but only from OBUISEL selector fields
 * flagged {@code isoutfield} <em>with a suffix</em> — and the selector behind
 * {@code C_Order.C_BPartner_ID} (reference 30 / 800057, "Business Partner with contact and
 * location") declares exactly one outfield, {@code identifier}, with a null suffix. So the aux
 * value never exists, {@code strLocation} is always empty, and that branch of the callout never
 * fires. The cascade runs and derives a dozen other fields; this one is simply unreachable.
 *
 * <p><b>The UI does not fill it either.</b> The only producer of the {@code _LOC} suffix left in
 * core is {@code SearchUniqueKeyResponse.html}, the legacy Classic search popup, which reads an
 * {@code inpLocation} field that no longer exists in this version. What saves the UI is not a
 * derivation but the <em>reach</em> of the mandatory check. {@code BillTo_ID} is
 * {@code ismandatory='Y'} in AD while the physical column is nullable ({@code C_ORDER.xml}:
 * {@code required="false"}), and the two create paths validate with different scope: the shared
 * path's {@code NeoMandatoryFieldValidator} inspects only the properties the caller actually
 * submitted ({@code userSubmittedFields}), so a mandatory field nobody mentions is never checked at
 * all, while {@code McpWriteRequestSupport#validateMandatoryFields} walks every mandatory AD column
 * regardless. The MCP path therefore refuses alone — for a field the create view hides from the
 * agent, because {@code visibility:"system"} excludes it from {@code view:"create"} via
 * {@code McpSchemaFieldBuilder#isAgentSuppliable}.
 *
 * <p><b>Why derive rather than let it through.</b> A null bill-to is not inert. The PL/SQL
 * {@code C_INVOICE_CREATE} copies {@code Cur_Order.BillTo_ID} straight into
 * {@code C_Invoice.C_BPartner_Location_ID} with no {@code COALESCE}, and that column <em>is</em>
 * {@code NOT NULL} — so the order becomes an invoice that cannot be generated. It is also the
 * address {@code C_GETTAX} reads {@code IsTaxExempt} and the partner tax category from, and one of
 * the {@code InvoiceGrouping} keys. Core itself already treats an empty bill-to as "use the
 * ship-to" ({@code SL_Order_Product} line 164), which is the fallback reproduced below.
 *
 * <p><b>Deliberately MCP-only.</b> Deriving this in the shared {@code schemaforge} create path
 * would change what the React frontend and the REST {@code /sws/neo/*} endpoints persist today, so
 * by decision the compensation sits in the MCP write path. The REST {@code /sws/neo/batch}
 * endpoint keeps the existing behaviour too: it shares {@code BatchService} but not the MCP
 * pre-pass this runs from.
 *
 * <p><b>Two call sites, one of them dormant.</b> {@code McpToolRouter#handleCreate} is the live
 * one. The second, in the per-operation pre-pass {@code McpToolRouter#resolveBatchOpFkNames}, only
 * runs while {@link McpConstants#BATCH_TOOL_ENABLED} is {@code true} — and it is {@code false}
 * today, so {@code neo_batch} is neither published nor routable. The call site is kept rather than
 * removed because the derivation is not what made that verb doubtful: if the flag is ever flipped
 * back, batched documents must not start being persisted with a null bill-to again. Read it as
 * wiring that is ready, not as coverage that is in force — {@code neo_create} is the only write
 * verb this reaches today
 */
final class McpBillToInjector {

  private static final String COLUMN_BILL_TO = "BillTo_ID";
  private static final String COLUMN_BPARTNER = "C_BPartner_ID";
  private static final String COLUMN_SHIP_TO = "C_BPartner_Location_ID";

  private McpBillToInjector() {
  }

  /**
   * Resolves and writes the bill-to address when the document does not carry one.
   *
   * <p>Abstains — leaving the body untouched — whenever the value cannot be established: the entity
   * has no bill-to column, the column is not mandatory there, the body already carries a value, the
   * business partner is unknown or still a {@code $ref:} placeholder, or the partner exposes no
   * usable location. Abstaining preserves the pre-existing behaviour rather than substituting a
   * guess; a wrong invoicing address is worse than an absent one, because it is plausible.
   *
   * <p>Resolution order, most specific first:
   * <ol>
   *   <li>the ship-to already chosen for this document, when it belongs to the partner and is
   *       itself flagged as a bill-to address — keeps both addresses consistent and matches what a
   *       single-location tenant expects;</li>
   *   <li>the partner's own active bill-to location;</li>
   *   <li>the ship-to as a last resort, reproducing core's own fallback in
   *       {@code SL_Order_Product} line 164.</li>
   * </ol>
   *
   * @param body      the create body, keyed by canonical DAL property name; mutated in place
   * @param adTab     the tab being written; the AD column's own mandatory flag is the gate, and it
   *                  is NOT the same as the DAL property's — see {@link #mandatoryPropertyFor}
   * @param dalEntity the DAL entity being created; decides under which property names the partner
   *                  and ship-to are addressed
   * @param log       the caller's logger, so these lines land in the MCP request's context
   */
  static void injectIfMissing(JSONObject body, Tab adTab, Entity dalEntity, Logger log) {
    try {
      Property billTo = mandatoryPropertyFor(adTab, dalEntity, COLUMN_BILL_TO);
      if (billTo == null || hasValue(body, billTo.getName())) {
        return;
      }
      String partnerId = readReference(body, dalEntity, COLUMN_BPARTNER);
      if (StringUtils.isBlank(partnerId)) {
        log.debug("[MCP-BILL-TO] No business partner on the body — bill-to left untouched");
        return;
      }
      String shipToId = readReference(body, dalEntity, COLUMN_SHIP_TO);
      String resolved = resolveBillToLocation(partnerId, shipToId);
      if (resolved == null) {
        log.debug("[MCP-BILL-TO] Business partner {} exposes no usable location — "
            + "bill-to left untouched", partnerId);
        return;
      }
      body.put(billTo.getName(), resolved);
      log.debug("[MCP-BILL-TO] Derived {}={} from business partner {}",
          billTo.getName(), resolved, partnerId);
    } catch (Exception e) {
      // Never fail a create over a derived value: without this the write keeps the pre-existing
      // behaviour (a 422 the agent can read), with it the whole call would 500.
      log.debug("[MCP-BILL-TO] Bill-to derivation skipped: {}", e.getMessage());
    }
  }

  /**
   * @return the property mapped to {@code dbColumnName}, but only when the <b>AD column</b> is
   *     mandatory on this tab — so the injector fires exactly where a missing value blocks the
   *     write, and stays out of tables that merely happen to carry the same column (e.g.
   *     {@code C_Project}, where {@code BillTo_ID} is optional and filling it would be an
   *     unrequested change)
   *
   * <p><b>Read the flag off {@link Column}, never off {@link Property}.</b> They disagree, and on
   * this very column. {@code ModelProvider} deliberately overwrites the DAL property's mandatory
   * flag with the <em>physical</em> NOT NULL of the database — "set the mandatory value on the
   * basis of the real not-null in the database!" — because, as {@code Property} itself notes,
   * "there are many cases whereby it is set to true while the underlying db column allows null,
   * this because the mandatory value is used in the ui". {@code BillTo_ID} is exactly such a case:
   * {@code ismandatory='Y'} in AD, nullable in Postgres, so {@code Property#isMandatory} answers
   * {@code false} and a gate built on it never opens.
   *
   * <p>The predicate here must be the same one the check this injector exists to satisfy uses.
   * {@code McpToolRouterSupport#resolveMandatoryProperty} — the gate in
   * {@code validateMandatoryFields} — reads {@code col.isMandatory()} off the AD column. Anything
   * else and the injector abstains precisely where the validator refuses.
   */
  private static Property mandatoryPropertyFor(Tab adTab, Entity dalEntity, String dbColumnName) {
    if (adTab == null || adTab.getTable() == null || dalEntity == null) {
      return null;
    }
    for (Column column : adTab.getTable().getADColumnList()) {
      if (dbColumnName.equalsIgnoreCase(column.getDBColumnName())) {
        return Boolean.TRUE.equals(column.isActive()) && Boolean.TRUE.equals(column.isMandatory())
            ? dalEntity.getPropertyByColumnName(dbColumnName, false)
            : null;
      }
    }
    return null;
  }

  private static boolean hasValue(JSONObject body, String propertyName) {
    return body != null
        && body.has(propertyName)
        && !body.isNull(propertyName)
        && StringUtils.isNotBlank(body.optString(propertyName, ""));
  }

  /**
   * @return the id held under the property mapped to {@code dbColumnName}, or {@code null} when it
   *     is absent, blank, or still a {@code $ref:<opId>} placeholder — inside a batch the operation
   *     it points at has not run yet, so there is no id to derive from
   */
  private static String readReference(JSONObject body, Entity dalEntity, String dbColumnName) {
    Property property = dalEntity.getPropertyByColumnName(dbColumnName, false);
    if (property == null) {
      return null;
    }
    String value = body.optString(property.getName(), "");
    if (StringUtils.isBlank(value) || value.startsWith(BatchService.REF_PREFIX)) {
      return null;
    }
    return value;
  }

  /**
   * Applies the three-step resolution documented on {@link #injectIfMissing}.
   *
   * <p>Runs in the caller's own DAL scope on purpose — no admin mode. A location this role cannot
   * read must not become the invoicing address of a document it writes.
   */
  private static String resolveBillToLocation(String partnerId, String shipToId) {
    if (isPartnerBillToLocation(partnerId, shipToId)) {
      return shipToId;
    }
    Location billTo = findBillToLocation(partnerId);
    if (billTo != null) {
      return billTo.getId();
    }
    return shipToId;
  }

  /**
   * @return {@code true} when {@code locationId} is an active location of {@code partnerId} that is
   *     itself flagged as a bill-to address
   */
  private static boolean isPartnerBillToLocation(String partnerId, String locationId) {
    if (StringUtils.isBlank(locationId)) {
      return false;
    }
    Location location = OBDal.getInstance().get(Location.class, locationId);
    return location != null
        && Boolean.TRUE.equals(location.isActive())
        && Boolean.TRUE.equals(location.isInvoiceToAddress())
        && location.getBusinessPartner() != null
        && StringUtils.equals(location.getBusinessPartner().getId(), partnerId);
  }

  private static Location findBillToLocation(String partnerId) {
    OBCriteria<Location> crit = OBDal.getInstance().createCriteria(Location.class);
    crit.add(Restrictions.eq(Location.PROPERTY_BUSINESSPARTNER + ".id", partnerId));
    crit.add(Restrictions.eq(Location.PROPERTY_INVOICETOADDRESS, true));
    crit.add(Restrictions.eq(Location.PROPERTY_ACTIVE, true));
    crit.addOrderBy(Location.PROPERTY_CREATIONDATE, true);
    crit.setMaxResults(1);
    return (Location) crit.uniqueResult();
  }
}
