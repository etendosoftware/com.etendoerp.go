package com.etendoerp.go.schemaforge;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;
import org.apache.commons.lang.StringUtils;
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
