package com.etendoerp.go.schemaforge;

import java.io.BufferedReader;
import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.email.TransactionalEmailService;
import com.etendoerp.go.schemaforge.util.NeoImageHelper;

/**
 * Routes NEO endpoints that are served directly by the servlet rather than by
 * an SF spec.
 */
class NeoBuiltInEndpointHandler {

  private static final Logger log = LogManager.getLogger(NeoBuiltInEndpointHandler.class);

  private static final String METHOD_GET    = "GET";
  private static final String METHOD_POST   = "POST";
  private static final String METHOD_DELETE = "DELETE";
  private static final String METHOD_PATCH = "PATCH";
  private static final String ATTACHMENTS_SEGMENT_FILE = "file";
  private static final String ATTACHMENTS_SEGMENT_ZIP = "zip";
  private static final String ATTACHMENTS_SEGMENT_MAIN = "main";
  private static final String DESCRIPTION_FIELD = "description";
  private static final String IS_MAIN_FIELD = "isMain";
  private static final String MARK_AS_MAIN_PARAM = "markAsMain";

  private final NeoServlet servlet;
  private final NeoDiscoveryHandler discoveryHandler;
  private final Fiscal303BoxesHandler fiscal303Handler;
  private final Fiscal349BoxesHandler fiscal349Handler;
  private final TransactionalEmailService transactionalEmailService;

  NeoBuiltInEndpointHandler(NeoServlet servlet, NeoDiscoveryHandler discoveryHandler) {
    this(servlet, discoveryHandler, new TransactionalEmailService());
  }

  NeoBuiltInEndpointHandler(NeoServlet servlet, NeoDiscoveryHandler discoveryHandler,
      TransactionalEmailService transactionalEmailService) {
    this.servlet = servlet;
    this.discoveryHandler = discoveryHandler;
    this.fiscal303Handler = new Fiscal303BoxesHandler(servlet);
    this.fiscal349Handler = new Fiscal349BoxesHandler(servlet);
    this.transactionalEmailService = transactionalEmailService;
  }

  boolean handle(NeoServlet.NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (pathInfo.specName == null) {
      return handleDiscoveryEndpoint(method, response);
    }
    if ("image".equals(pathInfo.specName)) {
      NeoImageHelper.handleImageRequest(pathInfo.entityName, method, request, response);
      return true;
    }
    if ("session".equals(pathInfo.specName)) {
      return handleSessionEndpoint(method, response);
    }
    if ("filters".equals(pathInfo.specName)) {
      handleFiltersEndpoint(pathInfo, method, request, response);
      return true;
    }
    if ("certificate".equals(pathInfo.specName)) {
      handleCertificateEndpoint(method, request, response);
      return true;
    }
    if ("attachments".equals(pathInfo.specName)) {
      handleAttachmentsEndpoint(method, request, response);
      return true;
    }
    if ("fiscal303".equals(pathInfo.specName)) {
      fiscal303Handler.handle(pathInfo.entityName, method, request, response);
      return true;
    }
    if ("fiscal349".equals(pathInfo.specName)) {
      fiscal349Handler.handle(pathInfo.entityName, method, request, response);
      return true;
    }
    if ("email-contracts".equals(pathInfo.specName)) {
      handleEmailContractsEndpoint(pathInfo, method, request, response);
      return true;
    }
    // Only intercept amortization built-in endpoints (e.g. generate-plan).
    // Regular amortization spec CRUD (entityName = header/lines/accounting) falls through to
    // the standard window-spec router which resolves the DB spec.
    if ("amortization".equals(pathInfo.specName)
        && "generate-plan".equals(pathInfo.entityName)) {
      handleAmortizationEndpoint(method, request, response);
      return true;
    }
    if ("validate-exchange-rate".equals(pathInfo.specName)) {
      handleValidateExchangeRateEndpoint(method, request, response);
      return true;
    }
    return false;
  }

  private boolean handleDiscoveryEndpoint(String method, HttpServletResponse response)
      throws IOException {
    if (!METHOD_GET.equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Discovery endpoint only supports GET");
      return true;
    }
    discoveryHandler.handleDiscovery(response);
    return true;
  }

  private boolean handleSessionEndpoint(String method, HttpServletResponse response)
      throws IOException {
    if (!METHOD_GET.equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Session endpoint only supports GET");
      return true;
    }
    servlet.writeResponse(response, NeoSessionService.resolveSession());
    return true;
  }

  private void handleFiltersEndpoint(NeoServlet.NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (pathInfo.entityName == null) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Window name required: /sws/neo/filters/{window}");
      return;
    }
    if (METHOD_GET.equals(method)) {
      servlet.writeResponse(response, NeoFiltersService.getWindowPresets(pathInfo.entityName));
      return;
    }
    if (pathInfo.recordId == null) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Preset name required: /sws/neo/filters/{window}/{preset}");
      return;
    }
    handleFilterPresetMutation(pathInfo, method, request, response);
  }

  private void handleFilterPresetMutation(NeoServlet.NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if ("PUT".equals(method)) {
      NeoFiltersService.savePreset(pathInfo.entityName, pathInfo.recordId,
          NeoRequestBodyParser.readRequestBody(request));
      OBDal.getInstance().flush();
      servlet.writeResponse(response, null);
      return;
    }
    if (METHOD_DELETE.equals(method)) {
      NeoFiltersService.deletePreset(pathInfo.entityName, pathInfo.recordId);
      OBDal.getInstance().flush();
      servlet.writeResponse(response, null);
      return;
    }
    servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
        "Filters endpoint only supports GET, PUT and DELETE");
  }

  private void handleEmailContractsEndpoint(NeoServlet.NeoPathInfo pathInfo, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!METHOD_POST.equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Email contract endpoint only supports POST");
      return;
    }
    if (!isStrictEmailContractSendPath(request.getPathInfo())
        || StringUtils.isBlank(pathInfo.entityName) || !"send".equals(pathInfo.recordId)) {
      servlet.sendError(response, HttpServletResponse.SC_NOT_FOUND,
          "Unknown email contract endpoint");
      return;
    }
    try {
      JSONObject body = NeoRequestBodyParser.parseJsonObject(
          NeoRequestBodyParser.readRequestBody(request));
      servlet.writeResponse(response, transactionalEmailService.send(pathInfo.entityName, body));
    } catch (JSONException e) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body");
    } catch (IOException e) {
      log.error("Error reading email contract request body: {}", e.getMessage(), e);
      throw e;
    } catch (Exception e) {
      log.error("Unexpected error handling email contract [{}]: {}", pathInfo.entityName,
          e.getMessage(), e);
      OBDal.getInstance().rollbackAndClose();
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Email contract request failed");
    }
  }

  private boolean isStrictEmailContractSendPath(String pathInfo) {
    if (pathInfo == null) {
      return false;
    }
    String trimmed = pathInfo;
    while (trimmed.startsWith("/")) {
      trimmed = trimmed.substring(1);
    }
    String[] parts = trimmed.split("/");
    return parts.length == 3
        && "email-contracts".equals(parts[0])
        && StringUtils.isNotBlank(parts[1])
        && "send".equals(parts[2]);
  }

  private void handleCertificateEndpoint(String method, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    if (METHOD_GET.equals(method)) {
      servlet.writeResponse(response, NeoCertificateHelper.handleCertificateGet(request));
      return;
    }
    if (METHOD_DELETE.equals(method)) {
      servlet.writeResponse(response, NeoCertificateHelper.handleCertificateDelete(request));
      return;
    }
    if (METHOD_POST.equals(method)) {
      servlet.writeResponse(response, NeoCertificateHelper.handleCertificateUpload(request));
      return;
    }
    servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
        "Certificate endpoint supports GET, POST and DELETE");
  }

  /**
   * Dispatches {@code /sws/neo/attachments/*} requests to the cross-cutting
   * {@link NeoAttachmentsHelper}. Supported shapes:
   * <ul>
   *   <li>{@code GET    /attachments/{tableName}/{recordId}}        — list attachments
   *       (excludes the one marked as "main")</li>
   *   <li>{@code POST   /attachments/{tableName}/{recordId}}        — multipart upload;
   *       {@code ?markAsMain=true} marks the uploaded file as main atomically</li>
   *   <li>{@code GET    /attachments/{tableName}/{recordId}/zip}    — download all as zip
   *       (excludes the one marked as "main")</li>
   *   <li>{@code GET    /attachments/{tableName}/{recordId}/main}   — look up the attachment
   *       marked as this record's main document, or {@code {}} if none</li>
   *   <li>{@code GET    /attachments/file/{attachmentId}}           — download single file</li>
   *   <li>{@code DELETE /attachments/file/{attachmentId}}           — delete attachment</li>
   *   <li>{@code PATCH  /attachments/file/{attachmentId}}           — update description</li>
   *   <li>{@code PATCH  /attachments/file/{attachmentId}/main}      — {@code {isMain: bool}};
   *       marking deletes any previously-marked attachment for the same record</li>
   * </ul>
   */
  private void handleAttachmentsEndpoint(String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    String[] segments = parseAttachmentsSegments(request.getPathInfo());
    if (segments.length < 2) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Attachments endpoint requires /attachments/{tableName}/{recordId} "
              + "or /attachments/file/{attachmentId}");
      return;
    }

    if (ATTACHMENTS_SEGMENT_FILE.equals(segments[0])) {
      handleAttachmentsFileSubresource(segments, method, request, response);
      return;
    }

    handleAttachmentsRecordSubresource(segments, method, request, response);
  }

  /**
   * Handles {@code /attachments/{tableName}/{recordId}[/zip|/main]}.
   */
  private void handleAttachmentsRecordSubresource(String[] segments, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    String tableName = segments[0];
    String recordId = segments[1];
    boolean isZip = segments.length >= 3 && ATTACHMENTS_SEGMENT_ZIP.equals(segments[2]);
    boolean isMain = segments.length >= 3 && ATTACHMENTS_SEGMENT_MAIN.equals(segments[2]);

    if (isZip) {
      if (!"GET".equals(method)) {
        servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Attachments zip endpoint only supports GET");
        return;
      }
      NeoAttachmentsHelper.handleDownloadAll(tableName, recordId, response);
      return;
    }

    if (isMain) {
      if (!"GET".equals(method)) {
        servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Attachments main endpoint only supports GET");
        return;
      }
      servlet.writeResponse(response, NeoAttachmentsHelper.handleGetMain(tableName, recordId));
      return;
    }

    if ("GET".equals(method)) {
      servlet.writeResponse(response, NeoAttachmentsHelper.handleList(tableName, recordId));
      return;
    }
    if ("POST".equals(method)) {
      boolean markAsMain = "true".equalsIgnoreCase(request.getParameter(MARK_AS_MAIN_PARAM));
      servlet.writeResponse(response,
          NeoAttachmentsHelper.handleUpload(tableName, recordId, request, markAsMain));
      return;
    }
    servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
        "Attachments record endpoint supports GET (list) and POST (upload)");
  }

  /**
   * Handles {@code /attachments/file/{attachmentId}[/main]}
   * (download, delete, patch description, mark/unmark as main).
   */
  private void handleAttachmentsFileSubresource(String[] segments, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (segments.length < 2 || StringUtils.isBlank(segments[1])) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Attachments file endpoint requires /attachments/file/{attachmentId}");
      return;
    }
    String attachmentId = segments[1];
    boolean isMain = segments.length >= 3 && ATTACHMENTS_SEGMENT_MAIN.equals(segments[2]);

    if (isMain) {
      if (!METHOD_PATCH.equals(method)) {
        servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Attachments main endpoint only supports PATCH");
        return;
      }
      Boolean isMainValue = readIsMainFromBody(request, response);
      if (isMainValue == null && response.isCommitted()) {
        return;
      }
      servlet.writeResponse(response,
          NeoAttachmentsHelper.handleMarkMain(attachmentId, Boolean.TRUE.equals(isMainValue)));
      return;
    }

    if ("GET".equals(method)) {
      NeoAttachmentsHelper.handleDownload(attachmentId, response);
      return;
    }
    if (METHOD_DELETE.equals(method)) {
      NeoResponse delResp = NeoAttachmentsHelper.handleDelete(attachmentId);
      if (delResp.getHttpStatus() < 400) {
        OBDal.getInstance().flush();
      }
      servlet.writeResponse(response, delResp);
      return;
    }
    if (METHOD_PATCH.equals(method)) {
      String description = readDescriptionFromBody(request, response);
      if (description == null && response.isCommitted()) {
        return;
      }
      servlet.writeResponse(response,
          NeoAttachmentsHelper.handleUpdateDescription(attachmentId, description));
      return;
    }
    servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
        "Attachments file endpoint supports GET, DELETE and PATCH");
  }

  /**
   * Reads the {@code isMain} boolean out of a {@code PATCH .../main} JSON body.
   * Signals an error via {@code response} and returns {@code null} with the
   * response already committed if the body is missing or invalid.
   */
  private Boolean readIsMainFromBody(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String body = readBody(request);
    if (StringUtils.isBlank(body)) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Required field: isMain");
      return null;
    }
    try {
      JSONObject json = new JSONObject(body);
      if (!json.has(IS_MAIN_FIELD) || json.isNull(IS_MAIN_FIELD)) {
        servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
            "Required field: isMain");
        return null;
      }
      return json.getBoolean(IS_MAIN_FIELD);
    } catch (JSONException e) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid JSON body: " + e.getMessage());
      return null;
    }
  }

  /**
   * Splits the remaining path beyond {@code /attachments} into its segments.
   * For {@code /attachments/c_order/123/zip} this returns
   * {@code ["c_order", "123", "zip"]}.
   */
  private String[] parseAttachmentsSegments(String pathInfo) {
    if (pathInfo == null) {
      return new String[0];
    }
    String trimmed = pathInfo;
    while (trimmed.startsWith("/")) {
      trimmed = trimmed.substring(1);
    }
    String[] all = trimmed.split("/");
    if (all.length <= 1) {
      return new String[0];
    }
    // all[0] is "attachments"; drop it.
    String[] result = new String[all.length - 1];
    System.arraycopy(all, 1, result, 0, all.length - 1);
    return result;
  }

  /**
   * Reads the {@code description} field from a JSON PATCH body. Returns the
   * value (which may be {@code null} for an explicit {@code null}/empty body),
   * or signals an error via {@code response} and returns {@code null} with the
   * response already committed.
   */
  private String readDescriptionFromBody(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String body = readBody(request);
    if (StringUtils.isBlank(body)) {
      return null;
    }
    try {
      JSONObject json = new JSONObject(body);
      if (!json.has(DESCRIPTION_FIELD) || json.isNull(DESCRIPTION_FIELD)) {
        return null;
      }
      return json.getString(DESCRIPTION_FIELD);
    } catch (JSONException e) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid JSON body: " + e.getMessage());
      return null;
    }
  }

  private String readBody(HttpServletRequest request) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line);
      }
    }
    return sb.toString();
  }

  /**
   * Handles {@code POST /sws/neo/amortization/generate-plan} — generates an asset
   * amortization plan in a single call by firing the native A_Asset_Post process.
   *
   * <p>Body: {@code { "assetId": "<id>" }}
   * <p>Responses: 200 OK with plan summary, 400/404/409 on validation failures,
   * 500 on unexpected errors.
   *
   * <p>This method is only invoked when the path is exactly
   * {@code amortization/generate-plan}, so there is no collision with the
   * regular amortization window spec CRUD endpoints.
   */
  private void handleAmortizationEndpoint(String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!METHOD_POST.equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "Amortization generate-plan endpoint only supports POST");
      return;
    }
    String assetId;
    try {
      String body = readBody(request);
      JSONObject json = new JSONObject(body);
      assetId = json.optString("assetId", null);
    } catch (Exception e) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "Invalid JSON body: " + e.getMessage());
      return;
    }
    if (assetId == null || assetId.trim().isEmpty()) {
      servlet.sendError(response, HttpServletResponse.SC_BAD_REQUEST,
          "The 'assetId' parameter is mandatory.");
      return;
    }
    servlet.writeResponse(response, AmortizationPlanService.generatePlan(assetId));
  }

  private void handleValidateExchangeRateEndpoint(String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!METHOD_GET.equals(method)) {
      servlet.sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "validate-exchange-rate only supports GET");
      return;
    }
    servlet.writeResponse(response,
        NeoExchangeRateService.handleValidateExchangeRate(request));
  }
}
