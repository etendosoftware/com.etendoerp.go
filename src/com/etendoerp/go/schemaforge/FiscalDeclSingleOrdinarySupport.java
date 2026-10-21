/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.util.function.Predicate;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;

/**
 * The per-model "single ordinary declaration" rule of {@link FiscalDeclCrudHandler} (ETP-5716,
 * Modelo 390: filed once per exercise). Extracted from the CRUD handler like its other
 * {@code FiscalDecl*Support} clusters, to keep it under the SonarQube {@code java:S1448}
 * method-count threshold.
 */
class FiscalDeclSingleOrdinarySupport {

  /** Error code of the 409 {@link #rejectDuplicateOrdinary} answers. */
  static final String ERR_DUPLICATE_ORDINARY = "DUPLICATE_ORDINARY";

  private final FiscalDeclCrudHandler owner;

  FiscalDeclSingleOrdinarySupport(FiscalDeclCrudHandler owner) {
    this.owner = owner;
  }

  /**
   * Whether a model allows a single ORDINARY declaration per natural key (ETP-5716). Not decided
   * here: each model's handler says so ({@link AbstractFiscalHandler#allowsSingleOrdinary}, e.g.
   * the Modelo 390, filed once per exercise — a later filing is a sustitutiva/complementaria,
   * {@code type: "com"}, which stays allowed), wired by
   * {@link AbstractFiscalHandler#linkSubmittedSnapshotProviders}. Default: none (the 303/349
   * rectificativa flow re-files an ordinary for the same period, ETP-5187).
   */
  private Predicate<String> singleOrdinaryModel = model -> false;

  /**
   * Whether {@code model} allows a single ordinary declaration (see {@link #singleOrdinaryModel}).
   *
   * @param model the AEAT model code
   * @return {@code true} for a single-ordinary model
   */
  boolean isSingleOrdinaryModel(String model) {
    return singleOrdinaryModel.test(model);
  }

  /** Installs the per-model "single ordinary" rule (see {@link #singleOrdinaryModel}). */
  void setSingleOrdinaryModel(Predicate<String> rule) {
    this.singleOrdinaryModel = rule != null ? rule : model -> false;
  }

  /**
   * For a single-ordinary model ({@link #singleOrdinaryModel}), rejects creating a second ordinary
   * declaration for the natural key with a {@code 409} whose body carries what the UI needs to open the
   * existing one: {@code {"error":{"status":409,"code":"DUPLICATE_ORDINARY","message":...,
   * "existingId":...}}} ({@code existingId} = the latest ordinary, by {@code DECL_SEQ}).
   *
   * @return {@code true} when the response was written and the creation must stop
   * @throws Exception when the 409 answer cannot be written
   */
  boolean rejectDuplicateOrdinary(String clientId, String orgId, String model, long year,
      String period, String requestedDeclType, HttpServletResponse response) throws Exception {
    if (!singleOrdinaryModel.test(model) || !"O".equals(requestedDeclType)) {
      return false;
    }
    BaseOBObject existing = findLatestOrdinary(clientId, orgId, model, year, period);
    if (existing == null) {
      return false;
    }
    JSONObject error = new JSONObject();
    error.put(FiscalDeclCrudHandler.STATUS_KEY, HttpServletResponse.SC_CONFLICT);
    error.put(FiscalDeclCrudHandler.CODE_KEY, ERR_DUPLICATE_ORDINARY);
    error.put(FiscalDeclCrudHandler.MESSAGE_KEY, "An ordinary Modelo " + model
        + " declaration already exists for " + year
        + " — open it, or create a substitute (complementaria) declaration instead.");
    error.put("existingId", existing.getId());
    JSONObject body = new JSONObject();
    body.put("error", error);
    owner.servlet.writeResponse(response, NeoResponse.error(HttpServletResponse.SC_CONFLICT, body));
    return true;
  }

  /** Highest-{@code DECL_SEQ} ordinary ({@code DECL_TYPE = 'O'}) declaration, or {@code null}. */
  private BaseOBObject findLatestOrdinary(String clientId, String orgId, String model, long year,
      String period) {
    BaseOBObject latest = null;
    long maxSeq = -1L;
    for (BaseOBObject existing
        : FiscalDeclCrudHandler.naturalKeyQuery(clientId, orgId, model, year, period).list()) {
      String type = FiscalDeclCrudHandler.asString(
          existing.get(FiscalDeclCrudHandler.PROPERTY_DECLARATION_TYPE)).trim();
      Object rawSeq = existing.get(FiscalDeclCrudHandler.PROPERTY_DECL_SEQ);
      long seq = rawSeq instanceof Number ? ((Number) rawSeq).longValue() : 0L;
      if (!"C".equals(type) && seq > maxSeq) {
        maxSeq = seq;
        latest = existing;
      }
    }
    return latest;
  }
}
