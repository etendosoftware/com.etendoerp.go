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
package com.etendoerp.go.schemaforge;

import java.util.Arrays;

import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Who may read and who may write the template configuration of an organization.
 *
 * <p>The NEO request runs in admin mode, and in admin mode the DAL skips the caller's readable and
 * writable organizations, so they are asked here explicitly, from the caller's own
 * {@link OBContext}. An organization outside those sets (another tenant's, or one the role does not
 * reach) is refused the same way whether or not it exists.</p>
 */
class DocTemplateConfigAccess {

  /** The organization the caller is working in, the default when a request names none. */
  String currentOrgId() {
    return OBContext.getOBContext().getCurrentOrganization().getId();
  }

  boolean canRead(String orgId) {
    return Arrays.asList(OBContext.getOBContext().getReadableOrganizations()).contains(orgId);
  }

  boolean canWrite(String orgId) {
    return OBContext.getOBContext().getWritableOrganizations().contains(orgId);
  }

  /** Saving is for an administrator of the client, on top of {@link #canWrite}. */
  boolean isAdmin() {
    Role role = OBContext.getOBContext().getRole();
    return role != null && NeoAccessHelper.isAdminOrClientAdmin(role);
  }
}
