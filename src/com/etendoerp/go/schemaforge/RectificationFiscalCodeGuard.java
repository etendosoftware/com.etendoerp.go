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

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Replicates, for the completion step of the NEO/MCP write path, the "a rectificative invoice
 * needs its rectification motive/code" rule that today only ever runs on the classic UI — across
 * ALL THREE fiscal systems that may be active for the invoice's organization (ETP-5745 CP-13/CP-14).
 *
 * <h2>Why</h2>
 *
 * <p>Each fiscal system that tracks WHY an invoice is being corrected needs that choice from a
 * human — it is never guessed:
 * <ul>
 *   <li><b>SII</b> — {@code aeatsiiMotivoRectif} ('Motivo de rectificación').</li>
 *   <li><b>TicketBAI</b> — {@code tbaiReverseinvoicecode} ('Código de rectificación').</li>
 *   <li><b>VERI*FACTU</b> — folded into {@code etvfacInvType} itself: an {@code R1}-{@code R5} code
 *   names both "this is a correction" and "for this reason" in one field, so "the code is missing"
 *   here means the field is still a non-rectificative one ({@code F*}, including the AD default
 *   {@code F1}) or blank.</li>
 * </ul>
 *
 * <p>Nothing auto-fills any of them — see {@link SalesRectificativeFiscalDefaults}'s class javadoc
 * ("What it does not do") for why a prior version of this guard's sibling class auto-picked
 * {@code R4} for TicketBAI, and why that was reverted (ETP-5745 CP-14): it is exactly the same
 * class of fiscal judgement call as SII's motivo, and fabricating it on the caller's behalf is not
 * this module's call to make. The STRUCTURAL fields each system needs (SII's {@code aeatsiiClaveTipo}
 * /{@code aeatsiiTipoRectif}; TicketBAI's {@code tbaiIsreverseinvoice}/{@code tbaiReverseinvoicetype})
 * ARE corrected automatically — those name "this is a correction", not "why" — but the code/motive
 * itself is always a human decision this guard only ever BLOCKS on, never supplies.
 *
 * <p>None of the three systems' validators reach the MCP/REST write path today:
 * {@code useSifFieldPatcher.js} only runs in the browser SPA, {@code FacturasEventHandler} is
 * gated on a classic {@code windowId} request parameter NEO never sends, and the DB CHECK
 * constraints involved (e.g. {@code EM_AEATSII_C_INV_RECT_CHK}) only cover SII and throw a raw,
 * unfriendly violation instead of a validation message naming the field.
 *
 * <h2>What this guard does</h2>
 *
 * <p>Right before completion (mirroring where {@link RectificationTotalGuard} sits), for a SALES
 * invoice (purchase-side corrective support is not implemented yet — see the same ETP-5745 CP-10
 * precedent in {@code useSifFieldPatcher.js}) whose document type is structurally rectificative
 * ({@link RectificativeSupport#isRectificative} — a document-type flag, never a spec/table name):
 * <ol>
 *   <li>checks, independently, whether SII (always, when the structural condition above holds),
 *   TicketBAI (only when the org has an active {@code tbai_config}) and VERI*FACTU (only when the
 *   legal entity has an active, in-system {@code etvfac_verifactu_config}) are each still missing
 *   their own code/motive;</li>
 *   <li>if ONE OR MORE are missing, refuses the completion with a single 422 that names, in order
 *   and only the ones that actually failed, which system(s) still need a choice — never a fixed
 *   "SII y TicketBAI" string;</li>
 *   <li>otherwise, corrects {@code aeatsiiClaveTipo} to {@code "R"} and defaults
 *   {@code aeatsiiTipoRectif} to {@code "I"} when still blank (unchanged from the original CP-13
 *   fix), and lets the completion proceed.</li>
 * </ol>
 *
 * <p><b>ETP-5745 CP-20 — the reverse direction.</b> When the document type is NOT rectificative,
 * this guard still runs, for exactly one reason: clearing a VERI*FACTU {@code R1}-{@code R5}
 * code stranded from an earlier choice (the invoice was briefly rectificative, then reverted
 * through a channel that skipped the callout's own clear-on-revert, e.g. a direct MCP/REST
 * write) back to the generic default. Never a guess — SII/TicketBAI need no equivalent here:
 * their own structural fields ({@code aeatsiiClaveTipo}/{@code tbaiReverseinvoicecode}) are
 * either corrected only in the rectificative direction (SII, deliberately — see
 * {@code correctClaveTipo}) or never corrected by this guard at all (TicketBAI's code is the
 * user's own choice both ways).
 *
 * <p><b>Fails OPEN on a read/write error</b>, mirroring {@link RectificationTotalGuard}: this
 * guard's own failure to resolve or persist a fiscal field must never block a legitimate completion
 * it cannot evaluate.
 *
 * <p><b>Placement.</b> Invoice-specific, not shared NEO code: wired from
 * {@link AbstractInvoiceHeaderHandler#completeInvoiceIfNeeded}, alongside
 * {@link RectificationTotalGuard}. Applies identically to any invoice spec built on
 * {@code C_Invoice} — nothing here branches on a spec or table name, only on the document type's
 * own rectificative flag, the invoice's own {@code IsSOTrx}, and each system's own active
 * configuration.
 */
final class RectificationFiscalCodeGuard {

  private static final Logger log = LogManager.getLogger(RectificationFiscalCodeGuard.class);

  private static final String CLAVE_TIPO_RECTIFICATIVA = "R";
  private static final String TIPO_RECTIF_DEFAULT = "I";
  private static final String RECTIFICATIVE_PREFIX = "R";
  /** The generic AD_Column default for {@code etvfacInvType} (ETP-4390: "Factura"). */
  private static final String VERIFACTU_INV_TYPE_DEFAULT = "F1";

  private static final String SYSTEM_SII = "SII";
  private static final String SYSTEM_TBAI = "TicketBAI";
  private static final String SYSTEM_VERIFACTU = "Verifactu";

  /** Stable identity sent in messageKeys — a plain string, not an AD_MESSAGE (ETP-5692 precedent). */
  static final String MSG_RECTIFICATION_CODE_REQUIRED = "ETGO_InvoiceRectificationCodeRequired";

  private RectificationFiscalCodeGuard() {
  }

  /**
   * Applies the rectificative fiscal-code rule when the request is a completion action; otherwise
   * a no-op. See the class javadoc for the full rule.
   *
   * @param context the current request
   * @return a 422 naming the system(s) still missing their code/motive, or {@code null} to let the
   *     completion proceed
   */
  static NeoResponse checkAndApply(NeoContext context) {
    if (!InvoiceCalloutHelper.isInvoiceCompleteAction(context)) {
      return null;
    }
    String invoiceId = InvoiceCalloutHelper.resolveInvoiceIdFromContext(context);
    if (StringUtils.isBlank(invoiceId)) {
      return null;
    }
    try {
      Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
      if (invoice == null || !Boolean.TRUE.equals(invoice.isSalesTransaction())) {
        return null;
      }
      DocumentType docType = invoice.getTransactionDocument();
      if (!RectificativeSupport.isRectificative(docType)) {
        // ETP-5745 CP-20: not rectificative — the only thing left to do is clean up a VERI*FACTU
        // R1-R5 code stranded from an earlier choice (e.g. the invoice was rectificative, then
        // the document type was reverted through a channel that skipped the callout's own
        // clear-on-revert, such as a direct MCP/REST write). Never a guess: this only clears an
        // already-wrong value back to the generic default, the same "cleanup, not a guess"
        // rule InvoiceCalloutHelper#applyVerifactuInvTypeFromDocType already follows.
        if (clearStaleVerifactuInvType(invoice)) {
          OBDal.getInstance().save(invoice);
          OBDal.getInstance().flush();
        }
        return null;
      }
      String orgId = invoice.getOrganization() != null ? invoice.getOrganization().getId() : null;
      List<String> missing = missingSystems(invoice, orgId);
      if (!missing.isEmpty()) {
        return reject(missing);
      }
      // ETP-5745 CP-24: only correct SII's structural fields when SII actually applies to this
      // org — see missingSystems' matching gate just below for why (an org that never enrolled
      // in SII must never have aeatsiiClaveTipo stamped "R": the DB check constraint fires on
      // ClaveTipo alone, with no awareness of whether the org even uses SII).
      boolean changed = StringUtils.isNotBlank(orgId)
          && SalesRectificativeFiscalDefaults.siiConfigActive(orgId)
          && (correctClaveTipo(invoice) | defaultTipoRectif(invoice));
      if (changed) {
        OBDal.getInstance().save(invoice);
        OBDal.getInstance().flush();
      }
      return null;
    } catch (RuntimeException | JSONException e) {
      log.warn("Could not evaluate the rectification fiscal-code guard for invoice {}: {}",
          invoiceId, e.getMessage());
      return null;
    }
  }

  /**
   * The fiscal systems, in SII/TicketBAI/VERI*FACTU order, that are active for {@code orgId} and
   * still missing their rectification code/motive on {@code invoice}.
   *
   * <p>ETP-5745 CP-24: SII is now gated on {@link SalesRectificativeFiscalDefaults#siiConfigActive}
   * exactly like TicketBAI/VERI*FACTU already were — it used to be checked unconditionally,
   * which was the real bug behind the DB check constraint firing for an org that never enrolled
   * in SII at all (nothing in this guard, nor in the doc-type callout, may stamp a value toward
   * {@code aeatsiiClaveTipo='R'} for such an org in the first place — see
   * {@code InvoiceCalloutHelper#applyRectificativeFieldsFromDocType}'s matching fix).
   */
  private static List<String> missingSystems(Invoice invoice, String orgId) {
    List<String> missing = new ArrayList<>();
    if (StringUtils.isNotBlank(orgId)) {
      if (SalesRectificativeFiscalDefaults.siiConfigActive(orgId)
          && StringUtils.isBlank(invoice.getAeatsiiMotivoRectif())) {
        missing.add(SYSTEM_SII);
      }
      if (SalesRectificativeFiscalDefaults.tbaiConfigActive(orgId)
          && StringUtils.isBlank(invoice.getTbaiReverseinvoicecode())) {
        missing.add(SYSTEM_TBAI);
      }
      if (SalesRectificativeFiscalDefaults.configExists(
          SalesRectificativeFiscalDefaults.VERIFACTU_ACTIVE_SQL, orgId)
          && !isRectificativeVerifactuCode(invoice.getEtvfacInvType())) {
        missing.add(SYSTEM_VERIFACTU);
      }
    }
    return missing;
  }

  private static boolean isRectificativeVerifactuCode(String code) {
    return code != null && code.startsWith(RECTIFICATIVE_PREFIX);
  }

  /**
   * Clears a stranded VERI*FACTU {@code R1}-{@code R5} code back to the generic default
   * ({@code "F1"}) on a NON-rectificative invoice. {@code etvfacInvType} folds "this is a
   * correction" and "for this reason" into one value, so a non-rectificative invoice carrying
   * one is always stale, never a legitimate state to complete with.
   *
   * @return {@code true} when the value was changed
   */
  private static boolean clearStaleVerifactuInvType(Invoice invoice) {
    if (!isRectificativeVerifactuCode(invoice.getEtvfacInvType())) {
      return false;
    }
    invoice.setEtvfacInvType(VERIFACTU_INV_TYPE_DEFAULT);
    return true;
  }

  /** @return {@code true} when the value was changed */
  private static boolean correctClaveTipo(Invoice invoice) {
    if (CLAVE_TIPO_RECTIFICATIVA.equals(invoice.getAeatsiiClaveTipo())) {
      return false;
    }
    invoice.setAeatsiiClaveTipo(CLAVE_TIPO_RECTIFICATIVA);
    return true;
  }

  /** @return {@code true} when the value was changed */
  private static boolean defaultTipoRectif(Invoice invoice) {
    if (StringUtils.isNotBlank(invoice.getAeatsiiTipoRectif())) {
      return false;
    }
    invoice.setAeatsiiTipoRectif(TIPO_RECTIF_DEFAULT);
    return true;
  }

  /**
   * The 422 refusal, in the same flat {@code success:false}/{@code message}/{@code messageKeys}/
   * {@code messageParams} shape {@link RectificationTotalGuard} answers with — explicitly naming
   * the system(s) still missing their code, dynamically built from {@code missingSystems} (never a
   * hardcoded "SII y TicketBAI" string).
   */
  /**
   * ETP-5745 CP-19: the {@code message} field is English, plain prose — the wire contract for a
   * channel with no i18n layer of its own (MCP, a script, curl), exactly like every other
   * guard's refusal in this module (see {@code backendErrors.js}'s own comments: "the Java text
   * is the wire contract"). The SPA never shows this string: it recognises
   * {@code messageKeys: [MSG_RECTIFICATION_CODE_REQUIRED]} and renders its OWN localized sentence
   * from {@code messageParams.systems} — a raw JSON array, deliberately NOT pre-joined into a
   * string here, so the frontend's {@code joinWithConjunction} can apply the session's own
   * locale-aware conjunction ("y"/"and") — see
   * {@code translateRectificationCodeRequired} (backendErrors.js) and the matching
   * {@code backendError.rectificationCodeRequired} locale entries, mirroring the SAME pattern
   * already established for {@code InvoiceReactivationGuard}'s
   * {@code ETGO_InvoiceReactivateFiscalRectified}. Earlier versions of this guard built the
   * Spanish sentence (join included) here in Java — wrong: it ignored the session's own
   * language, unlike the DB-constraint message this guard exists to pre-empt, which goes through
   * {@code AD_MESSAGE}/{@code AD_MESSAGE_TRL} correctly.
   */
  private static NeoResponse reject(List<String> missingSystems) throws JSONException {
    String message = "Select a rectification reason/code in the SIF tab for "
        + String.join(", ", missingSystems) + ".";

    JSONObject body = new JSONObject();
    body.put("success", false);
    body.put("message", message);
    body.put("messageKeys", new JSONArray(List.of(MSG_RECTIFICATION_CODE_REQUIRED)));
    JSONObject messageParams = new JSONObject();
    messageParams.put("systems", new JSONArray(missingSystems));
    body.put("messageParams", messageParams);
    return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, body);
  }
}
