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

package com.etendoerp.go.schemaforge.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

class NeoTelemetryServiceTest {

  @Test
  void emitKeepsOnlyBackendEvents() {
    RecordingSink sink = new RecordingSink();
    NeoTelemetryService service = service(sink, 0L);

    service.emit("record_created", mapOf("status", "success"));
    service.emit(NeoTelemetryEvents.BACKEND_ASSET_CREATED, mapOf("status", "success"));

    assertEquals(1, sink.events.size());
    assertEquals(NeoTelemetryEvents.BACKEND_ASSET_CREATED, sink.last().getName());
  }

  @Test
  void emitSanitizesSensitivePropertiesAndKeepsSafeMetrics() {
    RecordingSink sink = new RecordingSink();
    NeoTelemetryService service = service(sink, 0L);

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("accuracy", 98.5d);
    properties.put("channel", "ocr");
    properties.put("count", 4);
    properties.put("correctCount", 3);
    properties.put("critical", true);
    properties.put("durationMs", 1250L);
    properties.put("entity", "invoice");
    properties.put("entityType", "supplier_invoice");
    properties.put("flow", "invoice_ingestion");
    properties.put("kpiId", "kpi_precision_purchase_ocr_fields");
    properties.put("module", "purchases");
    properties.put("operation", "create");
    properties.put("source", "ocr");
    properties.put("specName", "purchase-invoice");
    properties.put("status", "success");
    properties.put("total", 4);
    properties.put("recordId", "A123");
    properties.put("documentNo", "INV-1");
    properties.put("token", "secret");
    properties.put("rawUrl", "/private/A123");
    properties.put("randomKey", "ignored");

    service.emit(NeoTelemetryEvents.BACKEND_OCR_FIELD_ACCURACY, properties);

    Map<String, Object> emitted = sink.last().getProperties();
    assertEquals(98.5d, emitted.get("accuracy"));
    assertEquals("ocr", emitted.get("channel"));
    assertEquals(4, emitted.get("count"));
    assertEquals(3, emitted.get("correctCount"));
    assertEquals(true, emitted.get("critical"));
    assertEquals(1250L, emitted.get("durationMs"));
    assertEquals("invoice", emitted.get("entity"));
    assertEquals("supplier_invoice", emitted.get("entityType"));
    assertEquals("invoice_ingestion", emitted.get("flow"));
    assertEquals("kpi_precision_purchase_ocr_fields", emitted.get("kpiId"));
    assertEquals("purchases", emitted.get("module"));
    assertEquals("purchase-invoice", emitted.get("specName"));
    assertEquals(4, emitted.get("total"));
    assertFalse(emitted.containsKey("recordId"));
    assertFalse(emitted.containsKey("documentNo"));
    assertFalse(emitted.containsKey("token"));
    assertFalse(emitted.containsKey("rawUrl"));
    assertFalse(emitted.containsKey("randomKey"));
  }

  @Test
  void emitDropsInvalidNumericMetricValues() {
    RecordingSink sink = new RecordingSink();
    NeoTelemetryService service = service(sink, 0L);

    service.emit(NeoTelemetryEvents.BACKEND_BANK_MATCH_ATTEMPTED, mapOf(
        "accuracy", 120,
        "correctCount", -1,
        "critical", "true",
        "durationMs", "2500",
        "httpStatus", 99,
        "score", "9",
        "status", "success",
        "total", Double.POSITIVE_INFINITY));

    Map<String, Object> emitted = sink.last().getProperties();
    assertEquals("success", emitted.get("status"));
    assertFalse(emitted.containsKey("accuracy"));
    assertFalse(emitted.containsKey("correctCount"));
    assertFalse(emitted.containsKey("critical"));
    assertFalse(emitted.containsKey("durationMs"));
    assertFalse(emitted.containsKey("httpStatus"));
    assertFalse(emitted.containsKey("score"));
    assertFalse(emitted.containsKey("total"));
  }

  @Test
  void emitKeepsEtp4307BackendKpiEvents() {
    RecordingSink sink = new RecordingSink();
    NeoTelemetryService service = service(sink, 0L);

    service.emit(NeoTelemetryEvents.BACKEND_INVOICE_INGESTION_COMPLETED,
        mapOf("kpiId", "kpi_adopt_purchase_ocr_channel", "module", "purchases",
            "channel", "ocr", "entityType", "supplier_invoice", "status", "success"));
    service.emit(NeoTelemetryEvents.BACKEND_STOCK_MOVEMENT_VALIDATED,
        mapOf("kpiId", "kpi_integrity_inventory_movement_traceability",
            "module", "inventory", "entityType", "stock_movement", "critical", true,
            "status", "success"));
    service.emit(NeoTelemetryEvents.BACKEND_ACCEPTANCE_INTEGRITY_CHECK_COMPLETED,
        mapOf("kpiId", "kpi_integrity_product_stock_card_inventory",
            "module", "products", "correctCount", 10, "total", 10,
            "critical", true, "status", "success"));

    assertEquals(3, sink.events.size());
    assertEquals(NeoTelemetryEvents.BACKEND_INVOICE_INGESTION_COMPLETED,
        sink.events.get(0).getName());
    assertEquals(NeoTelemetryEvents.BACKEND_STOCK_MOVEMENT_VALIDATED,
        sink.events.get(1).getName());
    assertEquals(NeoTelemetryEvents.BACKEND_ACCEPTANCE_INTEGRITY_CHECK_COMPLETED,
        sink.events.get(2).getName());
    assertEquals("kpi_integrity_product_stock_card_inventory",
        sink.events.get(2).getProperties().get("kpiId"));
    assertEquals(10, sink.events.get(2).getProperties().get("correctCount"));
    assertEquals(10, sink.events.get(2).getProperties().get("total"));
  }

  @Test
  void measureBackendOperationEmitsWriteTimingWithoutRecordId() throws Exception {
    RecordingSink sink = new RecordingSink();
    NeoTelemetryService service = service(sink, 0L, 125_000_000L);
    NeoContext context = writeContext("POST", "A123");
    NeoResponse response = NeoResponse.created(new JSONObject("{\"ok\":true}"));

    NeoResponse result = service.measureBackendOperation(context, () -> response);

    assertSame(response, result);
    NeoTelemetryEvent event = sink.last();
    Map<String, Object> emitted = event.getProperties();
    assertEquals(NeoTelemetryEvents.BACKEND_WRITE_OPERATION_COMPLETED, event.getName());
    assertEquals("neo", emitted.get("source"));
    assertEquals("sales-order", emitted.get("specName"));
    assertEquals("header", emitted.get("entity"));
    assertEquals("create", emitted.get("operation"));
    assertEquals("success", emitted.get("status"));
    assertEquals(125L, emitted.get("durationMs"));
    assertEquals(201, emitted.get("httpStatus"));
    assertFalse(emitted.containsKey("recordId"));
  }

  @Test
  void measureBackendOperationEmitsFailedTimingAndRethrows() {
    RecordingSink sink = new RecordingSink();
    NeoTelemetryService service = service(sink, 10L, 30_000_010L);
    RuntimeException failure = new RuntimeException("boom");

    RuntimeException thrown = assertThrows(RuntimeException.class,
        () -> service.measureBackendOperation(writeContext("PATCH", "A123"), () -> {
          throw failure;
        }));

    assertSame(failure, thrown);
    Map<String, Object> emitted = sink.last().getProperties();
    assertEquals("update", emitted.get("operation"));
    assertEquals("failed", emitted.get("status"));
    assertEquals(30L, emitted.get("durationMs"));
    assertFalse(emitted.containsKey("recordId"));
  }

  @Test
  void measureBackendOperationSkipsReadMethods() {
    RecordingSink sink = new RecordingSink();
    NeoTelemetryService service = service(sink, 0L, 10_000_000L);
    NeoResponse response = NeoResponse.ok(new JSONObject());

    NeoResponse result = service.measureBackendOperation(readContext(), () -> response);

    assertSame(response, result);
    assertTrue(sink.events.isEmpty());
  }

  @Test
  void emitSwallowsSinkFailures() {
    NeoTelemetryService service = new NeoTelemetryService(event -> {
      throw new IllegalStateException("sink unavailable");
    }, sequenceClock(0L));

    assertDoesNotThrow(() ->
        service.emit(NeoTelemetryEvents.BACKEND_ACCOUNTING_ENTRY_GENERATED,
            mapOf("status", "success")));
  }

  @Test
  void detectsWriteMethods() {
    assertTrue(NeoTelemetryService.isWriteMethod("POST"));
    assertTrue(NeoTelemetryService.isWriteMethod("put"));
    assertTrue(NeoTelemetryService.isWriteMethod("PATCH"));
    assertTrue(NeoTelemetryService.isWriteMethod("DELETE"));
    assertFalse(NeoTelemetryService.isWriteMethod("GET"));
    assertFalse(NeoTelemetryService.isWriteMethod(null));
  }

  private static NeoTelemetryService service(RecordingSink sink, long... clockValues) {
    return new NeoTelemetryService(sink, sequenceClock(clockValues));
  }

  private static LongSupplier sequenceClock(long... values) {
    AtomicInteger index = new AtomicInteger(0);
    return () -> {
      int current = index.getAndIncrement();
      return values[Math.min(current, values.length - 1)];
    };
  }

  private static NeoContext writeContext(String method, String recordId) {
    return NeoContext.builder()
        .specName("sales-order")
        .entityName("header")
        .httpMethod(method)
        .recordId(recordId)
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private static NeoContext readContext() {
    return NeoContext.builder()
        .specName("sales-order")
        .entityName("header")
        .httpMethod("GET")
        .recordId("A123")
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  private static Map<String, Object> mapOf(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }

  private static final class RecordingSink implements NeoTelemetrySink {
    private final List<NeoTelemetryEvent> events = new ArrayList<>();

    @Override
    public void emit(NeoTelemetryEvent event) {
      events.add(event);
    }

    private NeoTelemetryEvent last() {
      return events.get(events.size() - 1);
    }
  }
}
