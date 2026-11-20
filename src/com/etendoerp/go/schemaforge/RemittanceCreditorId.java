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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.module.remittance.Remittance;

/** SEPA creditor identifier saved on the remittance and defaulted from its financial account. */
final class RemittanceCreditorId {

  /**
   * DAL property of {@code FIN_FINANCIAL_ACCOUNT.EM_REM_CREDITOR_IDENTIFIER}: the AD column name
   * {@code EM_REM_CreditorIdentifier} without its {@code EM_} prefix, camel-cased, first letter
   * lower-cased ({@code NamingUtil}).
   */
  static final String PROPERTY = "rEMCreditorIdentifier";

  /**
   * DAL property of {@code REM_REMITTANCE.CREDITOR_IDENTIFIER}: the remittance's own copy, the
   * one its file carries. The AD column is named {@code CreditorIdentifier}.
   */
  static final String REMITTANCE_PROPERTY = "creditorIdentifier";

  /**
   * The remittance's own copy, normalized, valid or not.
   *
   * @param remittance the remittance, may be {@code null}
   * @return the copy, or {@code null} when none
   */
  static String snapshot(Remittance remittance) {
    Object value = remittance == null ? null : remittance.get(REMITTANCE_PROPERTY);
    return value == null ? null : StringUtils.trimToNull(SepaCreditorId.normalize(value.toString()));
  }

  /**
   * The identifier the remittance would use: its own copy, else its account's default, valid or
   * not. A present copy is never replaced by the account's.
   *
   * @param remittance the remittance
   * @return the identifier, or {@code null} when none
   */
  static String effective(Remittance remittance) {
    String value = snapshot(remittance);
    return value != null ? value : stored(remittance.getFinancialAccount());
  }

  /**
   * The identifier its SEPA file carries: {@link #effective} when valid; with none at all, the one
   * computed from the organization when it can be formed; else {@code null} (also for an invalid
   * one, which is never replaced). A
   * remittance without a copy gets the account's valid default as its copy (an old remittance, or
   * one whose copy was never made), in the caller's transaction, so later edits of the account do
   * not change what a regeneration writes.
   *
   * @param remittance the remittance being generated
   * @return the valid identifier, or {@code null}
   */
  static String forFile(Remittance remittance) {
    String value = effective(remittance);
    if (value == null) {
      // None on the remittance nor its account: the one computed from the organization, kept as
      // the remittance's COPY (never written to the account), when it can be formed.
      value = suggested(remittance.getOrganization());
      if (value != null) {
        remittance.set(REMITTANCE_PROPERTY, value);
      }
      return value;
    }
    if (!SepaCreditorId.isValid(value)) {
      return null;
    }
    if (snapshot(remittance) == null) {
      remittance.set(REMITTANCE_PROPERTY, value);
    }
    return value;
  }

  private RemittanceCreditorId() {
  }

  /**
   * The identifier stored on {@code account}, normalized (upper case, no separators), valid or not.
   *
   * @param account the financial account, may be {@code null}
   * @return the stored value, or {@code null} when none
   */
  static String stored(FIN_FinancialAccount account) {
    if (account == null) {
      return null;
    }
    Object value = account.get(PROPERTY);
    String normalized = value != null ? SepaCreditorId.normalize(value.toString()) : "";
    return normalized.isEmpty() ? null : normalized;
  }

  /**
   * The identifier computed from an organization, as Classic's generator would write it: its
   * country, the mod-97 check digits, the creditor business code and its tax ID.
   *
   * @param info         the organization's own information, may be {@code null}
   * @param businessCode the stored creditor business code, raw
   * @return the computed identifier, or {@code null} when the organization has no country or tax
   *         ID, or they cannot form one
   */
  static String suggested(OrganizationInformation info, String businessCode) {
    String country = StringUtils.trimToNull(RemittanceSepaFileDataAction.countryIsoOf(info));
    String tax = info != null ? StringUtils.trimToNull(info.getTaxID()) : null;
    return country != null && tax != null
        ? SepaCreditorId.build(country, RemittanceSepaFileData.suffixOf(businessCode), tax)
        : null;
  }

  /**
   * The computed identifier of an organization: its own information (no parent fallback, as the
   * generator reads it) and the client's creditor business code.
   *
   * @param organization the organization, may be {@code null}
   * @return the computed identifier, or {@code null}
   */
  static String suggested(Organization organization) {
    if (organization == null) {
      return null;
    }
    List<OrganizationInformation> infos = organization.getOrganizationInformationList();
    OrganizationInformation info = infos == null || infos.isEmpty() ? null : infos.get(0);
    return suggested(info, RemittanceSepaProvisioning.creditorBusinessCode(
        organization.getClient()));
  }

  /**
   * Saves a validated, normalized identifier on {@code account}. The caller validates
   * ({@link SepaCreditorId#validate}) and runs in a transaction.
   *
   * @param account    the financial account
   * @param normalized the identifier, from {@link SepaCreditorId.Result#getNormalized()}
   */
  static void store(FIN_FinancialAccount account, String normalized) {
    account.set(PROPERTY, normalized);
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
  }
}
