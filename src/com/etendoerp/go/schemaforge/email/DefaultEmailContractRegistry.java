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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default registry for built-in transactional email contracts.
 */
public final class DefaultEmailContractRegistry implements EmailContractRegistry {

  private final Map<String, EmailContract> contracts;

  private DefaultEmailContractRegistry(List<EmailContract> contracts) {
    this.contracts = new LinkedHashMap<>();
    for (EmailContract contract : contracts) {
      this.contracts.put(contract.getName(), contract);
    }
  }

  /**
   * Creates the runtime registry backed by DAL record resolution.
   *
   * @return default transactional email registry
   */
  public static DefaultEmailContractRegistry createDefault() {
    return create(new DalEmailContractDataResolver());
  }

  static DefaultEmailContractRegistry create(EmailContractDataResolver dataResolver) {
    return new DefaultEmailContractRegistry(Arrays.asList(
        new AccountLinkEmailContract("reset-password", "reset-password", dataResolver),
        new AccountLinkEmailContract("new-account", "new-account", dataResolver),
        new LoginAlertEmailContract(dataResolver),
        new SalesInvoiceSendEmailContract(dataResolver)));
  }

  @Override
  public Optional<EmailContract> find(String contractName) {
    return Optional.ofNullable(contracts.get(contractName));
  }
}
