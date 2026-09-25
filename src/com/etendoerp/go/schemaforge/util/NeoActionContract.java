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
package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * One named action a {@code NeoHandler} answers in its pre-hook, i.e. a
 * {@code /{spec}/{entity}/{id}/action/{name}} route that is not an AD button (ETP-5447).
 *
 * <p><b>Why the handler declares this.</b> Named handler actions — {@code createDraftInvoice},
 * {@code listInvoices}, the bank-statement actions — exist only as string comparisons inside
 * {@code handle()}. Nothing in the configuration or the Application Dictionary knows about them,
 * so {@code neo_schema({view:"actions"})} listed the entity's AD buttons and none of these, and
 * {@code neo_action} always fired them as {@code POST}, which made every {@code GET}-only action
 * answer {@code 404 Action not found}. As with {@code NeoHandler#servesActions} and
 * {@code NeoHandler#reportParameters}, the handler is the only authority, so it declares the
 * truth and the MCP catalog and method selection read it.</p>
 *
 * <p>Parameters reuse {@link NeoReportParam}: the vocabulary (JSON Schema types plus
 * {@code date}) and the "declare only what you read" rule are the same.</p>
 *
 * <p>Immutable; build with {@link #builder(String)}.</p>
 */
public final class NeoActionContract {

  /** The HTTP method for an action that reads its input from the query string. */
  public static final String METHOD_GET = "GET";
  /** The HTTP method for an action that reads its input from the request body; the default. */
  public static final String METHOD_POST = "POST";

  private final String name;
  private final String description;
  private final String method;
  private final boolean readOnly;
  private final List<NeoReportParam> parameters;

  private NeoActionContract(Builder builder) {
    this.name = builder.name;
    this.description = builder.description;
    this.method = builder.method;
    this.readOnly = builder.readOnly;
    this.parameters = Collections.unmodifiableList(new ArrayList<>(builder.parameters));
  }

  /**
   * Start declaring an action.
   *
   * @param name the action name exactly as the handler compares it (the {@code {name}} path
   *             segment, and the {@code action} argument of {@code neo_action})
   * @return a builder with method {@code POST}, {@code readOnly=false} and no parameters
   */
  public static Builder builder(String name) {
    return new Builder(name);
  }

  /**
   * @return the action name, as the handler compares it
   */
  public String getName() {
    return name;
  }

  /**
   * @return what the action does and returns, shown to agents in the catalog; may be {@code null}
   */
  public String getDescription() {
    return description;
  }

  /**
   * @return {@link #METHOD_GET} or {@link #METHOD_POST}
   */
  public String getMethod() {
    return method;
  }

  /**
   * @return {@code true} when the action changes nothing (a lookup or a preview)
   */
  public boolean isReadOnly() {
    return readOnly;
  }

  /**
   * @return the declared inputs, in declaration order; unmodifiable, never {@code null}
   */
  public List<NeoReportParam> getParameters() {
    return parameters;
  }

  /**
   * @return the names of the parameters the action cannot run without, in declaration order
   */
  public List<String> getRequiredParameterNames() {
    List<String> required = new ArrayList<>();
    for (NeoReportParam param : parameters) {
      if (param.isRequired()) {
        required.add(param.getName());
      }
    }
    return required;
  }

  /**
   * Fluent builder for {@link NeoActionContract}.
   */
  public static final class Builder {

    private final String name;
    private String description;
    private String method = METHOD_POST;
    private boolean readOnly;
    private final List<NeoReportParam> parameters = new ArrayList<>();

    private Builder(String name) {
      this.name = name;
    }

    /**
     * @param description what the action does and what its result carries
     * @return this builder
     */
    public Builder description(String description) {
      this.description = description;
      return this;
    }

    /**
     * @param method {@code "GET"} or {@code "POST"} — the method the handler answers the action
     *               on; validated in {@link #build()}
     * @return this builder
     */
    public Builder method(String method) {
      this.method = method;
      return this;
    }

    /**
     * @param readOnly {@code true} when the action changes nothing
     * @return this builder
     */
    public Builder readOnly(boolean readOnly) {
      this.readOnly = readOnly;
      return this;
    }

    /**
     * Declare one input. Repeatable; the order of the calls is the order agents see.
     *
     * @param param a parameter the handler demonstrably reads for this action
     * @return this builder
     */
    public Builder param(NeoReportParam param) {
      if (param != null) {
        parameters.add(param);
      }
      return this;
    }

    /**
     * @return the immutable contract
     * @throws IllegalArgumentException when the name is blank or the method is not GET/POST
     */
    public NeoActionContract build() {
      if (StringUtils.isBlank(name)) {
        throw new IllegalArgumentException("A declared action needs a non-blank name");
      }
      if (!METHOD_GET.equals(method) && !METHOD_POST.equals(method)) {
        throw new IllegalArgumentException(
            "Declared action '" + name + "' has method '" + method + "'; expected GET or POST");
      }
      return new NeoActionContract(this);
    }
  }
}
