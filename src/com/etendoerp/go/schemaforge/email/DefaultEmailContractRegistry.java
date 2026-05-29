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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.weld.WeldUtils;

/**
 * Default registry for built-in transactional email contracts.
 */
public final class DefaultEmailContractRegistry implements EmailContractRegistry {

  private static final Logger log = LogManager.getLogger(DefaultEmailContractRegistry.class);

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
    return create(loadProviders());
  }

  static DefaultEmailContractRegistry create(EmailContractDataResolver dataResolver) {
    return create(Arrays.asList(new CoreEmailContractProvider(dataResolver),
        new SalesDocumentEmailContractProvider()));
  }

  static DefaultEmailContractRegistry create(List<EmailContractProvider> providers) {
    List<EmailContract> providedContracts = new ArrayList<>();
    for (EmailContractProvider provider : providers) {
      providedContracts.addAll(provider.getContracts());
    }
    return new DefaultEmailContractRegistry(providedContracts);
  }

  private static List<EmailContractProvider> loadProviders() {
    try {
      Collection<EmailContractProvider> injectedProviders = WeldUtils.getInstances(
          EmailContractProvider.class);
      if (!injectedProviders.isEmpty()) {
        return new ArrayList<>(injectedProviders);
      }
    } catch (Exception e) {
      log.debug("Could not load injected email contract providers: {}", e.getMessage(), e);
    }
    return Arrays.asList(new CoreEmailContractProvider(), new SalesDocumentEmailContractProvider());
  }

  @Override
  public Optional<EmailContract> find(String contractName) {
    return Optional.ofNullable(contracts.get(contractName));
  }
}
