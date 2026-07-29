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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
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

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.RectificativeDocTypeFlagService;
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
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2 (this qualifier silently stops being
 * discovered if a scope annotation such as {@code @ApplicationScoped} is added — regressed
 * before in ETP-4244).
 */
@Named("tbai-config-sequence-handler")
public class TbaiConfigSequenceHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(TbaiConfigSequenceHandler.class);

  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";

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

  private RectificativeDocTypeFlagService rectificativeService = new RectificativeDocTypeFlagService();

  /** No pre-hook behavior: this handler only reacts after the config record is persisted. */
  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  /**
   * Post-hook: on a successful create/update of the TBAI config, ensures every invoice Document
   * Type in the config's organization tree has a TBAI chaining sequence assigned.
   *
   * @return always {@code null} — this is a side effect, never a response replacement.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = context.getHttpMethod();
    if (!METHOD_POST.equalsIgnoreCase(method) && !METHOD_PUT.equalsIgnoreCase(method)) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        ensureTbaiSequences(context, method);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("TbaiConfigSequenceHandler.afterHandle error: {}", e.getMessage(), e);
    }
    // ETP-4536: flag the client's rectificative document types and sequences (own admin-mode /
    // error handling). Independent of the TBAI chaining side effect above.
    return rectificativeService.applyAfterConfigSave(context);
  }

  /** Package-private seam so unit tests can inject a mocked rectificative service. */
  void setRectificativeService(RectificativeDocTypeFlagService rectificativeService) {
    this.rectificativeService = rectificativeService;
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
