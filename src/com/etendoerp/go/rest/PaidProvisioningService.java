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
package com.etendoerp.go.rest;

import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.onboarding.OnboardingProgressSink;
import com.etendoerp.go.payment.SystemContext;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/**
 * ETP-5048 — provisions a purchase that is already paid, with no browser attached.
 *
 * <p>Provisioning used to start only from the browser ({@code POST /sws/go/onboarding}), which
 * polls for the payment for a minute and then gives up. A customer who closed the tab, or whose
 * webhook was lost, had paid and had no environment. The billing reconciliation calls this service
 * for such a purchase.
 *
 * <p><b>One provisioning path, two callers.</b> This class does not re-implement onboarding. It
 * resolves the inputs the browser would have sent from the checkout row (with the fallbacks below)
 * and hands them to {@link EtendoGoJwtServlet#provisionPaidPurchase}, which runs the same
 * preparation steps the HTTP path runs after authentication (ownership, paywall, demo source, the
 * fenced {@code claimForProvisioning}) and then the same {@link EtendoGoJwtServlet#runOnboarding}.
 * The progress the run streams as NDJSON is replayed onto the {@link OnboardingProgressSink} given
 * here. The HTTP path is unchanged.
 *
 * <p><b>Inputs.</b> {@code LANGUAGE}, {@code CURRENCY} and {@code COUNTRY} recorded on the checkout
 * row at purchase time; when a column is empty (a purchase made before ETP-5048, or one whose
 * browser did not send the value) the account's stored onboarding draft, then {@code en_US},
 * {@code EUR} and {@code ES} — the same defaults the onboarding request parser applies. The
 * company address and fiscal identifier come from the draft too, so a server-repaired environment
 * gets the same company data the browser would have sent.
 *
 * <p>The caller's {@link OBContext} is restored afterwards: the run switches the thread to the
 * system context and then into the new tenant.
 */
public class PaidProvisioningService {

  private static final Logger log = LogManager.getLogger(PaidProvisioningService.class);

  static final String DEFAULT_LANGUAGE = "en_US";
  static final String DEFAULT_CURRENCY = "EUR";
  static final String DEFAULT_COUNTRY = "ES";

  private static final String DRAFT_FORM = "form";
  private static final String DRAFT_LANGUAGE = "language";
  private static final String DRAFT_CURRENCY = "currency";
  private static final String DRAFT_COUNTRY = "countryCode";
  private static final String DRAFT_FULL_NAME = "fullName";
  private static final String DRAFT_ADDRESS = "address";
  private static final String DRAFT_TAX_ID = "fiscalIdValue";

  /** How a server-side provisioning attempt ended. */
  public enum Status {
    /** The environment was created and the purchase is {@code PROVISIONED}. */
    PROVISIONED,
    /** The attempt ran and failed; whether a retry can help is on the checkout row. */
    FAILED,
    /** The attempt could not start for a reason no retry fixes; a person has to look at it. */
    NEEDS_REVIEW,
    /** Another attempt holds the purchase (or it is already provisioned); nothing was done. */
    NOT_CLAIMED,
    /** The purchase is not confirmed as paid; nothing was done. */
    NOT_PAID
  }

  /**
   * The result of {@link #provision}.
   *
   * @param status how the attempt ended
   * @param code a stable code for {@link Status#FAILED} and {@link Status#NEEDS_REVIEW}, else null
   * @param detail a short operational explanation, never customer data
   * @param attempt the claimed attempt token, or null when nothing was claimed
   */
  public record Outcome(Status status, String code, String detail, Long attempt) {

    static Outcome of(Status status, String code, String detail) {
      return new Outcome(status, code, detail, null);
    }
  }

  /**
   * What the browser would have sent for a paid purchase, resolved from the checkout row.
   *
   * @param requestId the checkout {@code REQUEST_ID} (the onboarding {@code paymentToken})
   * @param accountId the purchasing account
   * @param accountEmail the purchasing account's email
   * @param accountName the purchasing account's name, may be blank
   * @param clientName the company name fixed at checkout
   * @param fullName the owner's full name from the stored draft, may be blank
   * @param language the UI language, never blank
   * @param currencyIso the ISO currency code, never blank
   * @param countryCode the ISO country code, never blank
   * @param address the company address from the stored draft, may be blank
   * @param taxId the fiscal identifier from the stored draft ({@code fiscalIdValue}), may be blank
   */
  public record PaidInput(String requestId, String accountId, String accountEmail,
      String accountName, String clientName, String fullName, String language, String currencyIso,
      String countryCode, String address, String taxId) {
  }

  private final Supplier<EtendoGoJwtServlet> runnerFactory;

  /** Production wiring: a fresh onboarding runner per attempt. */
  public PaidProvisioningService() {
    this(EtendoGoJwtServlet::new);
  }

  /**
   * Wiring with an explicit runner, for tests.
   *
   * @param runnerFactory supplies the servlet whose onboarding run is reused
   */
  PaidProvisioningService(Supplier<EtendoGoJwtServlet> runnerFactory) {
    this.runnerFactory = runnerFactory;
  }

  /**
   * Provisions one paid purchase.
   *
   * @param requestId the checkout {@code REQUEST_ID}
   * @param sink receives the run's progress and result lines
   * @return how the attempt ended
   */
  public Outcome provision(String requestId, OnboardingProgressSink sink) {
    PaidInput input = SystemContext.call("resolving a paid purchase's onboarding inputs", () -> {
      CheckoutRequest request = (CheckoutRequest) OBDal.getInstance()
          .createCriteria(CheckoutRequest.class)
          .setFilterOnReadableClients(false)
          .setFilterOnReadableOrganization(false)
          .add(Restrictions.eq(CheckoutRequest.PROPERTY_REQUEST, requestId))
          .uniqueResult();
      return request == null ? null : inputOf(request);
    });
    if (input == null) {
      return Outcome.of(Status.NEEDS_REVIEW, "ACCOUNT_MISSING",
          "The checkout request does not exist or names no purchasing account");
    }
    OBContext previousContext = OBContext.getOBContext();
    try {
      return runnerFactory.get().provisionPaidPurchase(input, sink);
    } finally {
      try {
        OBContext.setOBContext(previousContext);
      } catch (RuntimeException e) {
        log.error("Could not restore the caller's OBContext after server-side provisioning", e);
      }
    }
  }

  /**
   * Resolves the onboarding inputs of a paid purchase. Caller provides the system context.
   *
   * @param request the checkout row
   * @return the inputs, or null when the row names no account
   */
  static PaidInput inputOf(CheckoutRequest request) {
    Account account = request.getEtendoGoAccount();
    if (account == null) {
      return null;
    }
    JSONObject draft = draftFormOf(account);
    return new PaidInput(request.getRequest(), account.getId(), request.getAccountEmail(),
        account.getName(), request.getClientName(), draftValue(draft, DRAFT_FULL_NAME),
        firstNonBlank(request.getLanguage(), draftValue(draft, DRAFT_LANGUAGE), DEFAULT_LANGUAGE),
        firstNonBlank(request.getCurrency(), draftValue(draft, DRAFT_CURRENCY), DEFAULT_CURRENCY),
        firstNonBlank(request.getCountry(), draftValue(draft, DRAFT_COUNTRY), DEFAULT_COUNTRY),
        draftValue(draft, DRAFT_ADDRESS), draftValue(draft, DRAFT_TAX_ID));
  }

  private static String firstNonBlank(String recorded, String fromDraft, String fallback) {
    return StringUtils.trim(StringUtils.firstNonBlank(recorded, fromDraft, fallback));
  }

  private static JSONObject draftFormOf(Account account) {
    String stored = account.getOnboardingDraft();
    if (StringUtils.isBlank(stored)) {
      return null;
    }
    try {
      return new JSONObject(stored).optJSONObject(DRAFT_FORM);
    } catch (JSONException e) {
      log.debug("Stored onboarding draft of account {} is not valid JSON; ignoring it",
          account.getId());
      return null;
    }
  }

  private static String draftValue(JSONObject form, String field) {
    if (form == null || !form.has(field) || form.isNull(field)) {
      return null;
    }
    Object value = form.opt(field);
    return value instanceof String ? StringUtils.trimToNull((String) value) : null;
  }
}
