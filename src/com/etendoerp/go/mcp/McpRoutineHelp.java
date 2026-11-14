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
package com.etendoerp.go.mcp;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The answer of {@code etendo_routine} mode {@code help} (ETP-6236): the full format of a routine,
 * with complete examples, read only when the agent asks for it.
 *
 * <p>The tool description is sent on every turn, so it stays short and points here; this text is
 * paid for once, in the turn that asks. The examples are real calls: {@code McpRoutineHelpTest}
 * checks that each one passes the same validation a call would.</p>
 */
final class McpRoutineHelp {

  /** Example 1: an inline flow — look up a customer and a product, invoice it and complete it. */
  static final String EXAMPLE_EXEC = "{\"mode\":\"exec\",\"steps\":["
      + "{\"id\":\"customer\",\"tool\":\"etendo_list\",\"args\":{\"spec\":\"contacts\","
      + "\"entity\":\"businessPartner\",\"filters\":{\"name\":{\"contains\":\"Acme\"}},"
      + "\"fields\":[\"id\",\"name\"]}},"
      + "{\"id\":\"water\",\"tool\":\"etendo_list\",\"args\":{\"spec\":\"product\","
      + "\"entity\":\"product\",\"filters\":{\"name\":{\"contains\":\"Agua\"}},"
      + "\"fields\":[\"id\",\"name\"]}},"
      + "{\"id\":\"invoice\",\"tool\":\"etendo_create\",\"args\":{\"spec\":\"sales-invoice\","
      + "\"entity\":\"header\",\"fields\":{\"businessPartner\":\"$ref:customer.data[0].id\"}}},"
      + "{\"id\":\"line\",\"tool\":\"etendo_create\",\"args\":{\"spec\":\"sales-invoice\","
      + "\"entity\":\"lines\",\"parentId\":\"$ref:invoice\",\"fields\":{"
      + "\"product\":\"$ref:water.data[0].id\",\"invoicedQuantity\":2,\"unitPrice\":100}}},"
      + "{\"id\":\"complete\",\"tool\":\"etendo_action\",\"args\":{\"spec\":\"sales-invoice\","
      + "\"entity\":\"header\",\"id\":\"$ref:invoice\",\"action\":\"documentAction\","
      + "\"parameters\":{\"docAction\":\"CO\"}},\"returnResult\":true}]}";

  /** Example 2: example 1 saved as a routine, turning four of its values into parameters. */
  static final String EXAMPLE_SAVE_FROM_EXECUTION = "{\"mode\":\"save\","
      + "\"fromExecution\":\"<executionId of example 1>\",\"name\":\"invoice-one-product\","
      + "\"description\":\"Invoices one product to a customer and completes the invoice.\","
      + "\"params\":{"
      + "\"customer\":{\"type\":\"string\",\"required\":true,\"description\":\"Customer name, or "
      + "part of it\",\"from\":[\"customer.args.filters.name.contains\"]},"
      + "\"product\":{\"type\":\"string\",\"required\":true,\"description\":\"Product name, or part "
      + "of it\",\"from\":[\"water.args.filters.name.contains\"]},"
      + "\"quantity\":{\"type\":\"number\",\"required\":true,\"description\":\"Units\","
      + "\"from\":[\"line.args.fields.invoicedQuantity\"]},"
      + "\"price\":{\"type\":\"number\",\"required\":true,\"description\":\"Unit price\","
      + "\"from\":[\"line.args.fields.unitPrice\"]}}}";

  /** Example 3: a routine written directly, without an execution first. */
  static final String EXAMPLE_SAVE = "{\"mode\":\"save\",\"name\":\"customer-with-address\","
      + "\"description\":\"Creates a customer with its address in Spain.\","
      + "\"params\":{"
      + "\"name\":{\"type\":\"string\",\"required\":true,\"description\":\"Customer name\"},"
      + "\"street\":{\"type\":\"string\",\"required\":true,\"description\":\"Street and number\"},"
      + "\"city\":{\"type\":\"string\",\"required\":true,\"description\":\"City\"},"
      + "\"postalCode\":{\"type\":\"string\",\"required\":false,\"description\":\"Postal code\"}},"
      + "\"steps\":["
      + "{\"id\":\"bp\",\"tool\":\"etendo_create\",\"args\":{\"spec\":\"contacts\","
      + "\"entity\":\"businessPartner\",\"fields\":{\"name\":\"$param.name\"}}},"
      + "{\"id\":\"address\",\"tool\":\"etendo_create\",\"args\":{\"spec\":\"contacts\","
      + "\"entity\":\"locationAddress\",\"parentId\":\"$ref:bp\",\"fields\":{\"country\":\"106\","
      + "\"addressLine1\":\"$param.street\",\"cityName\":\"$param.city\","
      + "\"postalCode\":\"$param.postalCode\"}},\"returnResult\":true}]}";

  /** Example 4: running a saved routine. */
  static final String EXAMPLE_RUN = "{\"mode\":\"run\",\"name\":\"invoice-one-product\","
      + "\"params\":{\"customer\":\"Globex\",\"product\":\"Fernet\",\"quantity\":3,\"price\":12.5}}";

  private McpRoutineHelp() {
  }

  /** @return the help body: how a routine is written, and the four examples as JSON */
  static JSONObject body() {
    try {
      JSONObject body = new JSONObject();
      body.put("steps", "A routine is a list of at most " + McpRoutineSteps.MAX_STEPS + " steps, "
          + "run in order. Each step is {id, tool, args, returnResult?}. 'id' names the step "
          + "(letters, digits, '_' or '-', starting with a letter). 'tool' is one of "
          + McpRoutineSteps.STEP_TOOLS + ". 'args' are exactly the arguments that tool takes "
          + "on its own: write a step the way you would write that call. returnResult:true puts "
          + "that step's full result in the answer; with none marked, only the last step's is.");
      body.put("references", "Inside 'args', a string value that IS a reference is replaced "
          + "before the step runs, keeping the JSON type of what it points to. '$ref:<id>' is "
          + "the record id (data.id) an earlier step created or read. '$ref:<id>.<path>' is any "
          + "value of that step's result: keys separated by '.', list positions as [n] or .n — "
          + "'$ref:customer.data[0].id' is the id of the first row an etendo_list returned. "
          + "'$param.<name>' is a parameter of a saved routine; an optional one that is not sent "
          + "removes the key that held it. Only earlier steps can be referenced, and the whole "
          + "string must be the reference ('Invoice $ref:x' is not replaced).");
      body.put("parameters", "A saved routine declares its parameters in 'params': "
          + "{<name>: {type: string|number|boolean|id, required: true|false, description}}. "
          + "Every declared parameter must be used as '$param.<name>' by some step, and every "
          + "'$param.<name>' used must be declared. mode run checks every value against its type "
          + "before the first step and runs nothing when one is missing or wrong.");
      body.put("howToCreate", "Easiest and safest: solve the task once with mode exec (example "
          + "1); when it finishes ok, save it with mode save and fromExecution (example 2) — "
          + "each parameter lists in 'from' the values of that execution it replaces, as "
          + "'<stepId>.<path inside the step>' (e.g. 'line.args.fields.unitPrice'). The steps "
          + "you save have already worked. You can also write the steps yourself (example 3). "
          + "Then mode run with the values (example 4). mode describe shows a saved routine's "
          + "steps and parameters; mode update stores a complete new version (steps and params).");
      body.put("tools", "Steps may read (etendo_list, etendo_get, etendo_selectors, "
          + "etendo_defaults) and write (etendo_create, etendo_update, etendo_action). Deleting "
          + "and sending email are separate calls you decide on one by one; a routine cannot run "
          + "another routine.");
      body.put("failures", "Not atomic: each step commits when it succeeds. The first failing "
          + "step is rolled back and the routine stops; the error names it (failedAt), carries "
          + "that tool's own error (stepError), lists the steps that are done and stay done with "
          + "their recordId (completed) and the ones that did not run (pending). Fix the cause "
          + "and continue with the failed and pending steps only, using the completed recordIds "
          + "— resending a completed step creates the record twice.");
      body.put("names", "Routine names: lowercase letters, digits, '-' or '_' (at most 60), "
          + "unique among your routines. Routines are yours only; list shows them.");
      JSONArray examples = new JSONArray();
      examples.put(example("1. Solve a task inline: an invoice for a customer, completed",
          EXAMPLE_EXEC));
      examples.put(example("2. Save example 1 as a routine, with four values as parameters",
          EXAMPLE_SAVE_FROM_EXECUTION));
      examples.put(example("3. Write a routine directly, with an optional parameter",
          EXAMPLE_SAVE));
      examples.put(example("4. Run a saved routine", EXAMPLE_RUN));
      body.put("examples", examples);
      return body;
    } catch (JSONException e) {
      throw new McpToolException("Error building the routine help", e);
    }
  }

  private static JSONObject example(String title, String call) throws JSONException {
    JSONObject example = new JSONObject();
    example.put("title", title);
    example.put("call", new JSONObject(call));
    return example;
  }
}
