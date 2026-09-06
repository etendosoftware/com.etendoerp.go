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

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import com.etendoerp.db.extended.data.VectorSource;
import com.etendoerp.db.extended.data.VectorSearchTarget;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.db.extended.vector.VectorException;
import com.etendoerp.db.extended.vector.VectorSearchService;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/** Authenticated global semantic-search endpoint backed by DB Extended's pgvector facade. */
public class NeoVectorSearchEndpoint {
  private static final int DEFAULT_TOP_K = 10;
  private static final int MAX_TOP_K = 50;
  private static final double DEFAULT_MIN_SCORE = 0.60d;
  private static final double DEFAULT_MAX_SCORE = 1d;
  private static final int HTTP_UNPROCESSABLE_ENTITY = 422;
  private final SearchGateway searchGateway;
  private final NamespaceAuthorizer namespaceAuthorizer;
  private TargetSearchGateway targetSearchGateway;
  private NamespaceAuthorizer targetAuthorizer;

  /** Creates the production endpoint backed by DB Extended and authenticated entity access. */
  public NeoVectorSearchEndpoint() {
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

  NeoVectorSearchEndpoint(SearchGateway searchGateway, NamespaceAuthorizer namespaceAuthorizer,
      TargetSearchGateway targetSearchGateway, NamespaceAuthorizer targetAuthorizer) {
    this.searchGateway = searchGateway;
    this.namespaceAuthorizer = namespaceAuthorizer;
    this.targetSearchGateway = targetSearchGateway;
    this.targetAuthorizer = targetAuthorizer;
  }

  NeoResponse handle(HttpServletRequest request) {
    String query = trimToNull(request.getParameter("query"));
    return handle(query, request.getParameter("namespaces"), request.getParameter("targets"),
        request.getParameter("topK"), request.getParameter("minScore"),
        request.getParameter("maxScore"), request.getParameter("metadataFilter"));
  }

  /**
   * Executes the authenticated search contract for non-servlet adapters such as MCP.
   *
   * @param queryValue user query text
   * @param rawNamespaces comma-separated vector source namespaces
   * @param rawTargets comma-separated configured vector target keys
   * @param rawTopK optional maximum number of results
   * @param rawMinScore optional minimum normalized score
   * @param rawMaxScore optional maximum normalized score
   * @param metadataFilter optional metadata filter for namespace searches
   * @return HTTP-style response containing search results or a validation/authorization error
   */
  public NeoResponse handle(String queryValue, String rawNamespaces, String rawTargets,
      String rawTopK, String rawMinScore, String rawMaxScore, String metadataFilter) {
    String query = trimToNull(queryValue);
    List<String> namespaces = parseNamespaces(rawNamespaces);
    List<String> targets = parseNamespaces(rawTargets);
    if (query == null || (namespaces.isEmpty() && targets.isEmpty())) return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
        "Missing required parameter: query and either targets or namespaces are required");
    Integer topK = parseTopK(rawTopK);
    if (topK == null) return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
        "topK must be an integer between 1 and " + MAX_TOP_K);
    ScoreRange scoreRange = parseScoreRange(rawMinScore, rawMaxScore);
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
            trimToNull(metadataFilter), scoreRange.minScore, scoreRange.maxScore)));
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
    /**
     * Executes the search for the requested namespaces.
     * @param namespaces namespaces to search
     * @param query search text
     * @param topK maximum number of results
     * @param metadataFilter optional metadata filter
     * @param minScore minimum score threshold
     * @param maxScore maximum score threshold
     * @return serialized search response
     */
    String search(List<String> namespaces, String query, int topK,
      String metadataFilter, double minScore, double maxScore); }

  /** Executes a target-scoped vector search and returns its JSON response. */
  interface TargetSearchGateway {
    /**
     * Executes the search for the requested entity targets.
     * @param targets entity targets to search
     * @param query search text
     * @param topK maximum number of results
     * @param minScore minimum score threshold
     * @param maxScore maximum score threshold
     * @return serialized search response
     */
    String search(List<String> targets, String query, int topK,
      double minScore, double maxScore); }

  /** Checks whether the current user can read all requested namespaces. */
  interface NamespaceAuthorizer {
    /**
     * Returns whether the current user can access every namespace.
     * @param namespaces namespaces to authorize
     * @return true when all namespaces are readable
     */
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
        for (String namespace : namespaces) {
          String tableId = findSourceTableId(namespace);
          if (tableId == null || !TargetEntityAuthorizer.hasAuthorizedSchemaForgeWindow(tableId)) return false;
          Entity entity = ModelProvider.getInstance().getEntityByTableId(tableId);
          if (entity == null) return false;
          OBContext.getOBContext().getEntityAccessChecker().checkReadable(entity);
        }
        return true;
      } catch (Exception e) { return false; }
    }
    private static String findSourceTableId(String namespace) {
      OBContext.setAdminMode(true);
      try {
        OBCriteria<VectorSource> criteria = OBDal.getInstance().createCriteria(VectorSource.class);
        criteria.add(Restrictions.eq(VectorSource.PROPERTY_NAMESPACE, namespace));
        criteria.add(Restrictions.eq(VectorSource.PROPERTY_ACTIVE, true));
        criteria.setMaxResults(1);
        List<VectorSource> sources = criteria.list();
        return sources.isEmpty() || sources.get(0).getTable() == null
            ? null : sources.get(0).getTable().getId();
      } finally {
        OBContext.restorePreviousMode();
      }
    }
  }

  /** Applies the same entity read authorization to a target's configured physical source. */
  private static final class TargetEntityAuthorizer implements NamespaceAuthorizer {
    private static final String SPEC_PROPERTY_PREFIX = "spec.";

    @Override public boolean isAuthorized(List<String> targets) {
      try {
        for (String target : targets) {
          String tableId = findSourceTableId(target);
          if (tableId == null || !hasAuthorizedSchemaForgeWindow(tableId)) return false;
          Entity entity = ModelProvider.getInstance().getEntityByTableId(tableId);
          if (entity == null) return false;
          OBContext.getOBContext().getEntityAccessChecker().checkReadable(entity);
        }
        return true;
      } catch (Exception e) { return false; }
    }
    private static String findSourceTableId(String target) {
      OBContext.setAdminMode(true);
      try {
        OBCriteria<VectorSearchTarget> criteria = OBDal.getInstance().createCriteria(VectorSearchTarget.class);
        criteria.add(Restrictions.eq(VectorSearchTarget.PROPERTY_SEARCHKEY, target));
        criteria.add(Restrictions.eq(VectorSearchTarget.PROPERTY_ACTIVE, true));
        criteria.setMaxResults(1);
        List<VectorSearchTarget> targets = criteria.list();
        VectorSource source = targets.isEmpty() ? null : targets.get(0).getEtarcVectorSource();
        return source == null || !Boolean.TRUE.equals(source.isActive()) || source.getTable() == null
            ? null : source.getTable().getId();
      } finally {
        OBContext.restorePreviousMode();
      }
    }

    /**
     * Vector targets are a second data surface, so entity DAL access alone is not sufficient.
     * Require an active Schema Forge window for the physical table and apply the same
     * AD_Window_Access decision used by MCP CRUD discovery and execution.
     */
    private static boolean hasAuthorizedSchemaForgeWindow(String tableId) {
      List<String> windowIds = new ArrayList<>();
      OBContext.setAdminMode(true);
      try {
        OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
        criteria.createAlias(SFEntity.PROPERTY_ADTAB, "tab");
        criteria.createAlias(SFEntity.PROPERTY_ETGOSFSPEC, "spec");
        criteria.add(Restrictions.in("spec.client.id",
            (Object[]) OBContext.getOBContext().getReadableClients()));
        criteria.add(Restrictions.eq("tab.table.id", tableId));
        criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
        criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
        criteria.add(Restrictions.eq(SPEC_PROPERTY_PREFIX + SFSpec.PROPERTY_ISACTIVE, true));
        criteria.add(Restrictions.eq(SPEC_PROPERTY_PREFIX + SFSpec.PROPERTY_SHOWINMCP, true));
        criteria.add(Restrictions.eq(SPEC_PROPERTY_PREFIX + SFSpec.PROPERTY_SPECTYPE, "W"));
        criteria.add(Restrictions.isNotNull(SPEC_PROPERTY_PREFIX + SFSpec.PROPERTY_ADWINDOW));
        for (SFEntity entity : criteria.list()) {
          windowIds.add(entity.getETGOSFSpec().getADWindow().getId());
        }
      } finally {
        OBContext.restorePreviousMode();
      }
      for (String windowId : windowIds) {
        if (NeoAccessHelper.hasWindowAccess(windowId, "GET")) return true;
      }
      return false;
    }
  }
}
