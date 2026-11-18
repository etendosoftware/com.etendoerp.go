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

package com.etendoerp.go.schemaforge.handlers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.common.businesspartner.BankAccount;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoExtension;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Contacts spec, bank account tab ({@code C_BP_BankAccount}): the IBAN is stored normalized —
 * without whitespace and upper-cased — on every write channel (REST single, {@code /batch}, MCP),
 * since all of them run this customization before the generic write (ETP-6247).
 *
 * <p>Why: Classic's SEPA generators write the stored IBAN verbatim into the remittance file, and
 * the ISO 20022 schema rejects {@code "ES91 2100 0418 4502 0005 1332"}. Normalizing on the way in
 * keeps every new or edited account usable for a remittance; IBANs stored with spaces before this
 * customization existed are still reported by the remittance's {@code fileReadiness}
 * ({@code ETGO_RemittanceFileLineInvalidIban}) until the account is saved again.
 *
 * <p>Only the IBAN the body sends is touched — under {@code iBAN} or any casing of it (the AD
 * column {@code Iban}), in the body or its {@code {"data": {...}}} envelope: an absent key,
 * {@code null} or a non-string value is left to the generic write. Nothing else of the request
 * changes.
 */
@NeoExtension(spec = "contacts", entity = "bankAccount")
public class ContactBankAccountHandler implements NeoHandler {

  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH");
  /** The DAL property; its AD column {@code Iban} and any other casing match too. */
  static final String IBAN_KEY = BankAccount.PROPERTY_IBAN;

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoEndpointType type = context.getEndpointType();
    boolean crud = type == null || type == NeoEndpointType.CRUD;
    if (crud && WRITE_METHODS.contains(context.getHttpMethod())) {
      normalizeIban(context.getRequestBody());
    }
    return null;
  }

  /**
   * Normalizes the IBAN in place wherever the body carries it: in the body itself and in its
   * {@code {"data": {...}}} envelope ({@link ContactSepaMandateDefaults#writeSections}), under the
   * DAL property {@code iBAN} or any spelling of it that matches ignoring case — the AD column
   * {@code Iban}, {@code IBAN}, {@code iban} — since the downstream write resolves AD names
   * case-insensitively. Each key keeps its own spelling.
   *
   * @param body the write body, may be {@code null}
   */
  static void normalizeIban(JSONObject body) {
    if (body == null) {
      return;
    }
    for (JSONObject section : ContactSepaMandateDefaults.writeSections(body)) {
      for (String key : ibanKeys(section)) {
        Object raw = section.opt(key);
        if (raw instanceof String) {
          putQuietly(section, key, normalize((String) raw));
        }
      }
    }
  }

  /** The keys of {@code section} that name the IBAN, in any case. */
  private static List<String> ibanKeys(JSONObject section) {
    List<String> keys = new ArrayList<>();
    Iterator<?> it = section.keys();
    while (it.hasNext()) {
      String key = String.valueOf(it.next());
      if (IBAN_KEY.equalsIgnoreCase(key)) {
        keys.add(key);
      }
    }
    return keys;
  }

  private static void putQuietly(JSONObject section, String key, String value) {
    try {
      section.put(key, value);
    } catch (JSONException e) {
      // A string key always accepts a string value; nothing to recover.
    }
  }

  /**
   * @param iban an IBAN as typed
   * @return the IBAN without any whitespace (non-breaking spaces included), upper-cased
   */
  static String normalize(String iban) {
    return iban.replaceAll("[\\s\\p{Z}]+", "").toUpperCase(Locale.ROOT);
  }
}
