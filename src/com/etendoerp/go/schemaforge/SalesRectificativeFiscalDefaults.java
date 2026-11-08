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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.utils.SIIUtils;

/**
 * <h2>MERGE CONFLICT NOTICE — read this before resolving</h2>
 *
 * This class also exists on {@code origin/develop} as of ETP-5747 (PR #1555, commit
 * {@code d07757ea2}), merged there AFTER this branch last pulled develop
 * ({@code 98564f7d0}). The two versions diverge in a way that is NOT just wording: the
 * ETP-5747/develop version DOES copy the document type's {@code em_etvfac_inv_type} onto the
 * invoice in {@code applyVerifactu}; THIS branch's version deliberately stopped doing that
 * (ETP-5745 CP-20 — see "What it does not do" below for why: copying ANY code, even a
 * human-configured per-DocType one, silently picks the user's rectification reason for them).
 *
 * <p><b>When this conflict appears, keep THIS branch's {@code applyVerifactu} and its class/method
 * javadoc — the one WITHOUT the DocType-to-invoice {@code etvfacInvType} copy.</b> Discard
 * develop's copy-from-DocType half of the method. Everything else that is a genuine merge (new
 * unrelated methods, SII/TicketBAI gating additions from either side, etc.) should merge normally;
 * only the {@code etvfacInvType} copy itself is the one piece to resolve toward THIS branch's side.
 *
 * <h2>Server-side defaults of the STRUCTURAL TicketBAI and VERI*FACTU rectificative fields</h2>
 *
 * Applied after every CRUD write of a SALES invoice's header (originally ETP-5747, brought into
 * this branch and adjusted for ETP-5745 CP-13/CP-14 — see "What it does not do" below).
 *
 * <h2>Why on the server</h2>
 *
 * These fields were only ever filled by the header callout, and only when its trigger was
 * {@code transactionDocument} ({@link InvoiceCalloutHelper#applyRectificativeFieldsFromDocType}).
 * An invoice that reaches the database with its rectificative document type already set — created
 * through "Importar desde factura origen", by {@code etendo_create} / {@code /batch}, or with the
 * type picked before the callout could answer — never had the document type "change", so the
 * fields stayed at their AD defaults for good. A client-side effect would have the same blind spot
 * on every channel that is not the SPA, so the defaults are applied where every channel meets: the
 * header's own post-hook.
 *
 * <h2>What it writes</h2>
 *
 * Only on a DRAFT, unprocessed sales invoice whose document type is flagged rectificative
 * ({@link RectificativeSupport#isRectificative(DocumentType)}), and never over a value already
 * chosen — except a non-rectificative {@code F*} VERI*FACTU code, which is replaced by the
 * document type's {@code R*} code:
 * <ul>
 *   <li><b>TicketBAI</b> — only when the invoice's organization has an active {@code tbai_config}:
 *   {@code tbaiIsreverseinvoice = Y}, {@code tbaiReverseinvoicetype = I} (Por diferencias) when
 *   blank.</li>
 *   <li><b>VERI*FACTU</b> — only when the legal entity has an active VERI*FACTU config that is in
 *   the system. {@code etvfacInvType} itself is NEVER written here (ETP-5745 CP-20 — see "What it
 *   does not do" below). Only once the invoice ALREADY carries an {@code R1}-{@code R5} code the
 *   user picked by hand, and {@code etvfacReverseinvtype} is blank, is the reverse type defaulted:
 *   the document type's reverse type, or {@code I} — the value the SIF tab always writes for it.</li>
 * </ul>
 *
 * <h2>What it does not do (ETP-5745 CP-14: no "motivo"/code is ever auto-picked)</h2>
 *
 * <ul>
 *   <li><b>SII is not defaulted here.</b> {@code EM_AEATSII_C_INV_RECT_CHK} requires
 *   {@code aeatsiiMotivoRectif} as soon as the SII type is {@code R}, and the motivo is a user
 *   choice. {@code aeatsiiClaveTipo}/{@code aeatsiiTipoRectif} ARE corrected server-side, but at
 *   completion time, by {@link RectificationFiscalCodeGuard} — not here.</li>
 *   <li><b>TicketBAI's {@code tbaiReverseinvoicecode} is NOT defaulted</b> — deliberately reverted
 *   in ETP-5745 CP-14. The original ETP-5747 version of this class auto-picked {@code R4} ("Resto")
 *   for it, which is exactly the kind of fiscal classification choice (why the invoice is being
 *   corrected) that must stay a manual, informed decision — the same reasoning that already kept
 *   SII's {@code aeatsiiMotivoRectif} out of this class. The field is left blank; the dedicated
 *   completion-time guard ({@link RectificationFiscalCodeGuard}) blocks completion when it is still
 *   blank, naming the missing system in its message.</li>
 *   <li>It never clears a value: a non-rectificative invoice is left exactly as it is.</li>
 *   <li>Purchase invoices are out of scope: VERI*FACTU never sends a purchase, and TicketBAI's
 *   rectificative fields are sales-only (see {@link InvoiceCalloutHelper#applyRectificativeFieldsFromDocType}).</li>
 * </ul>
 */
final class SalesRectificativeFiscalDefaults {

  private static final Logger log = LogManager.getLogger(SalesRectificativeFiscalDefaults.class);

  static final String FIELD_TBAI_IS_REVERSE = "tbaiIsreverseinvoice";
  static final String FIELD_TBAI_REVERSE_TYPE = "tbaiReverseinvoicetype";
  static final String FIELD_VF_REVERSE_TYPE = "etvfacReverseinvtype";

  /** TicketBAI / VERI*FACTU "Por diferencias" reverse type. */
  static final String REVERSE_TYPE_BY_DIFFERENCES = "I";

  private static final String DRAFT = "DR";
  private static final String RECTIFICATIVE_PREFIX = "R";

  static final String TBAI_ACTIVE_SQL =
      "SELECT 1 FROM tbai_config WHERE ad_org_id = ? AND isactive = 'Y' LIMIT 1";

  static final String VERIFACTU_ACTIVE_SQL =
      "SELECT 1 FROM etvfac_verifactu_config WHERE ad_org_id = ad_get_org_le_bu(?, 'LE')"
          + " AND isactive = 'Y' AND in_vfactu_system IS NOT NULL LIMIT 1";

  private SalesRectificativeFiscalDefaults() {
  }

  /**
   * Applies the defaults to the invoice and flushes them when anything changed.
   *
   * <p>Never throws a Java exception: a failure is logged and answered as "nothing applied", so
   * the save the user already made is not turned into an error by a default it did not ask for.
   *
   * @param invoiceId the sales invoice just written
   * @return the response keys and values that were written (empty when nothing changed)
   */
  static Map<String, Object> apply(String invoiceId) {
    if (StringUtils.isBlank(invoiceId)) {
      return Collections.emptyMap();
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
        Map<String, Object> applied = computeAndSet(invoice);
        if (!applied.isEmpty()) {
          OBDal.getInstance().save(invoice);
          OBDal.getInstance().flush();
        }
        return applied;
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("[ETP-5745] Could not apply rectificative fiscal defaults to invoice {}: {}",
          invoiceId, e.getMessage());
      return Collections.emptyMap();
    }
  }

  /**
   * Sets the missing defaults on {@code invoice} (no save, no flush).
   *
   * @param invoice the invoice, may be {@code null}
   * @return the response keys and values set, in a stable order
   */
  static Map<String, Object> computeAndSet(Invoice invoice) {
    Map<String, Object> applied = new LinkedHashMap<>();
    if (!isEligible(invoice)) {
      return applied;
    }
    DocumentType docType = invoice.getTransactionDocument();
    String orgId = invoice.getOrganization() != null ? invoice.getOrganization().getId() : null;
    if (orgId == null) {
      return applied;
    }
    if (tbaiConfigActive(orgId)) {
      applyTbai(invoice, applied);
    }
    if (verifactuConfigActive(orgId)) {
      applyVerifactu(invoice, docType, applied);
    }
    return applied;
  }

  private static boolean isEligible(Invoice invoice) {
    return invoice != null
        && Boolean.TRUE.equals(invoice.isSalesTransaction())
        && DRAFT.equals(invoice.getDocumentStatus())
        && !Boolean.TRUE.equals(invoice.isProcessed())
        && RectificativeSupport.isRectificative(invoice.getTransactionDocument());
  }

  /**
   * TicketBAI: {@code tbaiIsreverseinvoice} is ENFORCED on a rectificative draft (set to
   * {@code Y} whatever it held, as the doc-type callout does — a rectificative is never a
   * non-reverse TicketBAI invoice); the reverse type is only DEFAULTED when blank.
   * {@code tbaiReverseinvoicecode} is deliberately NEVER touched here — see the class javadoc's
   * "What it does not do" section (ETP-5745 CP-14).
   */
  private static void applyTbai(Invoice invoice, Map<String, Object> applied) {
    if (!Boolean.TRUE.equals(invoice.isTbaiIsreverseinvoice())) {
      invoice.setTbaiIsreverseinvoice(true);
      applied.put(FIELD_TBAI_IS_REVERSE, true);
    }
    if (StringUtils.isBlank(invoice.getTbaiReverseinvoicetype())) {
      invoice.setTbaiReverseinvoicetype(REVERSE_TYPE_BY_DIFFERENCES);
      applied.put(FIELD_TBAI_REVERSE_TYPE, REVERSE_TYPE_BY_DIFFERENCES);
    }
  }

  /**
   * ETP-5745 CP-20: no longer copies the DocType's own configured {@code em_etvfac_inv_type}
   * onto the invoice — reverted, same reasoning as {@code InvoiceCalloutHelper
   * #applyVerifactuInvTypeFromDocType}'s javadoc: VERI*FACTU's {@code R1}-{@code R5} codes fold
   * "this is a correction" and "for this reason" into one value, so copying ANY code — even a
   * human-configured per-DocType one — still picks the user's reason for THIS invoice on their
   * behalf. {@code etvfacInvType} is left completely alone here; only the REVERSE type is
   * defaulted, and only once the user has ALREADY picked an {@code R1}-{@code R5} code by hand.
   */
  private static void applyVerifactu(Invoice invoice, DocumentType docType,
      Map<String, Object> applied) {
    String current = StringUtils.trimToNull(invoice.getEtvfacInvType());
    if (isRectificativeCode(current) && StringUtils.isBlank(invoice.getEtvfacReverseinvtype())) {
      String reverseType = StringUtils.defaultIfBlank(docType.getEtvfacReverseInvType(),
          REVERSE_TYPE_BY_DIFFERENCES);
      invoice.setEtvfacReverseinvtype(reverseType);
      applied.put(FIELD_VF_REVERSE_TYPE, reverseType);
    }
  }

  private static boolean isRectificativeCode(String code) {
    return code != null && code.startsWith(RECTIFICATIVE_PREFIX);
  }

  /**
   * Whether the organization has an active TicketBAI configuration. Never throws a Java
   * exception: a failure answers {@code false} (no default is better than a default for a system
   * the org may not use).
   */
  static boolean tbaiConfigActive(String orgId) {
    return configExists(TBAI_ACTIVE_SQL, orgId);
  }

  /**
   * Whether the organization is actually enrolled in the SII ({@code aeatsii_config.insiisystem}
   * via {@code AEATSIIConfig#isAcogidaAlSII}) — ETP-5745 CP-24. Same DAL-based lookup
   * {@code AbstractInvoiceHeaderHandler}/{@code InvoiceCalloutHelper}'s own SII authorization
   * callout and {@code NeoBackgroundDefaultsService#isOrganizationInSiiSystem} already use, so SII
   * stays consistent with itself across the module. Unlike TicketBAI/VERI*FACTU (plain
   * {@code tbai_config}/{@code etvfac_verifactu_config} tables, queried by raw SQL), SII has no
   * such row-per-org config table in this module's reach — its config lives in the SII module's
   * own DAL entity, keyed off the invoice's legal entity, hence the DAL lookup instead of
   * {@link #configExists}.
   *
   * <p>Never throws: a failure to resolve the org or its config answers {@code false} — no SII
   * default is better than one stamped for an org that was never actually registered.
   */
  static boolean siiConfigActive(String orgId) {
    if (StringUtils.isBlank(orgId)) {
      return false;
    }
    try {
      Organization org = OBDal.getInstance().get(Organization.class, orgId);
      if (org == null) {
        return false;
      }
      AEATSIIConfig config = SIIUtils.getSiiConfigFromOrg(org);
      return config != null && Boolean.TRUE.equals(config.isAcogidaAlSII());
    } catch (Exception e) {
      log.debug("[ETP-5745] SII config lookup failed for org {}: {}", orgId, e.getMessage());
      return false;
    }
  }

  /**
   * Whether the invoice's legal entity has an active VERI*FACTU configuration in the system
   * ({@code etvfac_verifactu_config}, via {@code ad_get_org_le_bu}) — ETP-5745 Warning 1. Mirrors
   * {@link #tbaiConfigActive}/{@link #siiConfigActive} so every per-system gate in this class and
   * in {@code InvoiceCalloutHelper} resolves "is this fiscal system active for this org" the same
   * way. Never throws: a failure answers {@code false}.
   */
  static boolean verifactuConfigActive(String orgId) {
    return StringUtils.isNotBlank(orgId) && configExists(VERIFACTU_ACTIVE_SQL, orgId);
  }

  /**
   * Whether the config query answers a row for the organization. Never throws a Java exception:
   * a failure answers {@code false} — no default is better than a default for a system the org
   * may not use.
   */
  static boolean configExists(String sql, String orgId) {
    try {
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, orgId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next();
        }
      }
    } catch (Exception e) {
      log.debug("[ETP-5745] fiscal config lookup failed for org {}: {}", orgId, e.getMessage());
      return false;
    }
  }
}
