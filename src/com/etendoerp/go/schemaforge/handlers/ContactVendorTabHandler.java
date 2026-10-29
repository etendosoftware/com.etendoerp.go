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

import java.util.List;

import org.openbravo.model.common.businesspartner.BusinessPartner;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoExtension;

/**
 * Contacts spec, Vendor/Creditor tab ({@code C_BPartner}): "at least one contact type" guard
 * (ETP-5791), see {@link ContactTypeFlagGuardHandler}; and an MCP list without a parent keeps the
 * vendors (ETP-5852), see {@link ContactRoleListScope}.
 */
@NeoExtension(spec = "contacts", entity = "vendorCreditor")
public class ContactVendorTabHandler extends ContactTypeFlagGuardHandler {

  @Override
  public List<String> readPredicates(NeoContext context) {
    return ContactRoleListScope.forList(context,
        "e." + BusinessPartner.PROPERTY_VENDOR + " = true");
  }
}
