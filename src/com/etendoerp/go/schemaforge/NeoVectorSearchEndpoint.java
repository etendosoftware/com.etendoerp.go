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
package com.etendoerp.go.schemaforge;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.db.extended.vector.VectorException;
import com.etendoerp.db.extended.vector.VectorSearchService;

/** Authenticated global semantic-search endpoint backed by DB Extended's pgvector facade. */
class NeoVectorSearchEndpoint {
  private static final int DEFAULT_TOP_K = 10;
  private static final int MAX_TOP_K = 50;
  private static final double DEFAULT_MIN_SCORE = 0.60d;
  private static final double DEFAULT_MAX_SCORE = 1d;
  private static final int HTTP_UNPROCESSABLE_ENTITY = 422;
  private final SearchGateway searchGateway;
  private final NamespaceAuthorizer namespaceAuthorizer;
  private TargetSearchGateway targetSearchGateway;
  private NamespaceAuthorizer targetAuthorizer;

  NeoVectorSearchEndpoint() {
    this((namespaces, query, topK, metadataFilter, minScore, maxScore) ->
        new VectorSearchService(new DalConnectionProvider(false))
            .searchAsJson(namespaces, query, topK, metadataFilter, minScore, maxScore),
        new SourceEntityAuthorizer());
    targetSearchGateway = (targets, query, topK, minScore, maxScore) ->
        new VectorSearchService(new DalConnectionProvider(false))
            .searchTargetsAsJson(targets, query, topK, minScore, maxScore);
    targetAuthorizer = new TargetEntityAuthorizer();
  }

  NeoVectorSearchEndpoint(SearchGateway searchGateway) { this(searchGateway, namespaces -> true); }
  NeoVectorSearchEndpoint(SearchGateway searchGateway, NamespaceAuthorizer namespaceAuthorizer) {
    this.searchGateway = searchGateway; this.namespaceAuthorizer = namespaceAuthorizer;
    this.targetSearchGateway = null; this.targetAuthorizer = namespaces -> true;
  }

  NeoResponse handle(HttpServletRequest request) {
    String query = trimToNull(request.getParameter("query"));
    List<String> namespaces = parseNamespaces(request.getParameter("namespaces"));
    List<String> targets = parseNamespaces(request.getParameter("targets"));
    if (query == null || (namespaces.isEmpty() && targets.isEmpty())) return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
        "Missing required parameter: query and either targets or namespaces are required");
    Integer topK = parseTopK(request.getParameter("topK"));
    if (topK == null) return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
        "topK must be an integer between 1 and " + MAX_TOP_K);
    ScoreRange scoreRange = parseScoreRange(request.getParameter("minScore"), request.getParameter("maxScore"));
    if (scoreRange == null) return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
        "minScore and maxScore must be numbers between 0 and 1, with minScore not greater than maxScore");
    try {
      if (!targets.isEmpty()) {
        if (targetSearchGateway == null || !targetAuthorizer.isAuthorized(targets))
          return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN, "Access denied to vector target");
        return NeoResponse.ok(new JSONObject(targetSearchGateway.search(targets, query, topK,
            scoreRange.minScore, scoreRange.maxScore)));
      }
      if (!namespaceAuthorizer.isAuthorized(namespaces))
        return NeoResponse.error(HttpServletResponse.SC_FORBIDDEN, "Access denied to vector source");
      return NeoResponse.ok(new JSONObject(searchGateway.search(namespaces, query, topK,
          trimToNull(request.getParameter("metadataFilter")), scoreRange.minScore, scoreRange.maxScore)));
    } catch (VectorException e) {
      return NeoResponse.error(HTTP_UNPROCESSABLE_ENTITY, e.getCode().name() + ": " + e.getMessage());
    } catch (Exception e) {
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not execute vector search");
    }
  }

  private static List<String> parseNamespaces(String rawNamespaces) {
    List<String> namespaces = new ArrayList<>();
    if (rawNamespaces == null) return namespaces;
    for (String namespace : rawNamespaces.split(",")) { String value = trimToNull(namespace); if (value != null) namespaces.add(value); }
    return namespaces;
  }
  private static Integer parseTopK(String rawTopK) {
    if (trimToNull(rawTopK) == null) return DEFAULT_TOP_K;
    try { int topK = Integer.parseInt(rawTopK); return topK >= 1 && topK <= MAX_TOP_K ? topK : null; }
    catch (NumberFormatException e) { return null; }
  }
  private static ScoreRange parseScoreRange(String rawMinScore, String rawMaxScore) {
    try {
      double minScore = trimToNull(rawMinScore) == null ? DEFAULT_MIN_SCORE : Double.parseDouble(rawMinScore);
      double maxScore = trimToNull(rawMaxScore) == null ? DEFAULT_MAX_SCORE : Double.parseDouble(rawMaxScore);
      return Double.isFinite(minScore) && Double.isFinite(maxScore) && minScore >= 0d && maxScore <= 1d
          && minScore <= maxScore ? new ScoreRange(minScore, maxScore) : null;
    } catch (NumberFormatException e) { return null; }
  }
  private static String trimToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** Executes a namespace-scoped vector search and returns its JSON response. */
  interface SearchGateway {
    /** Executes the search for the requested namespaces. */
    String search(List<String> namespaces, String query, int topK,
      String metadataFilter, double minScore, double maxScore); }

  /** Executes a target-scoped vector search and returns its JSON response. */
  interface TargetSearchGateway {
    /** Executes the search for the requested entity targets. */
    String search(List<String> targets, String query, int topK,
      double minScore, double maxScore); }

  /** Checks whether the current user can read all requested namespaces. */
  interface NamespaceAuthorizer {
    /** Returns whether the current user can access every namespace. */
    boolean isAuthorized(List<String> namespaces);
  }
  private static final class ScoreRange {
    private final double minScore;
    private final double maxScore;
    private ScoreRange(double minScore, double maxScore) { this.minScore = minScore; this.maxScore = maxScore; }
  }

  /** Maps every configured namespace to its source AD table and enforces normal entity read access. */
  private static final class SourceEntityAuthorizer implements NamespaceAuthorizer {
    @Override public boolean isAuthorized(List<String> namespaces) {
      try {
        DalConnectionProvider connectionProvider = new DalConnectionProvider(false);
        for (String namespace : namespaces) {
          String tableId = findSourceTableId(connectionProvider, namespace);
          if (tableId == null) return false;
          Entity entity = ModelProvider.getInstance().getEntityByTableId(tableId);
          if (entity == null) return false;
          OBContext.getOBContext().getEntityAccessChecker().checkReadable(entity);
        }
        return true;
      } catch (Exception e) { return false; }
    }
    private static String findSourceTableId(DalConnectionProvider connectionProvider, String namespace) throws Exception {
      try (PreparedStatement statement = connectionProvider.getPreparedStatement(
          "SELECT ad_table_id FROM etarc_vector_source WHERE namespace = ? AND isactive = 'Y'")) {
        statement.setString(1, namespace);
        try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getString(1) : null; }
      }
    }
  }

  /** Applies the same entity read authorization to a target's configured physical source. */
  private static final class TargetEntityAuthorizer implements NamespaceAuthorizer {
    @Override public boolean isAuthorized(List<String> targets) {
      try {
        DalConnectionProvider connectionProvider = new DalConnectionProvider(false);
        for (String target : targets) {
          String tableId = findSourceTableId(connectionProvider, target);
          if (tableId == null) return false;
          Entity entity = ModelProvider.getInstance().getEntityByTableId(tableId);
          if (entity == null) return false;
          OBContext.getOBContext().getEntityAccessChecker().checkReadable(entity);
        }
        return true;
      } catch (Exception e) { return false; }
    }
    private static String findSourceTableId(DalConnectionProvider connectionProvider, String target) throws Exception {
      String sql = "SELECT s.ad_table_id FROM etarc_vector_search_target t JOIN etarc_vector_source s "
          + "ON s.etarc_vector_source_id=t.etarc_vector_source_id "
          + "WHERE t.search_key=? AND t.isactive='Y' AND s.isactive='Y'";
      try (PreparedStatement statement = connectionProvider.getPreparedStatement(sql)) {
        statement.setString(1, target);
        try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getString(1) : null; }
      }
    }
  }
}
