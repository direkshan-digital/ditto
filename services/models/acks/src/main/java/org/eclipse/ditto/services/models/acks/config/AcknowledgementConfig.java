/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.models.acks.config;

import java.time.Duration;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.services.utils.config.KnownConfigValue;

/**
 * Provides configuration settings for {@code Acknowledgement}s to be applied.
 *
 * @since 1.1.0
 */
@Immutable
public interface AcknowledgementConfig {

    /**
     * Returns the default/fallback timeout to apply when correlating Acknowledgements via the
     * {@code AcknowledgementForwarderActor}.
     *
     * @return the fallback timeout to apply when correlating Acknowledgements.
     */
    Duration getForwarderFallbackTimeout();

    /**
     * Returns the default/fallback lifetime to apply to {@code ResponseCollectorActor} when
     * collecting Acknowledgements.
     *
     * @return the fallback lifetime to apply when collecting Acknowledgements.
     * @since 1.2.0
     */
    Duration getCollectorFallbackLifetime();

    /**
     * Returns the default/fallback timeout to apply when asking {@code ResponseCollectorActor} for its output.
     *
     * @return the fallback lifetime to apply when collecting Acknowledgements.
     * @since 1.2.0
     */
    Duration getCollectorFallbackAskTimeout();

    /**
     * An enumeration of the known config path expressions and their associated default values for
     * {@code AcknowledgementConfig}.
     */
    enum AcknowledgementConfigValue implements KnownConfigValue {

        /**
         * The fallback timeout to apply when forwarding Acknowledgements.
         */
        FORWARDER_FALLBACK_TIMEOUT("forwarder-fallback-timeout", Duration.ofSeconds(10)),

        /**
         * The fallback lifetime to apply when forwarding Acknowledgements.
         */
        COLLECTOR_FALLBACK_LIFETIME("collector-fallback-lifetime", Duration.ofSeconds(100)),

        /**
         * The fallback timeout to apply when waiting for {@code ResponseCollectorActor} output.
         */
        COLLECTOR_FALLBACK_ASK_TIMEOUT("collector-fallback-ask-timeout", Duration.ofSeconds(120));

        private final String path;
        private final Object defaultValue;

        AcknowledgementConfigValue(final String thePath, final Object theDefaultValue) {
            path = thePath;
            defaultValue = theDefaultValue;
        }

        @Override
        public Object getDefaultValue() {
            return defaultValue;
        }

        @Override
        public String getConfigPath() {
            return path;
        }

    }

}
