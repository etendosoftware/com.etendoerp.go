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

/**
 * Everything {@link NeoExtensionDispatcher} needs for one dispatch.
 *
 * <p>Built once per request by the caller and then derived — {@link #post(NeoHandler)},
 * {@link #withPreviousResult(NeoResponse)} — so the pre and post phases of the same operation
 * cannot drift apart in spec, entity, surface or channel, which is precisely how the six
 * hand-rolled MCP hook sites drifted from each other in the first place.</p>
 *
 * @param qualifier      the {@code Java_Qualifier} to resolve
 * @param specName       the spec, for the trace
 * @param entityName     the entity, for the trace
 * @param surface        the operation being dispatched
 * @param channel        the caller family; selects the resolver
 * @param context        the context handed to the customization
 * @param phase          which customization method to invoke
 * @param customization  a customization already resolved by the matching pre phase; {@code null}
 *                       on a pre phase, where the dispatcher resolves it
 * @param previousResult the result a {@link Phase#POST} dispatch offers for replacement
 */
public record NeoExtensionRequest(String qualifier, String specName, String entityName,
    NeoExtensionSurface surface, NeoExtensionChannel channel, NeoContext context,
    Phase phase, NeoHandler customization, NeoResponse previousResult) {

  /** Which method of the customization a dispatch invokes. */
  public enum Phase {

    /** {@code NeoHandler#handle} — runs before the default service. */
    PRE("handle"),

    /** {@code NeoHandler#afterHandle} — runs after it. */
    POST("afterHandle");

    private final String methodName;

    Phase(String methodName) {
      this.methodName = methodName;
    }

    /** @return the method name, as it appears in the trace */
    public String methodName() {
      return methodName;
    }
  }

  /**
   * Derive the post phase of this same operation.
   *
   * @param resolved the customization the pre phase resolved; {@code null} means none was, and the
   *                 dispatch will report {@link NeoExtensionOutcome#NO_CUSTOMIZATION} without
   *                 resolving anything — a post phase never resolves, because that would hand the
   *                 post hook a different instance than the pre hook ran on
   * @return a copy in {@link Phase#POST}
   */
  public NeoExtensionRequest post(NeoHandler resolved) {
    return new NeoExtensionRequest(qualifier, specName, entityName, surface, channel, context,
        Phase.POST, resolved, previousResult);
  }

  /**
   * Derive a copy carrying the result the post hook is offered for replacement.
   *
   * @param result the result produced so far; may be {@code null}, which the REST path does pass
   * @return a copy carrying {@code result}
   */
  public NeoExtensionRequest withPreviousResult(NeoResponse result) {
    return new NeoExtensionRequest(qualifier, specName, entityName, surface, channel, context,
        phase, customization, result);
  }

  /** @return a builder for a {@link Phase#PRE} request */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for the pre phase; the post phase is derived from it, never built. */
  public static final class Builder {

    private String qualifier;
    private String specName;
    private String entityName;
    private NeoExtensionSurface surface;
    private NeoExtensionChannel channel;
    private NeoContext context;

    private Builder() {
    }

    /**
     * Sets the {@code Java_Qualifier} the entity row declares; may be blank when the
     * customization is bound by {@link NeoExtension} alone.
     *
     * @param value the qualifier, or {@code null}
     * @return this builder
     */
    public Builder qualifier(String value) {
      this.qualifier = value;
      return this;
    }

    /**
     * Sets the spec name, the kebab-case name every dispatch path passes and never the UUID.
     *
     * @param value the spec name
     * @return this builder
     */
    public Builder specName(String value) {
      this.specName = value;
      return this;
    }

    /**
     * Sets the entity within the spec.
     *
     * @param value the entity name
     * @return this builder
     */
    public Builder entityName(String value) {
      this.entityName = value;
      return this;
    }

    /**
     * Sets the surface being served. When left unset it is derived from the context.
     *
     * @param value the surface, or {@code null} to derive it
     * @return this builder
     */
    public Builder surface(NeoExtensionSurface value) {
      this.surface = value;
      return this;
    }

    /**
     * Sets the channel the request arrived on, which is what the trace reports it under.
     *
     * @param value the channel
     * @return this builder
     */
    public Builder channel(NeoExtensionChannel value) {
      this.channel = value;
      return this;
    }

    /**
     * Sets the context handed to the customization.
     *
     * @param value the context
     * @return this builder
     */
    public Builder context(NeoContext value) {
      this.context = value;
      return this;
    }

    /** @return the immutable request */
    public NeoExtensionRequest build() {
      return new NeoExtensionRequest(qualifier, specName, entityName,
          surface != null ? surface : NeoExtensionSurface.of(context), channel, context,
          Phase.PRE, null, null);
    }
  }
}
