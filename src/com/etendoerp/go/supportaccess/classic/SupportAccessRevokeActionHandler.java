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

import java.util.Map;
import java.util.function.Supplier;

import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.supportaccess.SupportAccessException;
import com.etendoerp.go.supportaccess.SupportAccessRecord;
import com.etendoerp.go.supportaccess.SupportAccessService;

/**
 * ETP-5351 — "Close Support Session" ("Cerrar sesión de soporte"): closes the open support access
 * of the selected company, whoever opened it, so a forgotten session does not block the others.
 * The access ends as {@code revoked}, with the operator who closed it, and its GO sessions are
 * revoked.
 */
public class SupportAccessRevokeActionHandler extends SupportAccessActionHandler {

  /** Production wiring. */
  public SupportAccessRevokeActionHandler() {
    super();
  }

  /**
   * Explicit wiring, for tests.
   *
   * @param serviceFactory creates the service used by one execution
   */
  SupportAccessRevokeActionHandler(Supplier<SupportAccessService> serviceFactory) {
    super(serviceFactory);
  }

  @Override
  protected JSONObject handle(SupportAccessService service, JSONObject request,
      Map<String, Object> parameters) {
    String clientId = selectedClientId(request);
    SupportAccessRecord open = clientId == null ? null : service.findOpenAccess(clientId);
    if (open == null || !service.revoke(open.getId(), currentUserId())) {
      throw new SupportAccessException(SupportAccessException.CODE_ACCESS_NOT_OPEN,
          "The company has no open support access");
    }
    commit();
    return success(getResponseBuilder(), translate(SupportAccessMessages.MSG_ACCESS_REVOKED))
        .build();
  }
}
