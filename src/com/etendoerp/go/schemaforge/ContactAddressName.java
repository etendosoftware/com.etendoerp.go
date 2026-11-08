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

import java.util.Collection;
import java.util.function.Function;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.geography.Location;

/**
 * The name {@link ContactsLocationAddressHandler} gives a new contact address (ETP-5852, M-168),
 * kept apart from the handler so it stays within Sonar's method budget.
 */
final class ContactAddressName {

  private static final String FIELD_NAME = "name";
  private static final String AD_DEFAULT = ".";
  private static final String FALLBACK = "Location";

  private ContactAddressName() {
  }

  /**
   * The name the caller sent, else a default. Over MCP the default is the name the app's address
   * editor gives — {@code displayName} of the location (city and address line, else region and
   * country), else "Location" — instead of the AD default ".", which an agent then showed as the
   * address name. MCP's defaults pass puts that "." in the body before the hook, so only a name the
   * caller itself sent ({@link NeoContext#ATTR_CLIENT_PROVIDED_FIELDS}) counts as sent. Other
   * callers always send a name and keep the old default.
   *
   * @param ctx         the pre-hook context
   * @param body        the create body
   * @param geoLoc      the address's location, may be {@code null}
   * @param displayName the location's display name, {@code null} when it has none
   * @return the name to store, never {@code null}
   */
  static String of(NeoContext ctx, JSONObject body, Location geoLoc,
      Function<Location, String> displayName) {
    String sentName = nonBlank(body.optString(FIELD_NAME, null));
    if (ctx == null || !ctx.isMcpOrigin() || geoLoc == null) {
      return sentName != null ? sentName : AD_DEFAULT;
    }
    Object provided = ctx.getAttribute(NeoContext.ATTR_CLIENT_PROVIDED_FIELDS);
    boolean sent = !(provided instanceof Collection)
        || ((Collection<?>) provided).contains(FIELD_NAME);
    String name = sent ? sentName : null;
    if (name == null) {
      name = displayName.apply(geoLoc);
    }
    return name != null ? name : FALLBACK;
  }

  /** As the handler's {@code nullIfEmpty}: empty and the literal "null" are no value. */
  private static String nonBlank(String value) {
    return value == null || value.isEmpty() || "null".equals(value) ? null : value;
  }
}
