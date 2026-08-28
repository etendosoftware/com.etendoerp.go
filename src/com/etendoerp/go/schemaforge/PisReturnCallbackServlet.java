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

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.psd2.bank.integration.data.PisPayment;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.PISPaymentDao;

/**
 * Resolves a PIS bank transfer's status server-side the moment the browser comes back from the
 * bank's SCA screen — matching what Classic's own {@code PisPaymentCallback#doGet} does for its
 * "Generate Bank Payment" flow — instead of leaving that entirely to the "Add payment" modal's own
 * poll (ETP-4895).
 *
 * <p><b>Why Etendo Go needed a poll that Classic never did.</b> In Classic the {@code FIN_Payment}
 * already exists before the transfer is sent, so a single consult on return only has to ANNOTATE
 * its status. In Etendo Go's deferred flow ({@link PisDeferredPaymentService}) the payment does not
 * exist until a consult returns a resolutive status, which is why the SPA had to keep asking. This
 * servlet moves that consult to where Classic has it — the browser's return — so the payment is
 * created with NO dependency on the "Add payment" modal, or even the app-shell tab that opened it,
 * still being open.</p>
 *
 * <p><b>Why not Classic's own {@code /pisPaymentCallback}.</b> That servlet updates the PSD2 row
 * and creates the financial transaction, but never calls {@link PisDeferredPaymentService#reconcile}
 * — the Etendo Go-specific step that actually creates the {@code FIN_Payment}. It cannot: PSD2 is
 * the shared module that Classic also depends on, so it must not know about Etendo Go. It also
 * redirects to a hardcoded Classic landing page, with no way back to the app-shell's own callback
 * route.</p>
 *
 * <p><b>What it reuses rather than duplicates.</b> The consult-persist-reconcile sequence is
 * {@link PisPaymentService#refreshPisStatusFromSaltEdge}, called verbatim — the same one the SPA's
 * poll uses, made package-private for exactly this. There is one implementation of that sequence,
 * shared by its two triggers (the SPA's poll and this browser redirect).</p>
 *
 * <p><b>Reachable with no Etendo session.</b> The bank redirects the bare browser here: no
 * {@code Authorization} bearer header (that is only ever attached by the SPA's own fetch calls,
 * never by a full-page navigation) and, since Etendo Go's login is JWT/API-based rather than a
 * classic HTTP session, most likely no session cookie either. That is fine on this path —
 * {@code /sws/pis-return} is an exact servlet mapping, so it is served here and never reaches the
 * {@code /sws/*} SecureWebServices dispatcher, and none of the four global {@code /*} filters
 * rejects a session-less request.</p>
 *
 * <p>Finding the row, however, needs {@link #findPaymentBypassingFilters} rather than
 * {@link PISPaymentDao#findBySaltedgePaymentId}: that helper wraps a plain {@code OBCriteria} in
 * admin mode, and {@code OBCriteria} still filters on readable clients. With no session,
 * {@code setAdminMode()} bootstraps the SYSTEM context (client "0"), so a row owned by a real
 * tenant is filtered out and the lookup returns null. PSD2's own callback hit this exact wall and
 * carries its own filter-disabling copy ({@code PisPaymentCallback#findPaymentByPassingFilters});
 * this mirrors it.</p>
 *
 * <p>Once found, a REAL tenant-scoped context is bootstrapped from the row's own
 * client/organization/creator — mirroring {@code PisPaymentCallback}'s
 * {@code initializeContextFromPayment} — rather than trusting {@code OBContext.setAdminMode(true)}'s
 * bootstrap fallback (client/org "0", the System tenant):
 * {@link com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils#getPsd2ApiKey} reads a
 * PER-CLIENT preference, and its own javadoc is explicit that the execution context must never
 * stand in for the entity's real owning client.</p>
 *
 * <p><b>What this does NOT cover.</b> A user who authorizes at the bank and kills the popup before
 * it redirects back never reaches this servlet, so their payment is still not created. Closing that
 * last gap would need PSD2 itself to invoke Etendo Go's reconcile (from its webhook and its
 * {@code RefreshPendingPayments} job, which do arrive without any browser) — a change in the shared
 * module, deliberately out of scope here.</p>
 *
 * <p>Registered via {@code AD_MODEL_OBJECT} + {@code AD_MODEL_OBJECT_MAPPING} at
 * {@code /sws/pis-return} (see {@code src-db/database/sourcedata/}), the same mechanism as this
 * module's other bare servlets (e.g. {@code NeoCurrencyFormatServlet} at
 * {@code /sws/neo/currency-format}). {@link PisPaymentBridge} points Salt Edge's {@code return_to}
 * here for every Etendo Go-initiated transfer, and separately persists the SPA's own callback page
 * URL on {@code PSD2_PIS_PAYMENT.return_to_url} at creation time, so this servlet knows where to
 * bounce the browser once it is done — see {@link PisPaymentBridge#resolveBackendReturnUrl}.
 */
public class PisReturnCallbackServlet extends HttpBaseServlet {

  private static final long serialVersionUID = 1L;
  private static final Logger log = LogManager.getLogger(PisReturnCallbackServlet.class);

  private static final String ERROR_CLASS_PARAM = "error_class";

  /**
   * Last-resort landing page, for when the originating SPA's own callback URL could not be resolved
   * (the initiating request carried neither an {@code Origin} nor a {@code Referer} header) or the
   * attempt could not be identified at all.
   *
   * <p>This is a popup WE opened, so the right behaviour is to close itself rather than ask the user
   * to do it — leaving a stray window with English boilerplate in it is not an acceptable end to the
   * flow. The visible text only ever appears if the browser refuses the {@code close()} (which it
   * can, for a window not opened by script), and it also nudges the opener to re-check so the
   * payment modal does not sit waiting on a popup that is going away.</p>
   */
  private static final String FALLBACK_BODY =
      "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>&nbsp;</title></head>"
      + "<body style=\"margin:0;font:14px/1.5 system-ui,sans-serif;color:#444;"
      + "display:flex;align-items:center;justify-content:center;height:100vh\">"
      + "<p id=\"m\" style=\"display:none\">Puedes cerrar esta ventana.</p>"
      + "<script>(function(){try{if(window.opener&&!window.opener.closed){"
      + "window.opener.postMessage({type:'pis-completed'},'*');}}catch(e){}"
      + "try{window.close();}catch(e){}"
      + "setTimeout(function(){document.getElementById('m').style.display='block';},400);"
      + "})();</script></body></html>";

  /**
   * Consults Salt Edge for the returning payment, reconciles it, and bounces the browser back to
   * the SPA's own callback page.
   *
   * <p>A failed consult never fails the redirect: the popup must always land somewhere the user can
   * close, and both the PSD2 webhook and its {@code RefreshPendingPayments} job remain able to move
   * the payment forward afterwards.</p>
   */
  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String saltedgePaymentId = request.getParameter(BankIntegrationConstants.PAYMENT_ID);
    if (StringUtils.isBlank(saltedgePaymentId)) {
      log.warn("PIS return callback reached with no payment_id query parameter");
      sendFallback(response);
      return;
    }

    PisPayment pisPayment = findPaymentBypassingFilters(saltedgePaymentId);
    if (pisPayment == null) {
      log.warn("PIS return callback: no PisPayment for Salt Edge id {}", saltedgePaymentId);
      sendFallback(response);
      return;
    }

    String appReturnUrl = pisPayment.getReturnToUrl();
    try {
      initializeContextFromPayment(pisPayment);
      PisPaymentService.refreshPisStatusFromSaltEdge(pisPayment);
    } catch (Exception e) {
      log.error("PIS return callback: could not refresh Salt Edge id {}: {}", saltedgePaymentId,
          e.getMessage(), e);
    } finally {
      OBContext.restorePreviousMode();
    }

    if (StringUtils.isNotBlank(appReturnUrl)) {
      response.sendRedirect(
          buildRedirectUrl(appReturnUrl, saltedgePaymentId, request.getParameter(ERROR_CLASS_PARAM)));
    } else {
      sendFallback(response);
    }
  }

  private static String buildRedirectUrl(String appReturnUrl, String saltedgePaymentId,
      String errorClass) {
    StringBuilder url = new StringBuilder(appReturnUrl);
    url.append(appReturnUrl.contains("?") ? '&' : '?');
    url.append("payment_id=").append(saltedgePaymentId);
    if (StringUtils.isNotBlank(errorClass)) {
      url.append('&').append(ERROR_CLASS_PARAM).append('=').append(errorClass);
    }
    return url.toString();
  }

  private static void sendFallback(HttpServletResponse response) throws IOException {
    response.setContentType("text/html;charset=UTF-8");
    response.getWriter().write(FALLBACK_BODY);
  }

  /**
   * Finds the attempt by its Salt Edge id with every automatic DAL filter switched off.
   *
   * <p>Necessary because this runs with no session: the client/organization filters an
   * {@code OBCriteria} applies by default are evaluated against the SYSTEM context that
   * {@code setAdminMode()} bootstraps when there is nothing to inherit, which never matches a real
   * tenant's row. The row has to be found FIRST — it is the only thing that says which client this
   * belongs to, and {@link #initializeContextFromPayment} needs that to read the tenant's own
   * Salt Edge API key.</p>
   */
  private static PisPayment findPaymentBypassingFilters(String saltedgePaymentId) {
    OBContext.setAdminMode(true);
    try {
      OBQuery<PisPayment> query = OBDal.getInstance()
          .createQuery(PisPayment.class, "as p where p.saltedgePayment = :paymentId");
      query.setNamedParameter("paymentId", saltedgePaymentId);
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setFilterOnActive(false);
      query.setMaxResult(1);
      return query.uniqueResult();
    } catch (Exception e) {
      log.error("PIS return callback: error looking up Salt Edge id {}: {}", saltedgePaymentId,
          e.getMessage(), e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Bootstraps a real, tenant-scoped {@link OBContext} from {@code pisPayment}'s own
   * client/organization/creator — mirrors {@code PisPaymentCallback#initializeContextFromPayment}
   * (the PSD2 webhook's own no-session bootstrap) verbatim, since this servlet is reached the exact
   * same way: no session, no bearer token, nothing to derive an identity from except the row.
   */
  private static void initializeContextFromPayment(PisPayment pisPayment) {
    User createdBy = pisPayment.getCreatedBy();
    Client client = pisPayment.getClient();
    Organization organization = pisPayment.getOrganization();
    if (createdBy == null || client == null || organization == null) {
      throw new IllegalStateException(
          "PisPayment " + pisPayment.getId() + " is missing user/client/organization");
    }
    OBContext.setOBContext(createdBy.getId(), null, client.getId(), organization.getId());
    OBContext.setAdminMode();
  }
}
