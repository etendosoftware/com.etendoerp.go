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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess.classic;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.client.application.process.BaseProcessActionHandler;
import org.openbravo.client.application.process.ResponseActionsBuilder;
import org.openbravo.client.application.process.ResponseActionsBuilder.MessageType;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.supportaccess.SupportAccessException;
import com.etendoerp.go.supportaccess.SupportAccessGuard;
import com.etendoerp.go.supportaccess.SupportAccessService;

/**
 * ETP-5351 — shared skeleton of the Classic "Support · Companies" processes: refuses any role but
 * "Soporte Etendo GO", reads the selected company, owns the transaction and turns every
 * {@link SupportAccessException} into an {@code ETGO_} message for the operator.
 *
 * <p>Access is checked three times, on purpose: {@link BaseProcessActionHandler#hasAccess}
 * (explicit process access, granted to the support role only), the role check here (the logged
 * role, so an operator logged in as System Administrator is refused too) and
 * {@link SupportAccessService} (the user holds the support role).</p>
 *
 * <p>The OBContext, message, transaction and clock calls are protected methods so the handlers can
 * be unit-tested without a database.</p>
 */
public abstract class SupportAccessActionHandler extends BaseProcessActionHandler {

  private static final Logger log = LogManager.getLogger(SupportAccessActionHandler.class);

  /** Request key naming the property that holds the selected record id. */
  static final String KEY_NAME = "inpKeyName";
  /** Request key holding the process parameters. */
  static final String PARAMS = "_params";
  private static final String TITLE_ERROR = "Error";
  private static final String TITLE_SUCCESS = "Success";

  private final Supplier<SupportAccessService> serviceFactory;

  /** Production wiring: a JDBC-backed {@link SupportAccessService} per request. */
  protected SupportAccessActionHandler() {
    this(SupportAccessService::new);
  }

  /**
   * Explicit wiring, for tests.
   *
   * @param serviceFactory creates the service used by one execution
   */
  protected SupportAccessActionHandler(Supplier<SupportAccessService> serviceFactory) {
    this.serviceFactory = serviceFactory;
  }

  @Override
  protected final JSONObject doExecute(Map<String, Object> parameters, String content) {
    SupportAccessService service = null;
    try {
      service = serviceFactory.get();
      if (!SupportAccessGuard.SUPPORT_OPERATOR_ROLE_ID.equals(currentRoleId())) {
        throw new SupportAccessException(SupportAccessException.CODE_OPERATOR_NOT_ALLOWED,
            "Only the support role can run this process");
      }
      JSONObject request = new JSONObject(StringUtils.defaultIfBlank(content, "{}"));
      return handle(service, request, parameters);
    } catch (SupportAccessException e) {
      log.info("Support process refused: {}", e.getCode());
      rollback();
      return refusal(e, service);
    } catch (JSONException | RuntimeException e) {
      log.error("Support process failed", e);
      rollback();
      return error(translate(SupportAccessMessages.MSG_ACCESS_FAILED));
    }
  }

  /**
   * Runs the process once the role is checked. Throw {@link SupportAccessException} to refuse.
   *
   * @param service    the support access service of this execution
   * @param request    the process request (record values, {@code _params}, ...)
   * @param parameters the raw handler parameters (HTTP request, window id, ...)
   * @return the client response
   * @throws JSONException when the request cannot be read
   */
  protected abstract JSONObject handle(SupportAccessService service, JSONObject request,
      Map<String, Object> parameters) throws JSONException;

  /**
   * The selected company: the record of the "Support · Companies" tab, whose id is the
   * {@code AD_Client_ID} of the tenant.
   *
   * @param request the process request
   * @return the client id, or {@code null} when no record is selected
   */
  static String selectedClientId(JSONObject request) {
    String keyName = StringUtils.trimToNull(request.optString(KEY_NAME, null));
    if (keyName == null || request.isNull(keyName)) {
      return null;
    }
    return StringUtils.trimToNull(request.optString(keyName, null));
  }

  /**
   * Adds the success message, shown in the window that launched the process.
   *
   * @param response the response being built
   * @param text     the message, HTML allowed
   * @return the same builder
   */
  protected ResponseActionsBuilder success(ResponseActionsBuilder response, String text) {
    return response.showMsgInProcessView(MessageType.SUCCESS, translate(TITLE_SUCCESS), text);
  }

  /**
   * An error response that closes the popup.
   *
   * @param text the message, HTML allowed
   * @return the response
   */
  protected JSONObject error(String text) {
    return getResponseBuilder().showMsgInProcessView(MessageType.ERROR, translate(TITLE_ERROR),
        text).build();
  }

  private JSONObject refusal(SupportAccessException refusal, SupportAccessService service) {
    SupportAccessMessages.Text message = SupportAccessMessages.forError(refusal,
        maxDurationMinutes(refusal, service), clock().instant(), zone(), this::translate);
    String text = translate(message.getKey(), message.getParams());
    if (SupportAccessMessages.isFixableInPopup(refusal.getCode())) {
      // The operator can fix the reason or the duration: keep the popup open.
      return getResponseBuilder().retryExecution(MessageType.ERROR, translate(TITLE_ERROR), text)
          .build();
    }
    return error(text);
  }

  private static int maxDurationMinutes(SupportAccessException refusal,
      SupportAccessService service) {
    if (service == null
        || !SupportAccessException.CODE_DURATION_INVALID.equals(refusal.getCode())) {
      return SupportAccessService.FALLBACK_MAX_MINUTES;
    }
    try {
      return service.getMaxDurationMinutes();
    } catch (RuntimeException e) {
      log.warn("Could not read the maximum support session duration", e);
      return SupportAccessService.FALLBACK_MAX_MINUTES;
    }
  }

  /** @return the {@code AD_User_ID} of the logged operator */
  protected String currentUserId() {
    return OBContext.getOBContext().getUser().getId();
  }

  /** @return the {@code AD_Role_ID} the operator is logged in with */
  protected String currentRoleId() {
    return OBContext.getOBContext().getRole().getId();
  }

  /**
   * Translates an {@code AD_Message} into the operator's language.
   *
   * @param key    the {@code AD_Message.Value}
   * @param params the {@code %0}, {@code %1}… values
   * @return the text, or the key when the message does not exist
   */
  protected String translate(String key, String... params) {
    return OBMessageUtils.getI18NMessage(key, params);
  }

  /** Commits the work of this execution (the service never commits). */
  protected void commit() {
    OBDal.getInstance().commitAndClose();
  }

  /** Discards the work of this execution. */
  protected void rollback() {
    OBDal.getInstance().rollbackAndClose();
  }

  /** @return the time source of the busy message */
  protected Clock clock() {
    return Clock.systemUTC();
  }

  /** @return the zone the busy-until time is shown in: the server's, like the window's dates */
  protected ZoneId zone() {
    return ZoneId.systemDefault();
  }
}
