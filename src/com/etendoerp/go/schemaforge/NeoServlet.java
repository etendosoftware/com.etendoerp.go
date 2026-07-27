package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.annotation.MultipartConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoErrorSanitizer;
import com.etendoerp.go.schemaforge.webhooks.SFListMenu;
import com.etendoerp.go.schemaforge.webhooks.SFRolesOverview;
import com.etendoerp.go.schemaforge.webhooks.SFWindowAccessMap;
import com.smf.securewebservices.SWSConfig;

/**
 * NEO Headless 2.0 servlet.
 *
 * Mapped to /sws/neo/* via AD_SERVLET registration.
 * Uses JWT authentication via SecureWebServices (same as EtendoGo).
 *
 * URL pattern:
 *   Window specs: /sws/neo/{specName}/{entityName}[/{id}]
 *   Process specs: /sws/neo/{specName}  (POST only)
 *
 * Resolves Spec/Entity/Field records (ETGO_SF_Spec, ETGO_SF_Entity, ETGO_SF_Field)
 * which point directly to AD_Window, AD_Tab, and AD_Column.
 * Hooks are discovered via CDI using the Java_Qualifier on ETGO_SF_Entity.
 */
@MultipartConfig(maxFileSize = 10L * 1024 * 1024, maxRequestSize = 12L * 1024 * 1024, fileSizeThreshold = 1024 * 1024)
public class NeoServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(NeoServlet.class);

  private static final String METHOD_DELETE = "DELETE";
  private static final String METHOD_PATCH = "PATCH";
  private static final String DOCUMENT_DOWNLOAD_PREFIX = "/document-download/";
  static final String ERR_ENTITY_NOT_FOUND = "Entity not found: ";
  static final String ERR_NO_LINKED_TAB = "Entity has no linked AD_Tab: ";
  public static final String ACTION_REQUEST_BODY_ATTR = "neo.action.requestBody";

  final NeoDiscoveryHandler discoveryHandler = new NeoDiscoveryHandler(this);
  private final NeoBuiltInEndpointHandler builtInEndpointHandler =
      new NeoBuiltInEndpointHandler(this, discoveryHandler);
  final NeoButtonHandler buttonHandler = new NeoButtonHandler();
  final NeoDisplayLogicHandler displayLogicHandler = new NeoDisplayLogicHandler();
  // Package-private so sibling collaborators (BatchService) can dispatch through
  // the same default CRUD pipeline without going via HTTP.
  final NeoCrudHandler crudHandler = new NeoCrudHandler(this);
  final NeoAuthenticator authenticator = new NeoAuthenticator(this);
  private final NeoRequestRouter requestRouter = new NeoRequestRouter(this);
  final NeoSubEndpointDispatcher subEndpointDispatcher = new NeoSubEndpointDispatcher(this);
  final NeoHookDispatcher hookDispatcher = new NeoHookDispatcher(this);
  final NeoSelectorEndpoint selectorEndpoint = new NeoSelectorEndpoint();
  final NeoCalloutEndpoint calloutEndpoint = new NeoCalloutEndpoint(this);
  final NeoDefaultsEndpoint defaultsEndpoint = new NeoDefaultsEndpoint(this);
  final NeoProcessReportEndpoint processReportEndpoint = new NeoProcessReportEndpoint(this);
  private final BatchService batchService = new BatchService(this);
  private final NeoSimSearchEndpoint simSearchEndpoint = new NeoSimSearchEndpoint();
  private final NeoGoWebhookBridge goWebhookBridge = new NeoGoWebhookBridge(this);

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    processRequest(request, response, "GET");
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    processRequest(request, response, "POST");
  }

  @Override
  public void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
    processRequest(request, response, "PUT");
  }

  @Override
  public void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
    processRequest(request, response, METHOD_DELETE);
  }

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (METHOD_PATCH.equalsIgnoreCase(request.getMethod())) {
      processRequest(request, response, METHOD_PATCH);
    } else {
      try {
        super.service(request, response);
      } catch (Exception e) {
        log.error("Error in NeoServlet.service", e);
        sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, NeoErrorSanitizer.sanitize(e));
      }
    }
  }

  private void processRequest(HttpServletRequest request, HttpServletResponse response,
      String method) throws IOException {
    // Readiness probe: no auth required, used by ALB health check
    if ("GET".equals(method) && "/health/ready".equals(request.getPathInfo())) {
      handleReadinessCheck(response);
      return;
    }

    // Download links are intentionally unauthenticated: the signed, expiring token carries the
    // record/client scope and NeoDocumentDownloadService validates it before serving any file.
    if ("GET".equals(method) && isDocumentDownloadPath(request.getPathInfo())) {
      handleDocumentDownload(request, response);
      return;
    }

    if (!authenticator.authenticateRequest(request, response)) {
      return;
    }

    NeoPathInfo pathInfo = requestRouter.parseRequestPath(request, response);
    if (pathInfo == null) {
      return;
    }

    try {
      OBContext.setAdminMode();
      if (builtInEndpointHandler.handle(pathInfo, method, request, response)) {
        return;
      }

      // Generic transactional batch endpoint: POST /sws/neo/batch
      //   Runs an ordered list of CRUD ops in one OBDal transaction with
      //   $ref:<opId> substitution between ops. Same primitive is consumed by
      //   the React UI (composite-document ingest) and external agents (MCP).
      //   Find-or-create logic stays with the caller — no per-window server code.
      if ("batch".equals(pathInfo.specName)) {
        if (!"POST".equals(method)) {
          sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
              "Batch endpoint only supports POST");
          return;
        }
        batchService.handle(request, response);
        return;
      }

      // Global similarity-search endpoint: GET /sws/neo/simsearch
      //   Same trigram matching as the "SimSearch" webhook, reached through NEO's own
      //   JWT auth instead of the Webhooks module's per-role grant table. See
      //   NeoSimSearchEndpoint for the authorization-model rationale.
      if ("simsearch".equals(pathInfo.specName)) {
        if (!"GET".equals(method)) {
          sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
              "Simsearch endpoint only supports GET");
          return;
        }
        writeResponse(response, simSearchEndpoint.handle(request));
        return;
      }

      // Etendo GO's own webhooks, reached through NEO's own JWT auth instead of the Webhooks
      // module's per-role SMFWHE_DEFINEDWEBHOOK_ROLE grant table (wiped by update.database — see
      // NeoGoWebhookBridge's class javadoc for the full rationale). A fixed, explicit allow-list,
      // never a generic "call any webhook by name" passthrough — bypassing the grant gate for a
      // third-party module's webhook is not this bridge's call to make.
      if ("listmenu".equals(pathInfo.specName)) {
        if (!"GET".equals(method)) {
          sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
              "Listmenu endpoint only supports GET");
          return;
        }
        writeResponse(response, goWebhookBridge.handle(request, new SFListMenu()));
        return;
      }
      if ("windowaccessmap".equals(pathInfo.specName)) {
        if (!"GET".equals(method)) {
          sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
              "Windowaccessmap endpoint only supports GET");
          return;
        }
        writeResponse(response, goWebhookBridge.handle(request, new SFWindowAccessMap()));
        return;
      }
      if ("rolesoverview".equals(pathInfo.specName)) {
        if (!"GET".equals(method)) {
          sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
              "Rolesoverview endpoint only supports GET");
          return;
        }
        writeResponse(response, goWebhookBridge.handle(request, new SFRolesOverview()));
        return;
      }
      requestRouter.handleSpecRequest(pathInfo, method, request, response);
    } catch (Exception e) {
      log.error("Error processing NEO request: {}", e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, NeoErrorSanitizer.sanitize(e));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private boolean isDocumentDownloadPath(String pathInfo) {
    return pathInfo != null && pathInfo.startsWith(DOCUMENT_DOWNLOAD_PREFIX);
  }

  private void handleDocumentDownload(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String token = StringUtils.substringAfter(request.getPathInfo(), DOCUMENT_DOWNLOAD_PREFIX);
    if (StringUtils.isBlank(token)) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing download token");
      return;
    }
    try {
      OBContext.setAdminMode(true);
      NeoDocumentDownloadService.handle(token, response);
    } catch (Exception e) {
      log.error("Error processing document download request", e);
      if (!response.isCommitted()) {
        response.reset();
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType("text/plain");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("Document download failed");
      }
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Find an active, included ETGO_SF_Entity by parent spec ID and entity name.
   */
  SFEntity findEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.ilike(SFEntity.PROPERTY_NAME, entityName, MatchMode.EXACT));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    List<SFEntity> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Check if the given HTTP method is enabled on the entity.
   */
  private boolean isMethodEnabled(SFEntity entity, String method) {
    switch (method) {
      case "GET":
        return Boolean.TRUE.equals(entity.isGet())
            || Boolean.TRUE.equals(entity.isGetByID());
      case "POST":
        return Boolean.TRUE.equals(entity.isPost());
      case "PUT":
        return Boolean.TRUE.equals(entity.isPut());
      case METHOD_PATCH:
        return Boolean.TRUE.equals(entity.isPatch());
      case METHOD_DELETE:
        return Boolean.TRUE.equals(entity.isDelete());
      default:
        return false;
    }
  }

  Map<String, String> extractQueryParams(HttpServletRequest request) {
    Map<String, String> params = new HashMap<>();
    Enumeration<String> names = request.getParameterNames();
    while (names.hasMoreElements()) {
      String name = names.nextElement();
      params.put(name, request.getParameter(name));
    }
    return params;
  }

  /**
   * Handle request with CDI hooks discovered via Java_Qualifier.
   * The handler acts as a pre+post hook (the handler decides via convention).
   *
   * <p>{@code request}/{@code response} are kept in the signature for the HTTP call sites
   * that already pass them, but the dispatch logic itself needs neither — it lives in
   * {@link NeoServletSupport#handleWithHooks(String, NeoContext, NeoCrudHandler)} so that
   * {@link BatchService}'s non-HTTP per-op create path can reach the exact same hook
   * dispatch without needing servlet request/response objects it does not have.
   */
  NeoResponse handleWithHooks(String javaQualifier, NeoContext context,
      HttpServletRequest request, HttpServletResponse response) {
    return NeoServletSupport.handleWithHooks(javaQualifier, context, crudHandler);
  }

  NeoHandler lookupHandler(String qualifier) {
    return NeoServletSupport.lookupHandler(qualifier);
  }

  void writeResponse(HttpServletResponse response, NeoResponse neoResponse)
      throws IOException {
    if (neoResponse == null) {
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
      return;
    }

    for (Map.Entry<String, String> header : neoResponse.getHeaders().entrySet()) {
      response.setHeader(header.getKey(), header.getValue());
    }

    response.setStatus(neoResponse.getHttpStatus());

    if (neoResponse.getBody() != null) {
      response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(neoResponse.getBody().toString());
    }
  }


  private void handleReadinessCheck(HttpServletResponse response) throws IOException {
    boolean ready = false;
    try {
      OBContext.setAdminMode();
      OBDal.getInstance().getSession()
          .createNativeQuery("SELECT 1").getSingleResult();
      ready = SWSConfig.getInstance().getPrivateKey() != null;
    } catch (Exception e) {
      log.warn("Readiness check failed: {}", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
    int status = ready ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE;
    writeReadinessJson(response, status, ready ? "ready" : "not-ready");
  }

  private static void writeReadinessJson(HttpServletResponse response, int status, String statusValue)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write("{\"status\":\"" + statusValue + "\"}");
  }


  void sendError(HttpServletResponse response, int status, String message)
      throws IOException {
    NeoResponse errorResponse = NeoResponse.error(status, message);
    writeResponse(response, errorResponse);
  }

  /**
   * Parsed path components.
   */
  public static class NeoPathInfo {
    public final String specName;
    public final String entityName;
    public final String recordId;
    public final boolean isSelector;
    public final String selectorField;
    public final boolean isAction;
    public final String actionName;
    public final boolean isEvaluateDisplay;
    public final boolean isCallout;
    public final boolean isDefaults;

    NeoPathInfo(String specName, String entityName, String recordId) {
      this(specName, entityName, recordId, false, null, false, null, false, false, false);
    }

    NeoPathInfo(String specName, String entityName, String recordId,
        boolean isSelector, String selectorField) {
      this(specName, entityName, recordId, isSelector, selectorField, false, null, false, false, false);
    }

    NeoPathInfo(String specName, String entityName, String recordId,
        boolean isSelector, String selectorField,
        boolean isAction, String actionName) {
      this(specName, entityName, recordId, isSelector, selectorField,
          isAction, actionName, false, false, false);
    }

    NeoPathInfo(String specName, String entityName, String recordId,
        boolean isSelector, String selectorField,
        boolean isAction, String actionName, boolean isEvaluateDisplay) {
      this(specName, entityName, recordId, isSelector, selectorField,
          isAction, actionName, isEvaluateDisplay, false, false);
    }

    NeoPathInfo(String specName, String entityName, String recordId,
        boolean isSelector, String selectorField,
        boolean isAction, String actionName, boolean isEvaluateDisplay,
        boolean isCallout) {
      this(specName, entityName, recordId, isSelector, selectorField,
          isAction, actionName, isEvaluateDisplay, isCallout, false);
    }

    NeoPathInfo(String specName, String entityName, String recordId,
        boolean isSelector, String selectorField,
        boolean isAction, String actionName, boolean isEvaluateDisplay,
        boolean isCallout, boolean isDefaults) {
      this.specName = specName;
      this.entityName = entityName;
      this.recordId = recordId;
      this.isSelector = isSelector;
      this.selectorField = selectorField;
      this.isAction = isAction;
      this.actionName = actionName;
      this.isEvaluateDisplay = isEvaluateDisplay;
      this.isCallout = isCallout;
      this.isDefaults = isDefaults;
    }
  }
}
