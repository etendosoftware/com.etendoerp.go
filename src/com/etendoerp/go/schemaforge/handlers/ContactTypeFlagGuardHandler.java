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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Guards the "at least one contact type" rule (ETP-5791) on the contacts spec's Customer and
 * Vendor/Creditor tabs.
 *
 * <p>Both tabs edit the same {@code C_BPartner} row as the contact header and each exposes one of
 * the two type flags as editable ({@code customer} / {@code vendor}), so an update through them
 * — e.g. {@code etendo_update(contacts, customer, {customer:false})} on a customer-only contact —
 * would otherwise leave the contact with no type, bypassing the check the header applies in
 * {@code BusinessPartnerHandler}. Only a write that sends one of the two flags is checked: these
 * tabs also save unrelated fields (payment terms, price list…), and those saves are not the
 * subject of the rule.</p>
 *
 * <p>Bound per entity with {@code @NeoExtension} on the two concrete subclasses, so every channel
 * (REST single, MCP) resolves it through the same dispatcher.</p>
 *
 * <p>Behind the {@code contact-category-type} feature flag: off, this pre-hook returns
 * {@code null} and the tab saves through the default CRUD unchecked. Either way a saved change of
 * a type emits {@code backend_contact_type_changed}, see {@link ContactTypeTelemetry}.</p>
 */
public abstract class ContactTypeFlagGuardHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ContactTypeFlagGuardHandler.class);

  @Override
  public NeoResponse handle(NeoContext ctx) {
    if (!ContactTypeRules.isCrudWrite(ctx) || ContactTypeRules.isCreate(ctx)) {
      return null;
    }
    try {
      boolean enabled = ContactTypeRules.isEnabled();
      NeoResponse rejection = enabled
          ? ContactTypeRules.validateContactUpdate(ctx, ctx.getRequestBody(), true)
          : null;
      if (rejection == null) {
        ContactTypeTelemetry.beforeUpdate(ctx, ctx.getRequestBody(), enabled, false);
      }
      return rejection;
    } catch (Exception e) {
      log.error("ContactTypeFlagGuardHandler: error validating contact type on {}/{}",
          ctx.getSpecName(), ctx.getEntityName(), e);
      throw new OBException("Error validating the contact type", e);
    }
  }

  /** Emits {@code backend_contact_type_changed} when the saved write changed a type (KPI). */
  @Override
  public NeoResponse afterHandle(NeoContext ctx) {
    if (ContactTypeRules.isCrudWrite(ctx) && !ContactTypeRules.isCreate(ctx)) {
      ContactTypeTelemetry.afterWrite(ctx, ctx.getRecordId());
    }
    return null;
  }
}
