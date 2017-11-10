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
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.jdbc.DataSourceFactory;
import org.osgi.service.log.LogService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import liquibase.Liquibase;
import liquibase.database.DatabaseConnection;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

@Component(service={Servlet.class}, property={"alias=/sonar-collector"} )
public class SonarCollectorServlet extends HttpServlet {
    private static final long serialVersionUID = -8421243385012454373L;
    private static final String SONAR_MEASURES_COMPONENTS_METRIC_KEYS = "sonar.measures.components.metricKeys";
    private static final String SONARCOLLECTOR_JDBCURL = "sonar.collector.jdbcurl";
    // A formatter that's able to parse ISO dates without colons in the time zone spec
    static final DateTimeFormatter isoZonedDateTimeformatter = new DateTimeFormatterBuilder()
        .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .optionalStart().appendOffset("+HH:MM", "+00:00").optionalEnd()
        .optionalStart().appendOffset("+HHMM", "+0000").optionalEnd()
        .optionalStart().appendOffset("+HH", "Z").optionalEnd()
        .toFormatter();
    private final Properties applicationProperties = new Properties();
    private final URLConnectionFactory factory;
    static final ObjectMapper mapper = new ObjectMapper();
    DataSource dataSource = NullDataSource.getInstance();
    private LogService logservice;
    private String savedLogMessage;
    private Exception savedLogException;

    @Reference
    public void setDataSourceFactory(DataSourceFactory dataSourceFactory) {
        DataSource db = connectDataSource(dataSourceFactory);
        createSchemaWithLiquibase(db);
        dataSource = db;
    }

    @Reference
    public void setLogservice(LogService logservice) {
        this.logservice = logservice;
        logSavedException();
    }

    private void logSavedException() {
        if (logservice != null && savedLogMessage != null) {
            logservice.log(LogService.LOG_ERROR, savedLogMessage, savedLogException);
            savedLogMessage = null;
            savedLogException = null;
        }
    }

    private DataSource connectDataSource(DataSourceFactory dataSourceFactory) {
        Properties properties = new Properties();
        properties.setProperty(DataSourceFactory.JDBC_URL, getJdbcUrl());
        try {
            return dataSourceFactory.createDataSource(properties);
        } catch (SQLException e) {
            logError("Sonar Collector servlet unable to connect to the database", e);
            return NullDataSource.getInstance(); // Return an object that can be safely used in try-with-resource
        }
    }

    private void logError(String message, Exception e) {
        if (logservice != null) {
            logservice.log(LogService.LOG_ERROR, message, e);
        } else {
            savedLogMessage = message;
            savedLogException = e;
        }
    }

    private void createSchemaWithLiquibase(DataSource db) {
        try (Connection connection = db.getConnection()) {
            DatabaseConnection databaseConnection = new JdbcConnection(connection);
            ClassLoaderResourceAccessor classLoaderResourceAccessor = new ClassLoaderResourceAccessor(getClass().getClassLoader());
            Liquibase liquibase = new Liquibase("db-changelog/db-changelog-1.0.0.xml", classLoaderResourceAccessor, databaseConnection);
            liquibase.clearCheckSums();
            liquibase.update("");
        } catch (Exception e) {
            logError("Sonar Collector servlet unable to create or update the database schema", e);
        }
    }

    public SonarCollectorServlet(URLConnectionFactory factory) throws IOException {
        this.factory = factory;
        applicationProperties.load(getClass().getClassLoader().getResourceAsStream("application.properties"));
    }

    public SonarCollectorServlet() throws IOException {
        this(new URLConnectionFactory() {

                @Override
                public HttpURLConnection openConnection(URL url) throws IOException {
                    return (HttpURLConnection) url.openConnection();
                }
            });

    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            SonarBuild build = doCallbackToSonarServerToGetMetrics(request);
            saveMeasuresInDatabase(build);
        } catch (Exception e) {
            logservice.log(LogService.LOG_ERROR, "Sonar Collector caught exception ", e);
            response.setStatus(500); // Report internal server error
        }
    }

    SonarBuild doCallbackToSonarServerToGetMetrics(ServletRequest request) throws JsonProcessingException, IOException {
        SonarBuild build = new SonarBuild();
        JsonNode root = mapper.readTree(request.getInputStream());
        build.analysedAt = ZonedDateTime.parse(root.path("analysedAt").asText(), isoZonedDateTimeformatter).toEpochSecond();
        build.project = root.path("project").path("key").asText();
        build.version = root.path("properties").path("mavenVersion").asText();
        logWarningIfVersionIsMissing(build);
        build.serverUrl = new URL(root.path("serverUrl").asText());
        URL measurementsUrl = createSonarMeasurementsComponentUrl(build, getMetricKeys());
        HttpURLConnection connection = openConnection(measurementsUrl);
        JsonNode measurementsRoot = mapper.readTree(connection.getInputStream());
        build.measurements = parseMeasures(measurementsRoot.path("component").path("measures"));

        return build;
    }

    private void logWarningIfVersionIsMissing(SonarBuild build) {
        if ("".equals(build.version)) {
            logservice.log(LogService.LOG_WARNING, String.format("Maven version is missing from build \"%s\". Remember to add -DmavenVersion=$POM_VERSION to the sonar command", build.project));
        }
    }

    private int saveMeasuresInDatabase(SonarBuild build) throws SQLException {
        boolean isRelease = versionIsReleaseVersion(build.version);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("insert into measures (project_key, version, version_is_release, analysis_time, lines, bugs, new_bugs, vulnerabilities, new_vulnerabilities, code_smells, new_code_smells, coverage, new_coverage) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, build.project);
                statement.setString(2, build.version);
                statement.setBoolean(3, isRelease);
                statement.setTimestamp(4, new Timestamp(build.analysedAt));
                statement.setString(5, build.measurements.get("lines"));
                statement.setString(6, build.measurements.get("bugs"));
                statement.setString(7, build.measurements.get("new_bugs"));
                statement.setString(8, build.measurements.get("vulnerabilities"));
                statement.setString(9, build.measurements.get("new_vulnerabilities"));
                statement.setString(10, build.measurements.get("code_smells"));
                statement.setString(11, build.measurements.get("new_code_smells"));
                statement.setString(12, build.measurements.get("coverage"));
                statement.setString(13, build.measurements.get("new_coverage"));

                return statement.executeUpdate();
            }
        }
    }

    boolean versionIsReleaseVersion(String version) {
        return !"".equals(version) && !version.endsWith("-SNAPSHOT");
    }

    public Map<String, String> parseMeasures(JsonNode measuresNode) {
        HashMap<String, String> measuresResults = new HashMap<>();
        for (JsonNode measureNode : measuresNode) {
            String metric = measureNode.path("metric").asText();
            String value = measureNode.path("value").asText();
            if ("".equals(value)) {
                value = measureNode.path("periods").path(0).path("value").asText();
            }

            measuresResults.put(metric, value);
        }
        return measuresResults;
    }

    public String[] getMetricKeys() {
        return System.getProperty(SONAR_MEASURES_COMPONENTS_METRIC_KEYS, applicationProperties.getProperty(SONAR_MEASURES_COMPONENTS_METRIC_KEYS)).split(",");
    }

    public String getJdbcUrl() {
        return System.getProperty(SONARCOLLECTOR_JDBCURL, applicationProperties.getProperty(SONARCOLLECTOR_JDBCURL));
    }

    public URL createSonarMeasurementsComponentUrl(SonarBuild build, String[] metricKeys) throws UnsupportedEncodingException, MalformedURLException {
        String localPath = String.format("/api/measures/component?componentKey=%s&metricKeys=%s", URLEncoder.encode(build.project,"UTF-8"), String.join(",", metricKeys));
        return new URL(build.serverUrl, localPath);
    }

    HttpURLConnection openConnection(URL url) throws IOException {
        return factory.openConnection(url);
    }

}
