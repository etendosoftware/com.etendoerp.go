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
package com.etendoerp.go.schemaforge.handlers;

import java.util.Collections;
import java.util.Set;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;

import com.etendoerp.go.schemaforge.AbstractPersonNameHandler;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Pre-save hook for the {@code contact} entity ({@code AD_User}) of the contacts spec.
 *
 * <p>Fills the AD-mandatory name column that the window does not expose as an editable
 * field, so a create driven only by the visible fields (first name / last name) passes
 * validation:
 * <ul>
 *   <li><b>{@code name}</b> — {@code AD_User.Name} is mandatory but declared
 *       {@code readOnly / form: false} in {@code decisions.json}. Derived as
 *       {@code firstName + " " + lastName} by {@link AbstractPersonNameHandler#deriveName}
 *       when the effective name is blank (body value on POST, persisted value on
 *       PATCH/PUT).</li>
 *   <li><b>{@code username}</b> — intentionally omitted. Contacts created through Classic
 *       leave {@code AD_User.Username} null, so NEO must preserve that behavior.</li>
 * </ul>
 *
 * <p>The derived name is truncated to the {@code AD_User.Name} column length
 * ({@code NVARCHAR(60)}).
 *
 * <p><b>ETP-4156.</b> This logic used to live in the app-shell's generic
 * {@code useEntity} hook, branching on hardcoded entity names
 * ({@code contact} / {@code adUser} / {@code user}), which violated the
 * "no window-specific logic in generic services" principle. Moving it here also covers
 * the {@code /batch} import path, which routes through {@code handleWithHooks} too.
 *
 * <p>Two deliberate differences from the removed front-end code:
 * <ul>
 *   <li>{@code username} is not generated. The old hook generated it from the contact name,
 *       which diverged from Classic and caused duplicate {@code AD_User.Username} errors.</li>
 *   <li>It applies to the contacts spec's {@code contact} entity only. The {@code user}
 *       spec exposes {@code name} and {@code username} as editable fields, so it no
 *       longer gets a silent autofill.</li>
 * </ul>
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'contactHandler'} on the ETGO_SF_ENTITY
 * record for the contacts spec's {@code contact} entity.
 *
 * @see com.etendoerp.go.schemaforge.BusinessPartnerHandler the sibling hook that derives the
 *     same way for {@code C_BPartner}
 */
@Named("contactHandler")
public class ContactHandler extends AbstractPersonNameHandler {

  private static final Logger log = LogManager.getLogger(ContactHandler.class);

  private static final String FIELD_FIRSTNAME = "firstName";
  private static final String FIELD_LASTNAME = "lastName";

  private static final String METHOD_POST = "POST";

  /** {@code AD_User.Name} is {@code NVARCHAR(60)}. */
  private static final int MAX_LENGTH = 60;

  @Override
  protected String firstnameField() {
    return FIELD_FIRSTNAME;
  }

  @Override
  protected String lastnameField() {
    return FIELD_LASTNAME;
  }

  @Override
  protected String persistedNamePartsSql() {
    return "SELECT name, firstname, lastname FROM ad_user WHERE ad_user_id = ?";
  }

  @Override
  protected int maxNameLength() {
    return MAX_LENGTH;
  }

  @Override
  public Set<String> protectedCreateCalloutFields(NeoContext context) {
    return Collections.singleton("username");
  }

  @Override
  public NeoResponse handle(NeoContext ctx) {
    String method = ctx.getHttpMethod();
    boolean isWrite = METHOD_POST.equals(method) || "PATCH".equals(method) || "PUT".equals(method);
    if (!isWrite) {
      return null;
    }
    JSONObject body = ctx.getRequestBody();
    if (body == null) {
      return null;
    }
    try {
      deriveName(ctx, body);
    } catch (Exception e) {
      log.error("ContactHandler: error in handle()", e);
      throw new OBException("Error processing Contact name derivation", e);
    }
    return null;
  }

}
