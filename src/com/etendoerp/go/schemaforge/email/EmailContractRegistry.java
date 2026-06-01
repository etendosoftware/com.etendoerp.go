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

package com.etendoerp.go.schemaforge.email;

import java.util.Optional;

/**
 * Registry for server-side email contracts.
 */
@FunctionalInterface
public interface EmailContractRegistry {

  /**
   * Finds a server-side email contract by its stable contract name.
   *
   * @param contractName kebab-case contract name from the NEO route
   * @return matching contract when it is registered
   */
  Optional<EmailContract> find(String contractName);

  /**
   * Creates an empty registry for deployments without registered contracts.
   *
   * @return registry that never resolves a contract
   */
  static EmailContractRegistry empty() {
    return contractName -> Optional.empty();
  }
}
