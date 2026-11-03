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

package com.etendoerp.go.llm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * One typed question of a Decisions API request ({@code POST /v1/decisions}).
 *
 * <p>Every question has a {@code name}, unique within the request, used to find its answer, and
 * {@code instructions} that say what to evaluate about the shared {@link DecisionInput}.</p>
 */
public final class DecisionQuestion {

  /** Question types the OpenAI decision model answers. */
  public enum Type {
    /** Yes/no condition; answered with the probability that it is true. */
    PREDICATE("predicate"),
    /** One value out of an unordered list. */
    CHOICE("choice"),
    /** A level out of an ordered list, lowest first. */
    SCORE("score");

    private final String wire;

    Type(String wire) {
      this.wire = wire;
    }

    String wire() {
      return wire;
    }
  }

  /**
   * A choice value or a score level.
   *
   * @param value the choice value, or the score level label
   * @param description optional; tells the model when this option applies
   */
  public record Option(String value, String description) {

    /**
     * An option without description.
     *
     * @param value the choice value or level label
     * @return the option
     */
    public static Option of(String value) {
      return new Option(value, null);
    }
  }

  static final int MAX_CHOICES = 255;
  static final int MAX_LEVELS = 10;

  private static final String QUESTION = "question ";

  private final Type type;
  private final String name;
  private final String instructions;
  private final List<Option> options;

  private DecisionQuestion(Type type, String name, String instructions, List<Option> options) {
    if (StringUtils.isBlank(name)) {
      throw new IllegalArgumentException("question name is required");
    }
    if (StringUtils.isBlank(instructions)) {
      throw new IllegalArgumentException("instructions are required for question " + name);
    }
    this.type = type;
    this.name = name;
    this.instructions = instructions;
    this.options = List.copyOf(options);
  }

  /**
   * A yes/no question.
   *
   * @param name unique name of the question in the request
   * @param instructions the condition to check, e.g. "Is the customer asking for a refund?"
   * @return the question
   */
  public static DecisionQuestion predicate(String name, String instructions) {
    return new DecisionQuestion(Type.PREDICATE, name, instructions, List.of());
  }

  /**
   * Pick one value out of a fixed, unordered list.
   *
   * @param name unique name of the question in the request
   * @param instructions what to choose
   * @param choices 2 to {@value #MAX_CHOICES} distinct values, optionally described
   * @return the question
   */
  public static DecisionQuestion choice(String name, String instructions, List<Option> choices) {
    return new DecisionQuestion(Type.CHOICE, name, instructions,
        checkOptions(name, choices, MAX_CHOICES, "choices"));
  }

  /**
   * Pick one value out of a fixed, unordered list of undescribed values.
   *
   * @param name unique name of the question in the request
   * @param instructions what to choose
   * @param values 2 to {@value #MAX_CHOICES} distinct values
   * @return the question
   */
  public static DecisionQuestion choice(String name, String instructions, String... values) {
    return choice(name, instructions, Arrays.stream(values).map(Option::of).toList());
  }

  /**
   * Rate against ordered levels.
   *
   * @param name unique name of the question in the request
   * @param instructions what to rate
   * @param levels 2 to {@value #MAX_LEVELS} distinct labels, <b>lowest first</b>, optionally
   *     described so adjacent levels have distinct criteria
   * @return the question
   */
  public static DecisionQuestion score(String name, String instructions, List<Option> levels) {
    return new DecisionQuestion(Type.SCORE, name, instructions,
        checkOptions(name, levels, MAX_LEVELS, "levels"));
  }

  private static List<Option> checkOptions(String name, List<Option> options, int max,
      String what) {
    if (options == null || options.size() < 2 || options.size() > max) {
      throw new IllegalArgumentException(QUESTION + name + " needs 2 to " + max + " " + what);
    }
    Set<String> seen = new HashSet<>();
    for (Option option : options) {
      if (option == null || StringUtils.isBlank(option.value())) {
        throw new IllegalArgumentException(QUESTION + name + " has a blank entry in " + what);
      }
      if (!seen.add(option.value())) {
        throw new IllegalArgumentException(QUESTION + name + " repeats '" + option.value()
            + "' in " + what);
      }
    }
    return new ArrayList<>(options);
  }

  public Type getType() {
    return type;
  }

  public String getName() {
    return name;
  }

  public String getInstructions() {
    return instructions;
  }

  /** @return the choices or score levels, in the order given; empty for a predicate */
  public List<Option> getOptions() {
    return options;
  }

  JSONObject toJson() throws JSONException {
    JSONObject json = new JSONObject()
        .put("type", type.wire())
        .put("name", name)
        .put("instructions", instructions);
    if (type == Type.PREDICATE) {
      return json;
    }
    JSONArray entries = new JSONArray();
    for (Option option : options) {
      JSONObject entry = new JSONObject().put(type == Type.CHOICE ? "value" : "label",
          option.value());
      if (StringUtils.isNotBlank(option.description())) {
        entry.put("description", option.description());
      }
      entries.put(entry);
    }
    return json.put(type == Type.CHOICE ? "choices" : "levels", entries);
  }
}
