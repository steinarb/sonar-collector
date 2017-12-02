/*
 * Copyright 2017 Steinar Bang
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

import org.osgi.service.jdbc.DataSourceFactory;
import org.osgi.service.log.LogService;

/***
 * A class that encapsulates finding configuration settings
 * for the {@link SonarCollectorServlet}.
 *
 * @author Steinar Bang
 *
 */
public class SonarCollectorConfiguration {
    static final String SONARCOLLECTOR_JDBC_URL = "sonar.collector.jdbc.url";
    static final String SONARCOLLECTOR_JDBC_USER = "sonar.collector.jdbc.user";
    static final String SONARCOLLECTOR_JDBC_PASS = "sonar.collector.jdbc.password";
    private final Properties applicationProperties = new Properties();
    private Map<String, Object> injectedconfig = Collections.emptyMap();


    SonarCollectorConfiguration(LogService logservice) {
        try(InputStream propertiesFile = getApplicationProperties()) {
            applicationProperties.load(propertiesFile);
        } catch (IOException e) {
            logservice.log(LogService.LOG_ERROR, "SonarCollectorConfiguration failed to load the application.properties", e);
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
     * Retrive the settings necessary to open a JDBC connection from
     * the configuration.  First the injected configuration service is
     * used, then the system properties, and finally the embedded application.properties
     * file is used.
     *
     * @return a properties object that can be sent to {@link DataSourceFactory#createDataSource(Properties)}
     */
    public Properties getJdbcConnectionProperties() {
        Properties properties = new Properties();
        setJdbcUrlIfNotNull(properties);
        setPropertyIfNotNull(properties, DataSourceFactory.JDBC_USER, SonarCollectorConfiguration.SONARCOLLECTOR_JDBC_USER);
        setPropertyIfNotNull(properties, DataSourceFactory.JDBC_PASSWORD, SonarCollectorConfiguration.SONARCOLLECTOR_JDBC_PASS);
        return properties;
    }

    public String getJdbcUrl() {
        // Settings made in karaf configuration takes precedence
        Object jdbcUrlFromKarafConfig = injectedconfig.get(SONARCOLLECTOR_JDBC_URL);
        if (jdbcUrlFromKarafConfig != null) {
            return (String) jdbcUrlFromKarafConfig;
        }

        // Fallback to system property which in turn falls back to application.properties
        return System.getProperty(SONARCOLLECTOR_JDBC_URL, applicationProperties.getProperty(SONARCOLLECTOR_JDBC_URL));
    }

    private void setJdbcUrlIfNotNull(Properties properties) {
        String jdbcUrl = getJdbcUrl();
        if (jdbcUrl != null) {
            properties.setProperty(DataSourceFactory.JDBC_URL, jdbcUrl);
        }
    }

    private void setPropertyIfNotNull(Properties properties, String targetPropertyName, String sourcePropertyName) {
        // Settings made in karaf configuration takes precedence
        Object valueFromKarafConfig = injectedconfig.get(sourcePropertyName);
        if (valueFromKarafConfig != null) {
            properties.setProperty(targetPropertyName, (String) valueFromKarafConfig);
            return;
        }

        // Fallback to system property which in turn falls back to application.properties
        String value = System.getProperty(sourcePropertyName, applicationProperties.getProperty(sourcePropertyName));
        if (value != null) {
            properties.setProperty(targetPropertyName, value);
        }
    }
}
