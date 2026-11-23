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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.RemittanceTypeParameter;

/**
 * ETP-6247 — what a provisioned remittance type needs for Classic's SEPA module to write its file:
 * the generator class ({@code REM_Remittance_Type.Classname}) and the {@code
 * REM_Remittance_Type_Param} rows the generator reads.
 *
 * <p>The values are the ones of Classic's own templates ({@code Core_Direct_Debit_Scheme.xml},
 * {@code Credit_Transfer_Scheme.xml}) for the current schema versions — pain.008.001.08 for
 * collections, pain.001.001.09 for payments — with two GO decisions on top: the BIC is not
 * mandatory ({@code BIC_SWIFT_MANDATORY=N}; its format is still checked when present) and the file
 * extension is {@code xml}. Both types also carry {@value #PARAM_SETTLE_ON_DUEDATE}{@code =Y}, the
 * automated-remittance parameter that settles the payments on the remittance due date. The
 * collection type identifies the parties with the AT-02 creditor
 * identifier ({@code Use_AT-02_toGenerateId=Y}), the payment type with the plain tax ID.
 *
 * <p><b>Idempotent and never overwriting.</b> {@link #ensure} only fills what is missing: a blank
 * class name and absent parameters. A value an administrator changed is kept, so a tenant can still
 * move a type to another schema version from Classic. Called by
 * {@link RemittanceSepaProvisioning} when a client is provisioned and by
 * {@link RemittanceFileAction} before a file is generated, which is what backfills the types
 * provisioned before ETP-6247.
 */
final class RemittanceSepaFileSetup {

  /** Classic's pain.008.001.08 SEPA direct debit (CORE) generator. */
  static final String CLASS_CORE =
      "org.openbravo.sepa.iso20022.coredirectdebit.customertobank.pain.x008.x001.x08.report."
          + "CoreDirectDebit";
  /** Classic's pain.001.001.09 SEPA credit transfer generator. */
  static final String CLASS_SCT =
      "org.openbravo.sepa.iso20022.credittransfer.customertobank.pain.x001.x001.x09.report."
          + "CreditTransfer";

  static final String PARAM_FILE_EXTENSION = "FILE_EXTENSION";
  /**
   * The com.etendoerp.automated.remittance parameter ({@code RemittanceProcessHookImpl
   * .PARAM_SETTLE_ON_DUEDATE}, added by ETP-6247 on the module's {@code epic/ETP-3504}): with
   * {@code Y}, the automatic settlement (a remittance an administrator flags automatic from Classic)
   * settles the payments on the remittance due date instead of the invoice date. ETP-6247:
   * GO's own «Cobrada» ({@link RemittanceStatusAction}) always settles on the due date and does not
   * read it. A literal, not the constant: GO does not require that change to build (no version
   * floor), and with a module without it the parameter is simply ignored and the payments settle
   * on the invoice date.
   */
  static final String PARAM_SETTLE_ON_DUEDATE = "ETAR_SETTLE_ON_DUEDATE";
  static final String FILE_EXTENSION = "xml";

  private static final String YES = "Y";
  private static final String NO = "N";

  private static final Map<String, String> COMMON = parameters(
      "STRICT_VALIDATION", YES,
      "Use_Nm_ForIdentifiers", YES,
      "Use_Id_ForIdentifiers", YES,
      "Include_InitiatingPartyId", YES,
      "BIC_SWIFT_MANDATORY", NO,
      "GENERATE_BUSINESSPARTNER_IBAN", NO,
      PARAM_FILE_EXTENSION, FILE_EXTENSION,
      PARAM_SETTLE_ON_DUEDATE, YES);

  private static final Map<String, String> CORE = parameters(
      "Use_AT-02_toGenerateId", YES,
      "CORE_B2B", "CORE");

  private static final Map<String, String> SCT = parameters(
      "Use_AT-02_toGenerateId", NO,
      "GroupBy_Duedate", NO,
      "Business_Code", "ZZZ",
      "DebtorIdentifier:Id+Business_Code", NO);

  private RemittanceSepaFileSetup() {
  }

  /**
   * Completes the file setup of {@code type}. The caller runs in admin mode and flushes.
   *
   * @param type   a provisioned remittance type
   * @param scheme its {@link RemittanceSepaProvisioning#PARAM_SCHEME} marker value; anything other
   *               than {@value RemittanceSepaProvisioning#SCHEME_CORE} or
   *               {@value RemittanceSepaProvisioning#SCHEME_SCT} leaves the type untouched
   * @return whether anything was written
   */
  static boolean ensure(RemittanceType type, String scheme) {
    String generator = generatorOf(scheme);
    if (type == null || generator == null) {
      return false;
    }
    boolean changed = false;
    if (StringUtils.isBlank(type.getJavaClassName())) {
      type.setJavaClassName(generator);
      OBDal.getInstance().save(type);
      changed = true;
    }
    changed |= ensureParameters(type, COMMON);
    changed |= ensureParameters(type,
        RemittanceSepaProvisioning.SCHEME_CORE.equals(scheme) ? CORE : SCT);
    return changed;
  }

  /**
   * The scheme of a provisioned type: the value of its
   * {@value RemittanceSepaProvisioning#PARAM_SCHEME} marker.
   *
   * @param type the remittance type, may be {@code null}
   * @return {@value RemittanceSepaProvisioning#SCHEME_CORE},
   *         {@value RemittanceSepaProvisioning#SCHEME_SCT}, or {@code null} for a type GO did not
   *         provision
   */
  static String schemeOf(RemittanceType type) {
    if (type == null) {
      return null;
    }
    RemittanceTypeParameter marker =
        RemittanceSepaProvisioning.findTypeParameter(type, RemittanceSepaProvisioning.PARAM_SCHEME);
    String scheme = marker != null ? StringUtils.trimToNull(marker.getValue()) : null;
    return generatorOf(scheme) != null ? scheme : null;
  }

  /**
   * @param scheme a scheme marker value
   * @return the generator class of the scheme, or {@code null} for an unknown one
   */
  static String generatorOf(String scheme) {
    if (RemittanceSepaProvisioning.SCHEME_CORE.equals(scheme)) {
      return CLASS_CORE;
    }
    return RemittanceSepaProvisioning.SCHEME_SCT.equals(scheme) ? CLASS_SCT : null;
  }

  /** The parameter values {@link #ensure} writes for {@code scheme}, for tests and docs. */
  static Map<String, String> expectedParameters(String scheme) {
    Map<String, String> all = new LinkedHashMap<>(COMMON);
    all.putAll(RemittanceSepaProvisioning.SCHEME_CORE.equals(scheme) ? CORE : SCT);
    return Collections.unmodifiableMap(all);
  }

  private static boolean ensureParameters(RemittanceType type, Map<String, String> wanted) {
    boolean changed = false;
    for (Map.Entry<String, String> entry : wanted.entrySet()) {
      if (RemittanceSepaProvisioning.findTypeParameter(type, entry.getKey()) == null) {
        RemittanceSepaProvisioning.saveTypeParameter(type, entry.getKey(), entry.getValue());
        changed = true;
      }
    }
    return changed;
  }

  private static Map<String, String> parameters(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return Collections.unmodifiableMap(map);
  }
}
