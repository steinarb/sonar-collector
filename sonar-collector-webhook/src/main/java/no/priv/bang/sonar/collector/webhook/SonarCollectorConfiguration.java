/*
 * Copyright 2017-2022 Steinar Bang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations
 * under the License.
 */
package no.priv.bang.sonar.collector.webhook;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import org.osgi.service.log.LogService;
import org.osgi.service.log.Logger;

/***
 * A class that encapsulates finding configuration settings
 * for the {@link SonarCollectorServlet}.
 *
 * @author Steinar Bang
 *
 */
public class SonarCollectorConfiguration {
    static final String SONAR_MEASURES_COMPONENTS_METRIC_KEYS = "sonar.measures.components.metricKeys";
    public static final String SONAR_USER_TOKEN = "sonar_user_token";
    private final Properties applicationProperties = new Properties();
    private Map<String, Object> injectedconfig = Collections.emptyMap();

    void loadProperties(LogService logservice) {
        try(InputStream propertiesFile = getApplicationProperties()) {
            applicationProperties.load(propertiesFile);
        } catch (IOException e) {
            Logger logger = logservice.getLogger(getClass());
            logger.error("SonarCollectorConfiguration failed to load the application.properties", e);
        }
    }

    protected InputStream getApplicationProperties() {
        return getClass().getClassLoader().getResourceAsStream("application.properties");
    }

    public void setConfig(Map<String, Object> config) {
        if (config != null) {
            injectedconfig = config;
        } else {
            injectedconfig = Collections.emptyMap();
        }
    }

    /**
     * Retrieve the list of metrics that should be retrieved from Sonar
     * First the injected config is used to find the values, and if nothing
     * is there, the fallback is to the system property and the final
     * fallback is the built-in application.properties.
     *
     * @return a list of the metrics that will be retrieved from Sonar
     */
    public String[] getMetricKeys() {
        // Settings made in karaf configuration takes precedence
        Object metricKeysFromKarafConfig = injectedconfig.get(SONAR_MEASURES_COMPONENTS_METRIC_KEYS);
        if (metricKeysFromKarafConfig != null) {
            return ((String) metricKeysFromKarafConfig).split(",");
        }

        String metricKeys = System.getProperty(SONAR_MEASURES_COMPONENTS_METRIC_KEYS, applicationProperties.getProperty(SONAR_MEASURES_COMPONENTS_METRIC_KEYS));
        if (metricKeys != null) {
            return metricKeys.split(",");
        }

        return new String[0];
    }

    public boolean hasSonarApiUserToken() {
        return injectedconfig.containsKey(SONAR_USER_TOKEN);
    }

    public String getSonarApiUserToken() {
        return (String) injectedconfig.get(SONAR_USER_TOKEN);
    }
}
