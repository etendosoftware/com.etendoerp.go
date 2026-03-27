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

package com.etendoerp.go.schemaforge.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Image;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Static helpers for image GET and POST endpoints.
 */
public final class NeoImageHelper {

  private static final Logger log = LogManager.getLogger(NeoImageHelper.class);
  private static final int MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;

  private NeoImageHelper() {
  }

  public static void handleImageRequest(String imageId, String method,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    try {
      if ("GET".equals(method)) {
        handleGetImage(imageId, response);
      } else if ("POST".equals(method)) {
        handlePostImage(request, response);
      } else {
        sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Image endpoint only supports GET and POST");
      }
    } catch (Exception e) {
      log.error("Error handling image request", e);
      OBDal.getInstance().rollbackAndClose();
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Image request failed");
    }
  }

  private static void handleGetImage(String imageId, HttpServletResponse response) throws Exception {
    if (StringUtils.isBlank(imageId)) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Image ID required");
      return;
    }
    Image image = OBDal.getInstance().get(Image.class, imageId);
    if (image == null) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, "Image not found: " + imageId);
      return;
    }
    byte[] data = image.getBindaryData();
    if (data == null || data.length == 0) {
      sendError(response, HttpServletResponse.SC_NOT_FOUND, "Image has no data");
      return;
    }
    String mimeType = image.getMimetype();
    if (StringUtils.isBlank(mimeType)) {
      mimeType = "image/png";
    }
    response.setContentType(mimeType);
    response.setContentLength(data.length);
    try (OutputStream out = response.getOutputStream()) {
      out.write(data);
    }
  }

  private static void handlePostImage(HttpServletRequest request, HttpServletResponse response)
      throws Exception {
    byte[] rawBytes = request.getInputStream().readNBytes(MAX_IMAGE_SIZE_BYTES + 1);
    if (rawBytes.length > MAX_IMAGE_SIZE_BYTES) {
      sendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Image exceeds 10 MB limit");
      return;
    }
    String bodyStr = new String(rawBytes, StandardCharsets.UTF_8);
    JSONObject body = new JSONObject(bodyStr);
    String name = body.optString("name", "image");
    String mimeType = body.optString("mimeType", "image/png");
    String dataBase64 = body.optString("data", "");
    if (StringUtils.isBlank(dataBase64)) {
      sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Image data required");
      return;
    }
    if (dataBase64.contains(",")) {
      dataBase64 = dataBase64.substring(dataBase64.indexOf(',') + 1);
    }
    byte[] imageBytes = Base64.getDecoder().decode(dataBase64);
    Image image = OBProvider.getInstance().get(Image.class);
    image.setClient(OBContext.getOBContext().getCurrentClient());
    image.setOrganization(OBContext.getOBContext().getCurrentOrganization());
    image.setName(name);
    image.setMimetype(mimeType);
    image.setBindaryData(imageBytes);
    OBDal.getInstance().save(image);
    OBDal.getInstance().flush();
    JSONObject result = new JSONObject();
    result.put("imageId", image.getId());
    result.put("name", image.getName());
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setStatus(HttpServletResponse.SC_OK);
    try (OutputStream out = response.getOutputStream()) {
      out.write(result.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void sendError(HttpServletResponse response, int status, String message)
      throws IOException {
    NeoResponse errorResponse = NeoResponse.error(status, message);
    response.setStatus(errorResponse.getHttpStatus());
    if (errorResponse.getBody() != null) {
      response.setContentType("application/json");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(errorResponse.getBody().toString());
    }
  }
}
