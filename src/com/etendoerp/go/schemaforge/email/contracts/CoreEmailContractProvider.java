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

package com.etendoerp.go.schemaforge.email.contracts;

import com.etendoerp.go.schemaforge.email.*;

import java.util.Arrays;
import java.util.Collection;

import javax.enterprise.context.ApplicationScoped;

/**
 * Provides non-document built-in email contracts.
 */
@ApplicationScoped
public final class CoreEmailContractProvider implements EmailContractProvider {

  private final EmailContractDataResolver contactResolver;

  public CoreEmailContractProvider() {
    this(new DalEmailContractDataResolver());
  }

  public CoreEmailContractProvider(EmailContractDataResolver contactResolver) {
    this.contactResolver = contactResolver;
  }

  @Override
  public Collection<EmailContract> getContracts() {
    return Arrays.asList(
        new AccountLinkEmailContract("reset-password", "reset-password", contactResolver, 3, 900),
        new AccountLinkEmailContract("new-account", "new-account", contactResolver, 2, 900),
        new LoginAlertEmailContract(contactResolver));
  }
}
