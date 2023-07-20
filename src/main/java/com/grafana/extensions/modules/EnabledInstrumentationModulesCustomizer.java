/*
 * Copyright Grafana Labs
 * SPDX-License-Identifier: Apache-2.0
 */

package com.grafana.extensions.modules;

import io.opentelemetry.api.internal.ConfigUtil;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.reflect.FieldUtils;

/**
 * This is the relevant upstream method:
 * https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/b917b3bf9c16d7327208a9f17a8db6d1a746829e/javaagent-tooling/src/main/java/io/opentelemetry/javaagent/tooling/config/AgentConfig.java#L12-L28
 */
public class EnabledInstrumentationModulesCustomizer {

  private static final String ENABLE_UNSUPPORTED_MODULES_PROPERTY =
      "grafana.otel.instrumentation.enable-unsupported-modules";

  private static final Logger logger =
      Logger.getLogger(EnabledInstrumentationModulesCustomizer.class.getName());

  public static Map<String, String> getDefaultProperties() {
    Map<String, String> m = new HashMap<>();
    m.put("otel.instrumentation.common.default-enabled", "false");

    for (String supportedModule : InstrumentationModules.SUPPORTED_MODULES) {
      m.put(getEnabledProperty(supportedModule), "true");
    }

    return m;
  }

  public static Map<String, String> customizeProperties(ConfigProperties configs) {
    if (configs.getBoolean(ENABLE_UNSUPPORTED_MODULES_PROPERTY, false)) {
      logger.info("Enabling unsupported modules");
      return Collections.emptyMap();
    }

    Set<String> supported =
        InstrumentationModules.SUPPORTED_MODULES.stream()
            .map(module -> ConfigUtil.normalizePropertyKey(module))
            .collect(Collectors.toSet());

    return getAllProperties(configs).entrySet().stream()
        .filter(entry -> entry.getValue().equals("true")) // it's allowed to disable modules
        .flatMap(
            entry ->
                stream(
                    getInstrumentationName(entry.getKey())
                        .flatMap(
                            instrumentationName -> {
                              if (supported.contains(instrumentationName)) {
                                return Optional.empty();
                              }

                              logger.info(
                                  String.format(
                                      "Disabling unsupported module %s (set grafana.otel.instrumentation.enable-unsupported-modules=true "
                                          + "to enable unsupported modules)",
                                      instrumentationName));
                              return Optional.of(getEnabledProperty(instrumentationName));
                            })))
        .collect(Collectors.toMap(property -> property, property -> "false"));
  }

  private static <T> Stream<T> stream(Optional<T> optional) {
    return optional.map(Stream::of).orElseGet(Stream::empty);
  }

  private static String getEnabledProperty(String name) {
    return "otel.instrumentation." + name + ".enabled";
  }

  private static Optional<String> getInstrumentationName(String property) {
    if (property.startsWith("otel.instrumentation.") && property.endsWith(".enabled")) {
      return Optional.of(
          property.substring(
              property.indexOf("otel.instrumentation.") + 21, property.indexOf(".enabled")));
    } else {
      return Optional.empty();
    }
  }

  static Map<String, String> getAllProperties(ConfigProperties configProperties) {
    try {
      //noinspection unchecked
      return (Map<String, String>) FieldUtils.readDeclaredField(configProperties, "config", true);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
