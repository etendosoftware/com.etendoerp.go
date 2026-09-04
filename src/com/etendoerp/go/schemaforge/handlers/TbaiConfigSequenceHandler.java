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

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.AbstractSmartDeactivationHandler;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.SiiTbaiAutoSendScheduleService;
import com.smf.ticketbai.data.TbaiConfig;

/**
 * NeoHandler for the {@code tbai-config} spec ({@code header} entity, table
 * {@code TBAI_Config}) that auto-creates and assigns the TBAI chaining sequence
 * (ETP-4401).
 *
 * <p>In classic Etendo, each invoice {@link DocumentType} needs a chaining sequence assigned to
 * {@code EM_Tbai_Ad_Sequence_ID} for TicketBAI submission, configured by hand in the Document
 * Type window. Etendo Go's declarative {@code ETGO_SF_*} layer does not expose sequence creation
 * or Document Type editing, so this handler closes that gap in Java: after the TBAI Fiscal
 * Configuration is saved, it ensures every invoice Document Type of the config's organization is
 * assigned to the single TBAI chaining sequence shared by that scope, creating it if none exists
 * yet. The scope explicitly includes organization {@code "0"} (the {@code "*"} org) alongside the
 * config organization's natural tree, since Document Types are very commonly defined at org
 * {@code "*"} and would otherwise be silently excluded (same precedent as
 * {@code SelectorOrgFilter#buildOrganizationPredicate}).
 *
 * <p>TicketBAI chains invoice numbers with a single, scope-wide counter — {@code
 * com.smf.ticketbai}'s {@code SynchronizeUtils.getPreviousInvoiceByDocStatus(num, docStatus)}
 * looks up the previous invoice by {@code Invoice.PROPERTY_TBAISEQUENCE == num} with no
 * {@link DocumentType} filter at all. Giving each Document Type its own independent
 * {@link Sequence} starting at 1 would let two invoices of different types share the same
 * {@code TbaiSequence} number and collide, breaking that chain lookup. So exactly one
 * {@link Sequence} is created per scope (organization) and reused — never one per Document Type.
 *
 * <p>Idempotency rule (covers both re-save and delete-and-recreate cases, now scoped to the
 * whole batch rather than per Document Type): if ANY qualifying Document Type in scope already
 * has a {@code tbaiAdSequence}, that instance IS the shared sequence and is reused as-is for
 * every other Document Type in scope that lacks one. A brand-new {@link Sequence} is only
 * created when NONE of them has one yet. Either way, a Document Type that already has a
 * {@code tbaiAdSequence} is left untouched — never overwritten, never duplicated.
 *
 * <p>Best-effort, secondary side effect: the config record has already been saved by the time
 * {@link #afterHandle(NeoContext)} runs, so a failure here must never fail the parent request.
 * Any exception is logged and swallowed, and {@code null} is returned so the original CRUD
 * response is kept untouched — mirrors {@code VerifactuConfigReadyHandler} and
 * {@code ChartOfAccountsHandler#afterHandle}.
 *
 * <h3>POST/PUT afterHandle — twice-a-day auto-send schedule (ETP-5117)</h3>
 * <p>After a successful create-or-update that leaves the config active, also ensures a
 * scheduled {@code AD_Process_Request} exists for the TicketBAI sending process, scoped to the
 * config's own client + organization (TBAI configs are per-organization) — see
 * {@link SiiTbaiAutoSendScheduleService} for the full scope/idempotency reasoning. Deliberately
 * checked independently of {@link #ensureTbaiSequences} (which runs regardless of the config's
 * active flag, since chaining sequences must survive a pause/resume): the schedule is only
 * created while the config is genuinely active. GO-only by design: this never runs for a config
 * saved through Classic UI.
 *
 * <h3>PUT/DELETE afterHandle — auto-send schedule cleanup on deactivation (ETP-5117 follow-up)</h3>
 * <p>The schedule created above must not outlive the config it was created for. When the incoming
 * PUT explicitly sets {@code active=false}, or when GO's UI issues a genuine {@code DELETE} (its
 * "Eliminar" action calls {@code apiFetch(..., { method: 'DELETE' })} — it never sends a PUT with
 * {@code active: false} — see {@code DetailView.jsx}), {@link #afterHandle} now also calls {@link
 * #unscheduleAutoSendForDeactivatedConfig} — covering both outcomes of {@link #smartDeactivate}:
 * the record deleted outright (no invoices were ever sent through it) or deactivated by the
 * default-CRUD fall-through (invoices exist, audit trail preserved). For the deactivating-PUT
 * case this is deliberately additive, not a replacement branch: {@link #ensureTbaiSequences}
 * still runs unconditionally exactly as before (chaining sequences must survive a pause/resume,
 * unaffected by this cleanup), and {@link #scheduleAutoSendIfActive} is simply left to no-op on
 * its own (the config is no longer active by the time it runs). For a genuine DELETE, though,
 * {@link #afterHandle} returns immediately after the unschedule call — the config record itself
 * is going away, so there is no scope left for {@link #ensureTbaiSequences} to (re)assign
 * chaining sequences to; a DELETE needs no {@link #isExplicitlyDeactivating} check the way PUT
 * does, since the method itself is unconditionally "this config is going away." Without the new
 * call, the schedule would keep firing the TicketBAI sending process twice a day for an
 * organization whose fiscal config no longer exists or is no longer active. See {@link
 * #unscheduleAutoSendForDeactivatedConfig} for how client/organization is resolved even though
 * the record may already be gone by the time this hook runs.
 *
 * <p><b>Known gap, not fixed here (ETP-5117):</b> {@link AbstractSmartDeactivationHandler#handle}
 * only intercepts {@code PUT} — a genuine {@code DELETE} never reaches {@link #smartDeactivate}
 * and falls straight through to NEO's default hard-delete CRUD, regardless of whether invoices
 * were ever sent through the config. This is a separate, likely pre-existing (ETP-4785-era) gap
 * in the pre-hook; this fix only guarantees the auto-send schedule always gets cleaned up in
 * {@link #afterHandle} once the DELETE completes.
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2 (this qualifier silently stops being
 * discovered if a scope annotation such as {@code @ApplicationScoped} is added — regressed
 * before in ETP-4244).
 */
@Named("tbai-config-sequence-handler")
public class TbaiConfigSequenceHandler extends AbstractSmartDeactivationHandler {

  private static final Logger log = LogManager.getLogger(TbaiConfigSequenceHandler.class);

  private static final String METHOD_POST = "POST";

  private static final String METHOD_DELETE = "DELETE";

  private static final String AUTO_SEND_SCHEDULE_DESCRIPTION =
      "Automatic TicketBAI invoice sending (Etendo GO)";

  private final SiiTbaiAutoSendScheduleService scheduleService = new SiiTbaiAutoSendScheduleService();

  /**
   * DB table name that identifies invoice {@link DocumentType}s. All invoice-category doc types
   * (sales invoice {@code ARI}, purchase invoice {@code API}, and their credit notes {@code ARC}
   * / {@code APC}) are backed by this same table, so filtering on it naturally includes all of
   * them without a hardcoded {@code documentCategory} list.
   */
  private static final String INVOICE_TABLE_NAME = "C_Invoice";

  private static final String TABLE_ALIAS = "tbl";

  private static final String SEQUENCE_NAME_PREFIX = "TBAI - ";
  private static final String SEQUENCE_PREFIX = "TBAI-";
  private static final long SEQUENCE_START_NO = 1L;
  private static final long SEQUENCE_INCREMENT_BY = 1L;

  /**
   * Decides between deleting the config record (no invoices sent through it) and letting the
   * default CRUD deactivate it (invoices exist — audit trail must be preserved).
   */
  @Override
  protected NeoResponse smartDeactivate(String recordId) throws JSONException {
    TbaiConfig config = OBDal.getInstance().get(TbaiConfig.class, recordId);
    if (config == null) {
      return null;
    }

    Date adoptionDate = config.getTbaisystemdate();
    String orgId = config.getOrganization().getId();

    // If the config never entered the fiscal system (adoption date not set), delete directly.
    if (adoptionDate == null) {
      OBDal.getInstance().remove(config);
      OBDal.getInstance().flush();
      log.info("TbaiConfigSequenceHandler: deleted unused config record {} (no adoption date)",
          recordId);
      return deletedResponse();
    }

    if (hasTbaiInvoicesSince(orgId, adoptionDate)) {
      // Invoices exist — let default CRUD deactivate (preserve audit trail).
      return null;
    }

    OBDal.getInstance().remove(config);
    OBDal.getInstance().flush();
    log.info("TbaiConfigSequenceHandler: deleted unused config record {} (no invoices sent)",
        recordId);
    return deletedResponse();
  }

  /**
   * Returns {@code true} if at least one invoice was sent through TicketBAI for the given
   * org with an invoice date on or after {@code since}.
   */
  private boolean hasTbaiInvoicesSince(String orgId, Date since) {
    OBCriteria<Invoice> crit = OBDal.getInstance().createCriteria(Invoice.class);
    crit.add(Restrictions.eq(Invoice.PROPERTY_ORGANIZATION + ".id", orgId));
    crit.add(Restrictions.eq(Invoice.PROPERTY_TBAIISSENT, Boolean.TRUE));
    crit.add(Restrictions.ge(Invoice.PROPERTY_INVOICEDATE, since));
    crit.setProjection(Projections.rowCount());
    Number count = (Number) crit.uniqueResult();
    return count != null && count.longValue() > 0;
  }

  /**
   * Post-hook: on a successful create/update of the TBAI config, ensures every invoice Document
   * Type in the config's organization tree has a TBAI chaining sequence assigned, and keeps the
   * twice-a-day auto-send schedule in sync with the config's active flag (create/activate on a
   * genuinely active save, cleanup on an explicit deactivation or a genuine DELETE) — see class
   * Javadoc. A genuine DELETE skips sequence assignment entirely (the config record is going
   * away) and only runs the schedule cleanup.
   *
   * @return always {@code null} — this is a side effect, never a response replacement.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    boolean isPost = METHOD_POST.equalsIgnoreCase(method);
    boolean isPut = METHOD_PUT.equalsIgnoreCase(method);
    boolean isDelete = METHOD_DELETE.equalsIgnoreCase(method);
    if (!isPost && !isPut && !isDelete) {
      return null;
    }
    if (isDelete || (isPut && isExplicitlyDeactivating(context.getRequestBody()))) {
      // Additive, not a replacement branch for the deactivating-PUT case — see class Javadoc
      // "schedule cleanup on deactivation" section. ensureTbaiSequences/scheduleAutoSendIfActive
      // below still run exactly as before for that request; this call only removes a schedule
      // that is no longer wanted. For a genuine DELETE, though, the config record itself is
      // going away — there is no scope left to (re)assign chaining sequences to, so we return
      // immediately afterward instead, same as the "record already deleted" guard below.
      unscheduleAutoSendForDeactivatedConfig(context);
      if (isDelete) {
        return null;
      }
    }
    // If handle() already deleted the record (smart deactivation), skip sequence assignment.
    NeoResponse preResult = context.getPreviousResult();
    if (preResult != null && preResult.getBody() != null
        && preResult.getBody().optBoolean("deleted", false)) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        ensureTbaiSequences(context, method);
        scheduleAutoSendIfActive(context, method);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("TbaiConfigSequenceHandler.afterHandle error: {}", e.getMessage(), e);
    }
    return null;
  }

  /**
   * Removes the auto-send schedule (if any) for a config that a deactivating PUT or a genuine
   * DELETE just deleted or deactivated (ETP-5117 follow-up) — see the "auto-send schedule
   * cleanup on deactivation" class Javadoc section.
   *
   * <p><b>Client/organization resolution.</b> Reuses {@link #resolveConfigScope}, always passing
   * {@link #METHOD_PUT} regardless of the actual incoming method (a deliberate, pre-existing
   * choice, not new to the DELETE case): it forces {@link #resolveRecordId} down its
   * {@code context.getRecordId()} branch instead of its POST-response-parsing branch, and {@link
   * NeoContext#getRecordId()} is populated from the URL path the same way for every HTTP method —
   * including a genuine DELETE — so this is safe to reuse unchanged here. That gives exactly the
   * fallback needed: try to load the {@link TbaiConfig} record by id first (a primary-key lookup
   * is <b>not</b> filtered by {@code Active}, so it still succeeds for the "deactivated by default
   * CRUD, not deleted" case and returns the config's own, most-precise organization), and only
   * fall back to {@code context.getObContext()}'s current client/organization when the record
   * cannot be loaded — the case where {@link #smartDeactivate} deleted it outright (deactivating
   * PUT, no invoices were ever sent through it) or where a genuine DELETE has already removed the
   * record via default CRUD by the time this hook runs. That fallback relies on TBAI configs
   * being per-organization records normally edited from within that same organization's context,
   * the same assumption {@link #resolveConfigScope} already documents for its own defensive use
   * on the schedule-creation side.
   */
  private void unscheduleAutoSendForDeactivatedConfig(NeoContext context) {
    try {
      OBContext.setAdminMode(true);
      try {
        ConfigScope scope = resolveConfigScope(context, METHOD_PUT);
        if (scope == null) {
          log.debug("TbaiConfigSequenceHandler: could not resolve client/organization for the "
              + "deactivated TBAI config; skipping auto-send schedule cleanup");
          return;
        }
        scheduleService.unscheduleAutoSend(scope.client.getId(), scope.organization.getId(),
            SiiTbaiAutoSendScheduleService.TBAI_PROCESS_SEARCH_KEY);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      // Non-fatal — the deactivate/delete already committed; log and continue.
      log.warn("TbaiConfigSequenceHandler.afterHandle: could not unschedule auto-send for "
          + "deactivated config: {}", e.getMessage(), e);
    }
  }

  /**
   * Ensures the twice-a-day auto-send schedule exists (and attempts to activate it) for the
   * saved config's client/organization, but only when the config is actually active — a config
   * created inactive, or a deactivating PUT that fell through to default CRUD (see class
   * Javadoc), must not get a schedule.
   */
  private void scheduleAutoSendIfActive(NeoContext context, String method) {
    String recordId = resolveRecordId(context, method);
    if (StringUtils.isBlank(recordId)) {
      return;
    }
    TbaiConfig config = OBDal.getInstance().get(TbaiConfig.class, recordId);
    if (config == null || !Boolean.TRUE.equals(config.isActive())
        || config.getClient() == null || config.getOrganization() == null) {
      return;
    }
    OBContext obContext = context.getObContext();
    if (obContext == null || obContext.getUser() == null || obContext.getRole() == null) {
      return;
    }
    String requestId = scheduleService.ensureAutoSendSchedule(config.getClient().getId(),
        config.getOrganization().getId(), obContext.getUser().getId(), obContext.getRole().getId(),
        SiiTbaiAutoSendScheduleService.TBAI_PROCESS_SEARCH_KEY, AUTO_SEND_SCHEDULE_DESCRIPTION);
    scheduleService.activateSchedule(requestId);
  }

  /**
   * Resolves the saved config's client/organization, finds every invoice Document Type in that
   * organization's natural tree, and ensures all of them share the same TBAI chaining
   * {@link Sequence} — reusing one already assigned in scope, or creating exactly one new
   * instance when none exists yet.
   */
  private void ensureTbaiSequences(NeoContext context, String method) {
    ConfigScope scope = resolveConfigScope(context, method);
    if (scope == null) {
      log.debug("TbaiConfigSequenceHandler: could not resolve client/organization "
          + "for the saved TBAI config; skipping");
      return;
    }

    List<DocumentType> invoiceDocTypes = findInvoiceDocumentTypes(scope.client, scope.organization.getId());
    if (invoiceDocTypes.isEmpty()) {
      log.debug("TbaiConfigSequenceHandler: no invoice Document Types found for org={}",
          scope.organization.getId());
      return;
    }

    Sequence sharedSequence = findExistingSharedSequence(invoiceDocTypes);
    if (sharedSequence == null) {
      sharedSequence = createSequence(scope.client, scope.organization);
    }

    for (DocumentType docType : invoiceDocTypes) {
      if (docType.getTbaiAdSequence() != null) {
        continue; // idempotent: never overwrite an existing chaining sequence assignment
      }
      docType.setTbaiAdSequence(sharedSequence);
      OBDal.getInstance().save(docType);
    }

    OBDal.getInstance().flush();
  }

  /**
   * Returns the {@link Sequence} already assigned to one of {@code docTypes} — the shared
   * scope-wide chaining sequence, if any exists — or {@code null} when none of them has one yet.
   */
  private Sequence findExistingSharedSequence(List<DocumentType> docTypes) {
    for (DocumentType docType : docTypes) {
      if (docType.getTbaiAdSequence() != null) {
        return docType.getTbaiAdSequence();
      }
    }
    return null;
  }

  /**
   * Resolves the client/organization the sequence assignment must be scoped to — always the
   * org the TBAI config record was actually saved for, not necessarily the request's current
   * session organization (they can differ, e.g. when saving from an org above/below the
   * session org). Falls back to the current {@link OBContext} client/organization only when the
   * saved record cannot be loaded (defensive; should not normally happen).
   */
  private ConfigScope resolveConfigScope(NeoContext context, String method) {
    String recordId = resolveRecordId(context, method);
    if (StringUtils.isNotBlank(recordId)) {
      TbaiConfig config = OBDal.getInstance().get(TbaiConfig.class, recordId);
      if (config != null && config.getClient() != null && config.getOrganization() != null) {
        return new ConfigScope(config.getClient(), config.getOrganization());
      }
      log.debug("TbaiConfigSequenceHandler: TbaiConfig record not found for id={}", recordId);
    }

    OBContext obContext = context.getObContext();
    if (obContext != null && obContext.getCurrentClient() != null
        && obContext.getCurrentOrganization() != null) {
      return new ConfigScope(obContext.getCurrentClient(), obContext.getCurrentOrganization());
    }
    return null;
  }

  /**
   * Resolves the id of the just-saved TBAI config record. For PUT the id comes straight from
   * the URL ({@link NeoContext#getRecordId()}). For POST there is no id in the create URL, so
   * it is read from the just-committed CRUD response envelope ({@code response.data[0].id} or,
   * defensively, {@code response.data.id} for a single-object envelope) — the same recovery
   * pattern used by {@code VerifactuConfigReadyHandler#resolveRecordId}.
   */
  private String resolveRecordId(NeoContext context, String method) {
    if (METHOD_PUT.equalsIgnoreCase(method)) {
      return context.getRecordId();
    }

    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return null;
    }
    JSONObject response = prev.getBody().optJSONObject("response");
    if (response == null) {
      return null;
    }
    JSONArray dataArr = response.optJSONArray("data");
    if (dataArr != null && dataArr.length() > 0) {
      JSONObject first = dataArr.optJSONObject(0);
      return first == null ? null : StringUtils.trimToNull(first.optString("id", null));
    }
    JSONObject dataObj = response.optJSONObject("data");
    return dataObj == null ? null : StringUtils.trimToNull(dataObj.optString("id", null));
  }

  /**
   * Finds every active invoice {@link DocumentType} — i.e. whose backing table
   * ({@link DocumentType#PROPERTY_TABLE}) is {@link #INVOICE_TABLE_NAME} — belonging to
   * {@code client} whose organization is in the natural org tree of {@code organizationId}
   * (ancestors + descendants, via
   * {@link org.openbravo.dal.security.OrganizationStructureProvider#getNaturalTree}, the same
   * helper used by {@code SelectorOrgFilter}) — plus organization {@code "0"} (the {@code "*"}
   * org), added explicitly since {@code getNaturalTree} does not include it and Document Types are
   * very commonly defined at org {@code "*"} (same precedent as
   * {@code SelectorOrgFilter#buildOrganizationPredicate}). Filtering by table (rather than
   * {@code documentCategory}) naturally covers sales/purchase invoices AND their credit notes,
   * since all of them are backed by the same {@code C_Invoice} table.
   */
  @SuppressWarnings("unchecked")
  private List<DocumentType> findInvoiceDocumentTypes(Client client, String organizationId) {
    Set<String> orgIds = new HashSet<>(
        OBContext.getOBContext().getOrganizationStructureProvider().getNaturalTree(organizationId));
    orgIds.add("0");

    OBCriteria<DocumentType> crit = OBDal.getInstance().createCriteria(DocumentType.class);
    crit.add(Restrictions.eq(DocumentType.PROPERTY_CLIENT + ".id", client.getId()));
    crit.add(Restrictions.in(DocumentType.PROPERTY_ORGANIZATION + ".id", orgIds));
    crit.createAlias(DocumentType.PROPERTY_TABLE, TABLE_ALIAS);
    crit.add(Restrictions.eq(TABLE_ALIAS + "." + Table.PROPERTY_DBTABLENAME, INVOICE_TABLE_NAME));
    crit.add(Restrictions.eq(DocumentType.PROPERTY_ACTIVE, true));
    return crit.list();
  }

  /**
   * Creates the single new auto-numbering {@link Sequence} shared by every invoice Document
   * Type in {@code organization}'s scope — never one per Document Type, since TicketBAI chains
   * invoice numbers with a single scope-wide counter (see class Javadoc). Copies the field
   * values of {@code CreateDocTypesStep#createSequence}, except the name now reflects the
   * organization scope rather than a single Document Type.
   */
  private Sequence createSequence(Client client, Organization organization) {
    Sequence sequence = OBProvider.getInstance().get(Sequence.class);
    sequence.setNewOBObject(true);
    sequence.setClient(client);
    sequence.setOrganization(organization);
    sequence.setName(SEQUENCE_NAME_PREFIX + organization.getName());
    sequence.setPrefix(SEQUENCE_PREFIX);
    sequence.setStartingNo(SEQUENCE_START_NO);
    sequence.setNextAssignedNumber(SEQUENCE_START_NO);
    sequence.setIncrementBy(SEQUENCE_INCREMENT_BY);
    sequence.setAutoNumbering(true);
    OBDal.getInstance().save(sequence);
    return sequence;
  }

  /** Immutable holder for the client/organization the TBAI config was saved for. */
  private static final class ConfigScope {
    private final Client client;
    private final Organization organization;

    private ConfigScope(Client client, Organization organization) {
      this.client = client;
      this.organization = organization;
    }
  }
}
