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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;

/**
 * The {@code configureFiscalSystem} action of {@code organization/organization} (ETP-6053): the
 * Fiscal Configuration onboarding wizard of the SPA, for agents. {@code id} is the organization.
 *
 * <p>It activates a billing system (SIF) — SII, TicketBAI, SII + TicketBAI or VERI*FACTU — with
 * the rules the wizard applies:</p>
 * <ul>
 *   <li>the territory is mandatory and must allow the system ({@link FiscalSifRules});</li>
 *   <li>never in forced fiscal test mode ({@code ETSG_ForceTestMode});</li>
 *   <li>only when the organization has no active SIF — except adding SII to an existing TicketBAI
 *       in the same Basque territory, the SPA's "Añadir SII";</li>
 *   <li>every value the wizard derives from the territory (SII {@code navarra}/{@code guipuzcoa}/
 *       {@code taxtype}, TicketBAI territory, VERI*FACTU tax type) is derived here too and cannot
 *       be supplied.</li>
 * </ul>
 *
 * <p>The records are created through the normal CRUD path — {@link BatchService}, the same
 * pipeline as {@code POST /sws/neo/batch} — so each entity's customization runs as it does for
 * the wizard (TicketBAI sequences, auto-send schedules, VERI*FACTU adoption date), the role's
 * access to each fiscal window is checked, and SII + TicketBAI are created atomically.</p>
 *
 * <p>When the organization has no active signing certificate the answer carries a warning saying
 * it must be uploaded from Configuración fiscal: the certificate cannot be uploaded through the
 * API for agents.</p>
 */
public class FiscalSystemConfigureAction {

  private static final Logger log = LogManager.getLogger(FiscalSystemConfigureAction.class);

  /** The action name. */
  public static final String ACTION = "configureFiscalSystem";
  static final String P_SYSTEM = "system";
  static final String P_TERRITORY = "territory";

  static final String MSG_NOT_ALLOWED = "ETGO_FiscalSifNotAllowed";
  static final String MSG_ALREADY_CONFIGURED = "ETGO_FiscalSifAlreadyConfigured";
  static final String MSG_TERRITORY_MISMATCH = "ETGO_FiscalSifTerritoryMismatch";
  static final String MSG_ORG_MISMATCH = "ETGO_FiscalSifOrgMismatch";
  static final String MSG_NO_CERTIFICATE = "ETGO_FiscalSifNoCertificate";

  private static final String METHOD_POST = "POST";

  /** spec / entity of each record system. */
  private static final Map<String, String[]> ENDPOINTS = Map.of(
      FiscalSifRules.SII, new String[] { "sii-config", "siiConfiguration" },
      FiscalSifRules.TBAI, new String[] { "tbai-config", "header" },
      FiscalSifRules.VERIFACTU, new String[] { "verifactu-config",
          "cabeceraDeConfiguraciónVerifactu" });

  private static final Map<String, NeoActionContract> CONTRACTS = Collections.singletonMap(ACTION,
      NeoActionContract.write(ACTION,
          "Activates the organization's billing system (SIF), as the Fiscal Configuration "
              + "onboarding does: creates the SII, TicketBAI or VERI*FACTU configuration (two "
              + "records for SII+TBAI) with every value the territory implies. Refused when the "
              + "territory does not allow the system, when fiscal test mode is forced, and when "
              + "the organization already has an active SIF — except system SII on an "
              + "organization with TicketBAI, which adds SII to it (same Basque territory). "
              + "Changing or switching off a SIF, and uploading the digital certificate, are only "
              + "done in the app (Configuración fiscal). Returns the created records and, when "
              + "the organization has no certificate, a warning to upload it in the app.",
          Param.requiredOptions(P_SYSTEM,
              "The billing system: SII, TBAI (TicketBAI), SII+TBAI (both, Basque Country with "
                  + "national operations) or VERIFACTU.", FiscalSifRules.SYSTEMS),
          Param.requiredOptions(P_TERRITORY,
              "The fiscal territory: navarra (SII), alava / bizkaia / gipuzkoa (TBAI or SII+TBAI), "
                  + "baleares = Spain common territory and Balearic Islands, canarias (SII or "
                  + "VERIFACTU), ceuta = Ceuta and Melilla (VERIFACTU only).",
              FiscalSifRules.TERRITORIES))
          .withIdDescription("the organization id"));

  /** The CRUD pipeline the records go through. A seam for unit tests. */
  private final Function<JSONArray, JSONObject> batch;
  /** "Today", as the wizard's {@code todayIsoDate}. A seam for unit tests. */
  private final Supplier<LocalDate> today;
  /** The organization's certificate expiry, {@code null} without one. A seam for unit tests. */
  private final Function<String, Date> certificateExpiry;

  /** Production wiring. */
  public FiscalSystemConfigureAction() {
    this(FiscalSystemConfigureAction::runBatch, LocalDate::now,
        NeoCertificateHelper::activeCertificateExpiry);
  }

  FiscalSystemConfigureAction(Function<JSONArray, JSONObject> batch, Supplier<LocalDate> today,
      Function<String, Date> certificateExpiry) {
    this.batch = batch;
    this.today = today;
    this.certificateExpiry = certificateExpiry;
  }

  /** @return the declared action */
  public static Map<String, NeoActionContract> actionContracts() {
    return CONTRACTS;
  }

  /**
   * @param action an action name
   * @return whether this class serves it
   */
  public static boolean serves(String action) {
    return ACTION.equals(action);
  }

  /**
   * Runs the action.
   *
   * @param context the ACTION context; {@code recordId} is the organization
   * @return the answer, or {@code null} when {@code context} is not this action
   */
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !serves(context.getFieldName())) {
      return null;
    }
    if (!METHOD_POST.equalsIgnoreCase(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed.");
    }
    JSONObject params = context.getRequestBody() != null ? context.getRequestBody()
        : new JSONObject();
    NeoResponse invalid = NeoActionContract.validate(CONTRACTS, ACTION, params);
    if (invalid != null) {
      return invalid;
    }
    String orgId = context.getRecordId();
    if (orgId == null || !isReadable(orgId)
        || OBDal.getInstance().get(Organization.class, orgId) == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Organization not found");
    }
    try {
      return configure(context, orgId, params.getString(P_SYSTEM),
          params.getString(P_TERRITORY));
    } catch (JSONException e) {
      log.error("configureFiscalSystem failed on organization {}", orgId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not configure the fiscal system. Please check logs for details.");
    }
  }

  private NeoResponse configure(NeoContext context, String orgId, String system,
      String territory) throws JSONException {
    // The records land in the session's organization (organization is server-owned), so a
    // different id would create them somewhere the caller did not ask for.
    if (!orgId.equals(FiscalSifGuard.currentOrganizationId(context.getObContext()))) {
      return FiscalSifGuard.refusal(HttpServletResponse.SC_BAD_REQUEST, MSG_ORG_MISMATCH);
    }
    if (FiscalSifGuard.isForceTestMode(context.getObContext())) {
      return FiscalSifGuard.refusal(HttpServletResponse.SC_CONFLICT, FiscalSifGuard.MSG_TEST_MODE);
    }
    Map<String, String> active = FiscalSifGuard.activeRecords(orgId);
    boolean addingSii = isAddingSiiToTbai(system, territory, active);
    if (!addingSii && !FiscalSifRules.isAllowed(system, territory)) {
      return FiscalSifGuard.refusal(HttpServletResponse.SC_BAD_REQUEST, MSG_NOT_ALLOWED,
          FiscalSifGuard.displayName(system), territory,
          String.join(", ", displayNames(FiscalSifRules.allowedSystems(territory))));
    }
    if (addingSii) {
      String tbaiTerritory = FiscalSifGuard.tbaiTerritoryOf(active.get(FiscalSifRules.TBAI));
      if (tbaiTerritory != null
          && !tbaiTerritory.equals(FiscalSifRules.tbaiTerritory(territory))) {
        return FiscalSifGuard.refusal(HttpServletResponse.SC_BAD_REQUEST, MSG_TERRITORY_MISMATCH,
            tbaiTerritory);
      }
    } else if (!active.isEmpty()) {
      return FiscalSifGuard.refusal(HttpServletResponse.SC_CONFLICT, MSG_ALREADY_CONFIGURED,
          String.join(" + ", displayNames(new ArrayList<>(active.keySet()))));
    }

    String day = today.get().toString();
    Map<String, JSONObject> payloads = addingSii
        ? Collections.singletonMap(FiscalSifRules.SII,
            FiscalSifRules.recordPayload(FiscalSifRules.SII, territory, day))
        : FiscalSifRules.onboardingPayloads(system, territory, day);

    JSONArray operations = new JSONArray();
    for (Map.Entry<String, JSONObject> e : payloads.entrySet()) {
      String[] endpoint = ENDPOINTS.get(e.getKey());
      operations.put(new JSONObject()
          .put("id", e.getKey())
          .put("spec", endpoint[0])
          .put("entity", endpoint[1])
          .put("body", e.getValue()));
    }
    JSONObject outcome = batch.apply(operations);
    if (outcome == null || !outcome.optBoolean(BatchService.FIELD_COMMITTED, false)) {
      return batchFailure(outcome);
    }
    return success(context, orgId, addingSii ? FiscalSifRules.SII_TBAI : system, territory,
        outcome);
  }

  private static boolean isAddingSiiToTbai(String system, String territory,
      Map<String, String> active) {
    return FiscalSifRules.SII.equals(system)
        && FiscalSifRules.BASQUE_TERRITORIES.contains(territory)
        && active.size() == 1 && active.containsKey(FiscalSifRules.TBAI);
  }

  private NeoResponse success(NeoContext context, String orgId, String resultingSystem,
      String territory, JSONObject outcome) throws JSONException {
    JSONArray created = new JSONArray();
    JSONArray ops = outcome.optJSONArray("operations");
    for (int i = 0; ops != null && i < ops.length(); i++) {
      JSONObject op = ops.getJSONObject(i);
      String recordSystem = op.optString("id");
      String[] endpoint = ENDPOINTS.get(recordSystem);
      created.put(new JSONObject()
          .put(P_SYSTEM, recordSystem)
          .put("spec", endpoint != null ? endpoint[0] : null)
          .put("entity", endpoint != null ? endpoint[1] : null)
          .put("id", op.optString("recordId", null)));
    }
    boolean hasCertificate = certificateExpiry.apply(orgId) != null;
    JSONObject body = new JSONObject()
        .put(P_SYSTEM, resultingSystem)
        .put(P_TERRITORY, territory)
        .put("created", created)
        .put("certificate", new JSONObject().put("exists", hasCertificate));
    if (!hasCertificate) {
      NeoHints.addMessage(body, context.isMcpOrigin(), NeoHints.TYPE_WARNING,
          FiscalSifGuard.text(MSG_NO_CERTIFICATE));
    }
    return NeoResponse.ok(body);
  }

  /** The batch refusal as this action's own error: status, message and message keys kept. */
  private static NeoResponse batchFailure(JSONObject outcome) throws JSONException {
    JSONObject error = outcome != null ? outcome.optJSONObject("error") : null;
    int status = error != null ? error.optInt("status", HttpServletResponse.SC_BAD_REQUEST)
        : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    String message = error != null ? error.optString("message", null) : null;
    NeoResponse response = NeoResponse.error(status,
        StringUtils.defaultIfBlank(message, "Could not configure the fiscal system."));
    JSONArray keys = error != null ? findMessageKeys(error) : null;
    if (keys != null && response.getBody() != null) {
      response.getBody().getJSONObject("error").put(NeoProcessService.MESSAGE_KEYS, keys);
    }
    return response;
  }

  private static JSONArray findMessageKeys(JSONObject error) {
    JSONArray keys = error.optJSONArray(NeoProcessService.MESSAGE_KEYS);
    if (keys != null) {
      return keys;
    }
    JSONObject detail = error.optJSONObject("detail");
    JSONObject nested = detail != null ? detail.optJSONObject("error") : null;
    return nested != null ? nested.optJSONArray(NeoProcessService.MESSAGE_KEYS) : null;
  }

  private static List<String> displayNames(List<String> systems) {
    List<String> names = new ArrayList<>(systems.size());
    systems.forEach(s -> names.add(FiscalSifGuard.displayName(s)));
    return names;
  }

  private static boolean isReadable(String orgId) {
    String[] readable = OBContext.getOBContext().getReadableOrganizations();
    return readable != null && Arrays.asList(readable).contains(orgId);
  }

  private static JSONObject runBatch(JSONArray operations) {
    try {
      return BatchService.forBatchOnly().executeBatch(operations);
    } catch (JSONException e) {
      log.error("configureFiscalSystem: the batch could not be built", e);
      return null;
    }
  }
}
