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

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The fiscal-territory x fiscal-system (SIF) rules of the Fiscal Configuration onboarding, on the
 * server (ETP-6053). Pure: no database, no session, so it is unit-testable on its own.
 *
 * <p>It is the Java twin of the SPA's onboarding tables in {@code etendo_schema_forge}
 * ({@code tools/app-shell/src/windows/custom/fiscal-config/fiscalConfig.utils.js}:
 * {@code TERRITORY_ALLOWED_SYSTEMS} and {@code buildOnboardingPayloads}). The two copies must
 * change together — the duplication is recorded as feature debt; the SPA can drop its copy once
 * it calls {@code configureFiscalSystem} itself.</p>
 *
 * <p>Everything here belongs to the three fiscal-configuration windows ({@code sii-config},
 * {@code tbai-config}, {@code verifactu-config}); it is called only from their customizations and
 * from the {@code organization} action that creates them, never from the generic engine.</p>
 */
public final class FiscalSifRules {

  /** SII (AEAT / Navarra). */
  public static final String SII = "SII";
  /** TicketBAI (Basque Country). */
  public static final String TBAI = "TBAI";
  /** SII + TicketBAI together (Basque Country with national SII operations). */
  public static final String SII_TBAI = "SII+TBAI";
  /** VERI*FACTU. */
  public static final String VERIFACTU = "VERIFACTU";

  public static final String NAVARRA = "navarra";
  public static final String ALAVA = "alava";
  public static final String BIZKAIA = "bizkaia";
  public static final String GIPUZKOA = "gipuzkoa";
  public static final String BALEARES = "baleares";
  public static final String CANARIAS = "canarias";
  public static final String CEUTA = "ceuta";

  /** Every system a caller may ask for, in the SPA's presentation order. */
  public static final List<String> SYSTEMS = List.of(SII, TBAI, SII_TBAI, VERIFACTU);

  /** Every territory, in the SPA's presentation order. */
  public static final List<String> TERRITORIES = List.of(NAVARRA, ALAVA, BIZKAIA, GIPUZKOA,
      BALEARES, CANARIAS, CEUTA);

  /** The Basque territories: the only ones where SII is added on top of an existing TicketBAI. */
  public static final Set<String> BASQUE_TERRITORIES = Set.of(ALAVA, BIZKAIA, GIPUZKOA);

  /**
   * {@code TERRITORY_ALLOWED_SYSTEMS} of the SPA. Ceuta/Melilla is VERI*FACTU only: SII cannot
   * represent an IPSI taxpayer ({@code AEATSII_CONFIG.TAXTYPE} only lists IVA/IGIC).
   */
  private static final Map<String, List<String>> ALLOWED;

  /** TicketBAI territory ({@code TBAI_CONFIG.ETSG_SIF_TERRITORY}) by territory. */
  private static final Map<String, String> TBAI_TERRITORY = Map.of(
      ALAVA, "ARABA", BIZKAIA, "BIZKAIA", GIPUZKOA, "GIPUZKOA");

  /** VERI*FACTU tax type ({@code ETVFAC_VERIFACTU_CONFIG.TAX_TYPE}) by territory. */
  private static final Map<String, String> VERIFACTU_TAX_TYPE = Map.of(
      BALEARES, "01", CANARIAS, "03", CEUTA, "02");

  static {
    Map<String, List<String>> allowed = new LinkedHashMap<>();
    allowed.put(NAVARRA, List.of(SII));
    allowed.put(ALAVA, List.of(TBAI, SII_TBAI));
    allowed.put(BIZKAIA, List.of(TBAI, SII_TBAI));
    allowed.put(GIPUZKOA, List.of(TBAI, SII_TBAI));
    allowed.put(BALEARES, List.of(SII, VERIFACTU));
    allowed.put(CANARIAS, List.of(SII, VERIFACTU));
    allowed.put(CEUTA, List.of(VERIFACTU));
    ALLOWED = Collections.unmodifiableMap(allowed);
  }

  private FiscalSifRules() {
  }

  /**
   * @param territory a territory id
   * @return the systems the territory allows, empty for an unknown territory
   */
  public static List<String> allowedSystems(String territory) {
    List<String> allowed = territory == null ? null : ALLOWED.get(territory);
    return allowed == null ? Collections.emptyList() : allowed;
  }

  /**
   * @param system    a system
   * @param territory a territory id
   * @return whether the onboarding offers {@code system} for {@code territory}
   */
  public static boolean isAllowed(String system, String territory) {
    return allowedSystems(territory).contains(system);
  }

  /**
   * The systems a request activates, one config record each: SII+TBAI is two records.
   *
   * @param system a system
   * @return the record systems ({@link #SII}, {@link #TBAI}, {@link #VERIFACTU})
   */
  public static List<String> recordSystems(String system) {
    if (SII_TBAI.equals(system)) {
      return List.of(SII, TBAI);
    }
    return system == null ? Collections.emptyList() : List.of(system);
  }

  /**
   * The TicketBAI territory value a territory maps to ({@code ARABA}/{@code BIZKAIA}/
   * {@code GIPUZKOA}), or {@code null} outside the Basque Country.
   *
   * @param territory a territory id
   * @return the {@code ETSG_SIF_TERRITORY} value, or {@code null}
   */
  public static String tbaiTerritory(String territory) {
    return territory == null ? null : TBAI_TERRITORY.get(territory);
  }

  /**
   * The territory a TicketBAI territory value belongs to — the inverse of {@link #tbaiTerritory}.
   *
   * @param tbaiTerritory {@code ARABA}, {@code BIZKAIA} or {@code GIPUZKOA}
   * @return the territory id, or {@code null}
   */
  public static String territoryOfTbai(String tbaiTerritory) {
    for (Map.Entry<String, String> e : TBAI_TERRITORY.entrySet()) {
      if (e.getValue().equals(tbaiTerritory)) {
        return e.getKey();
      }
    }
    return null;
  }

  /**
   * The body of each record the onboarding creates for {@code system} in {@code territory}, by
   * record system — {@code buildOnboardingPayloads} of the SPA, field for field. Derived values
   * (SII {@code navarra}/{@code guipuzcoa}/{@code taxtype}, TicketBAI {@code etsgSifTerritory},
   * VERI*FACTU {@code tAXType}) come from the territory and nowhere else.
   *
   * @param system    a system allowed for {@code territory}
   * @param territory a territory id
   * @param today     the adoption day, {@code yyyy-MM-dd} in the local calendar
   * @return the bodies in creation order (SII before TBAI), empty when the pair is not allowed
   * @throws JSONException never in practice
   */
  public static Map<String, JSONObject> onboardingPayloads(String system, String territory,
      String today) throws JSONException {
    Map<String, JSONObject> payloads = new LinkedHashMap<>();
    if (!isAllowed(system, territory)) {
      return payloads;
    }
    for (String recordSystem : recordSystems(system)) {
      payloads.put(recordSystem, recordPayload(recordSystem, territory, today));
    }
    return payloads;
  }

  /**
   * The body of one record. Also used for the SII record added on top of an existing TicketBAI
   * ("Añadir SII"), which gets exactly the SII part of the SII+TBAI onboarding for the territory.
   *
   * @param recordSystem {@link #SII}, {@link #TBAI} or {@link #VERIFACTU}
   * @param territory    a territory id
   * @param today        the adoption day, {@code yyyy-MM-dd}
   * @return the body
   * @throws JSONException never in practice
   */
  public static JSONObject recordPayload(String recordSystem, String territory, String today)
      throws JSONException {
    JSONObject body = new JSONObject();
    if (SII.equals(recordSystem)) {
      if (NAVARRA.equals(territory)) {
        body.put("navarra", "Y");
      }
      if (GIPUZKOA.equals(territory)) {
        body.put("guipuzcoa", "Y");
      }
      body.put("taxtype", CANARIAS.equals(territory) ? "IGIC" : "IVA");
      body.put("acogidaAlSII", "N");
      body.put("entornoDeProduccin", "Y");
      body.put("adjuntarArchivosXML", "Y");
      body.put("fechaAcogidaSII", today);
      body.put("monitordate", today);
    } else if (TBAI.equals(recordSystem)) {
      body.put("etsgSifTerritory", tbaiTerritory(territory));
      body.put("tbaisystemdate", today);
      body.put("productionEnv", "Y");
      body.put("uSEAsproductDesc", "N");
      body.put("validatePreviousInvoice", "N");
    } else if (VERIFACTU.equals(recordSystem)) {
      body.put("tAXType", VERIFACTU_TAX_TYPE.get(territory));
      body.put("nextSendWaitTime", "60");
      body.put("defaultQR", "Y");
    }
    return body;
  }

  /**
   * The territories that allow {@code system}, for a refusal that says what would be accepted.
   *
   * @param system a system
   * @return the territory ids, in presentation order
   */
  public static List<String> territoriesAllowing(String system) {
    List<String> territories = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : ALLOWED.entrySet()) {
      if (e.getValue().contains(system)) {
        territories.add(e.getKey());
      }
    }
    return territories;
  }
}
