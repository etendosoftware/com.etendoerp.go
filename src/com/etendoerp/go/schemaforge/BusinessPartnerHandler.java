/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

/**
 * Pre-save hook for the businessPartner entity in the contacts spec.
 * On POST (new record), injects searchKey = name when not provided by the client,
 * so the user does not need to fill in the Search Key field manually.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'businessPartnerHandler'} on the
 * ETGO_SF_ENTITY record for the contacts spec's businessPartner entity.
 */
@ApplicationScoped
@Named("businessPartnerHandler")
public class BusinessPartnerHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(BusinessPartnerHandler.class);

  @Override
  public NeoResponse handle(NeoContext ctx) {
    if (!"POST".equals(ctx.getHttpMethod())) {
      return null;
    }
    JSONObject body = ctx.getRequestBody();
    if (body == null) {
      return null;
    }
    try {
      String name = body.optString("name", null);
      if (StringUtils.isNotBlank(name) && !body.has("searchKey")) {
        body.put("searchKey", name);
      }
    } catch (Exception e) {
      log.error("BusinessPartnerHandler: error injecting searchKey", e);
    }
    return null;
  }
}
