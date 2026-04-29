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
package com.etendoerp.go.rest;

import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;

final class EtendoGoDalHelper {

  private EtendoGoDalHelper() {
  }

  static void commitDalChanges(String operation, Logger log) {
    try {
      OBDal.getInstance().commitAndClose();
    } catch (Exception commitEx) {
      log.error("Commit failed after {}", operation, commitEx);
      throw commitEx;
    }
  }

  static void rollbackDalChanges(String operation, Exception failure, Logger log) {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (Exception rollbackEx) {
      log.error("Rollback failed after {}", operation, rollbackEx);
      log.debug("Original failure while handling {}", operation, failure);
    }
  }
}
