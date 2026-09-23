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
package com.etendoerp.go.session;

import org.openbravo.base.exception.OBException;

/**
 * Thrown when a session's role was revoked and the user holds no other role it could be rebound
 * to. The session cannot be used for any environment until the user enters one again.
 */
public class SessionRoleRevokedException extends OBException {

  private static final long serialVersionUID = 1L;

  public SessionRoleRevokedException(String message) {
    super(message);
  }
}
