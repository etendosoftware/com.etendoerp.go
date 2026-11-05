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
import org.openbravo.model.common.invoice.Invoice;

/**
 * Server-side defaults of the TicketBAI and VERI*FACTU rectificative fields of a SALES invoice,
 * applied after every CRUD write of its header (ETP-5747).
 *
 * <h2>Why on the server</h2>
 *
 * Until ETP-5747 these fields were only ever filled by the header callout, and only when its
 * trigger was {@code transactionDocument} ({@link InvoiceCalloutHelper#applyRectificativeFieldsFromDocType},
 * {@link InvoiceCalloutHelper#applyVerifactuInvTypeFromDocType}). An invoice that reaches the
 * database with its rectificative document type already set — created through
 * "Importar desde factura origen", by {@code etendo_create} / {@code /batch}, or with the type
 * picked before the callout could answer — never had the document type "change", so the fields
 * stayed at their AD defaults for good: TicketBAI with no reverse-invoice flag/code (its XML
 * generator then fails on the {@code null} code with {@code TBAI_SetKey}), VERI*FACTU at
 * {@code F1}. A client-side effect would have the same blind spot on every channel that is not
 * the SPA, so the defaults are applied where every channel meets: the header's own post-hook.
 *
 * <h2>What it writes</h2>
 *
 * Only on a DRAFT, unprocessed sales invoice whose document type is flagged rectificative
 * ({@link RectificativeSupport#isRectificative(DocumentType)}), and never over a value already
 * chosen:
 * <ul>
 *   <li><b>TicketBAI</b> — only when the invoice's organization has an active {@code tbai_config}:
 *   {@code tbaiIsreverseinvoice = Y}, {@code tbaiReverseinvoicetype = I} (Por diferencias) when
 *   blank, {@code tbaiReverseinvoicecode = R4} (Resto) when blank — the exact values the
 *   doc-type callout injects.</li>
 *   <li><b>VERI*FACTU</b> — only when the legal entity has an active VERI*FACTU config that is in
 *   the system (the same gate as {@code ETVFAC_C_INVOICE_SET_VERIFACTU}, which copies these values
 *   from the document type at completion): {@code etvfacInvType} = the document type's
 *   {@code em_etvfac_inv_type} when the invoice's value is blank or a non-rectificative
 *   {@code F*} code (the AD default {@code F1}); an {@code R*} code the user picked is kept. Then,
 *   when the type is {@code R*} and {@code etvfacReverseinvtype} is blank, the document type's
 *   reverse type, or {@code I} — the value the SIF tab always writes for it.</li>
 * </ul>
 *
 * <h2>What it does not do</h2>
 *
 * <ul>
 *   <li><b>SII is not defaulted here.</b> {@code EM_AEATSII_C_INV_RECT_CHK} requires
 *   {@code aeatsiiMotivoRectif} as soon as the SII type is {@code R}, and the motivo is a user
 *   choice, so writing {@code R} on its own would make the save fail. The SII type stays a
 *   client-side default ({@code useSifFieldPatcher.js}) driven by {@code isRectificative}, which
 *   the write response now carries.</li>
 *   <li>It never clears a value: a non-rectificative invoice is left exactly as it is.</li>
 *   <li>Purchase invoices are out of scope: VERI*FACTU never sends a purchase, and TicketBAI's
 *   rectificative fields are sales-only (see {@link InvoiceCalloutHelper#applyRectificativeFieldsFromDocType}).</li>
 * </ul>
 */
final class SalesRectificativeFiscalDefaults {

  private static final Logger log = LogManager.getLogger(SalesRectificativeFiscalDefaults.class);

  static final String FIELD_TBAI_IS_REVERSE = "tbaiIsreverseinvoice";
  static final String FIELD_TBAI_REVERSE_TYPE = "tbaiReverseinvoicetype";
  static final String FIELD_TBAI_REVERSE_CODE = "tbaiReverseinvoicecode";
  static final String FIELD_VF_INV_TYPE = AbstractInvoiceHeaderHandler.FIELD_ETVFAC_INV_TYPE;
  static final String FIELD_VF_REVERSE_TYPE = "etvfacReverseinvtype";

  /** TicketBAI / VERI*FACTU "Por diferencias" reverse type. */
  static final String REVERSE_TYPE_BY_DIFFERENCES = "I";
  /** TicketBAI "Factura rectificativa: Resto." reverse-invoice code. */
  static final String TBAI_REVERSE_CODE_REST = "R4";

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
   * <p>Never throws: a failure is logged and answered as "nothing applied", so the save the
   * user already made is not turned into an error by a default it did not ask for.
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
      log.warn("[ETP-5747] Could not apply rectificative fiscal defaults to invoice {}: {}",
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
    if (configExists(TBAI_ACTIVE_SQL, orgId)) {
      applyTbai(invoice, applied);
    }
    if (configExists(VERIFACTU_ACTIVE_SQL, orgId)) {
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

  private static void applyTbai(Invoice invoice, Map<String, Object> applied) {
    if (!Boolean.TRUE.equals(invoice.isTbaiIsreverseinvoice())) {
      invoice.setTbaiIsreverseinvoice(true);
      applied.put(FIELD_TBAI_IS_REVERSE, true);
    }
    if (StringUtils.isBlank(invoice.getTbaiReverseinvoicetype())) {
      invoice.setTbaiReverseinvoicetype(REVERSE_TYPE_BY_DIFFERENCES);
      applied.put(FIELD_TBAI_REVERSE_TYPE, REVERSE_TYPE_BY_DIFFERENCES);
    }
    if (StringUtils.isBlank(invoice.getTbaiReverseinvoicecode())) {
      invoice.setTbaiReverseinvoicecode(TBAI_REVERSE_CODE_REST);
      applied.put(FIELD_TBAI_REVERSE_CODE, TBAI_REVERSE_CODE_REST);
    }
  }

  private static void applyVerifactu(Invoice invoice, DocumentType docType,
      Map<String, Object> applied) {
    String docInvType = StringUtils.trimToNull(docType.getEtvfacInvType());
    String current = StringUtils.trimToNull(invoice.getEtvfacInvType());
    if (isRectificativeCode(docInvType) && !isRectificativeCode(current)) {
      invoice.setEtvfacInvType(docInvType);
      applied.put(FIELD_VF_INV_TYPE, docInvType);
      current = docInvType;
    }
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
   * Whether the config query answers a row for the organization. A failure answers
   * {@code false}: no default is better than a default for a system the org may not use.
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
      log.debug("[ETP-5747] fiscal config lookup failed for org {}: {}", orgId, e.getMessage());
      return false;
    }
  }
}
