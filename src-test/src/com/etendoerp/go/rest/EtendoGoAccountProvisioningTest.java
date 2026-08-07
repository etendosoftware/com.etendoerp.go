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

import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link EtendoGoAccountProvisioning} — the public cross-package entry point
 * (ETP-4829) that {@code com.etendoerp.go.schemaforge.handlers.UserRoleAssignmentHandler} calls
 * without depending on the package-private {@link EtendoGoJwtDalHelper} directly.
 */
class EtendoGoAccountProvisioningTest {

  @Test
  void ensurePendingAccountDelegatesToDalHelper() {
    try (MockedStatic<EtendoGoJwtDalHelper> dalHelperMock =
        mockStatic(EtendoGoJwtDalHelper.class)) {
      EtendoGoAccountProvisioning.ensurePendingAccount("new.user@test.com", "New User");

      dalHelperMock.verify(
          () -> EtendoGoJwtDalHelper.createPendingAccount("new.user@test.com", "New User"));
    }
  }
}
