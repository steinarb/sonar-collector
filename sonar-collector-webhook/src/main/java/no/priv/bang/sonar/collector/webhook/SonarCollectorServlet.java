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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Map;
import java.util.Properties;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.jdbc.DataSourceFactory;
import org.osgi.service.log.LogService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import liquibase.Liquibase;
import liquibase.database.DatabaseConnection;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import no.priv.bang.osgi.service.adapters.logservice.LogServiceAdapter;

@Component(service={Servlet.class}, property={"alias=/sonar-collector", "configurationPid=no.priv.bang.sonar.sonar-collector-webhook"} )
public class SonarCollectorServlet extends HttpServlet {
    private static final long serialVersionUID = -8421243385012454373L;
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
    final DataSourceAdapter dataSource = new DataSourceAdapter();
    private final DataSourceFactoryAdapter dataSourceFactory = new DataSourceFactoryAdapter();
    private final LogServiceAdapter logservice = new LogServiceAdapter();
    private final SonarCollectorConfiguration configuration = new SonarCollectorConfiguration(logservice);

    @Reference
    public void setDataSourceFactory(DataSourceFactory dataSourceFactory) {
        this.dataSourceFactory.setFactory(dataSourceFactory);
    }

    @Reference
    public void setLogservice(LogService logservice) {
        this.logservice.setLogService(logservice);
    }

    @Activate
    public void activate(Map<String, Object> config) {
        configuration.setConfig(config);
        DataSource db = connectDataSource(dataSourceFactory);
        createSchemaWithLiquibase(db);
        dataSource.setDatasource(db);
    }

    private DataSource connectDataSource(DataSourceFactory dataSourceFactory) {
        Properties properties = configuration.getJdbcConnectionProperties();
        try {
            return dataSourceFactory.createDataSource(properties);
        } catch (SQLException e) {
            logservice.log(LogService.LOG_ERROR, "Sonar Collector servlet unable to connect to the database", e);
            return NullDataSource.getInstance(); // Return an object that can be safely used in try-with-resource
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
            logservice.log(LogService.LOG_ERROR, "Sonar Collector servlet unable to create or update the database schema", e);
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
            SonarBuild build = callbackToSonarServerToGetMetrics(request);
            saveMeasuresInDatabase(build);
        } catch (Exception e) {
            logservice.log(LogService.LOG_ERROR, "Sonar Collector caught exception ", e);
            response.setStatus(500); // Report internal server error
        }
    }

    SonarBuild callbackToSonarServerToGetMetrics(ServletRequest request) throws IOException {
        try(InputStream postbody = request.getInputStream()) {
            JsonNode root = mapper.readTree(postbody);
            long analysedAt = parseTimestamp(root.path("analysedAt").asText());
            String project = root.path("project").path("key").asText();
            URL serverUrl = new URL(root.path("serverUrl").asText());
            URL componentsShowUrl = createSonarComponentsShowUrl(serverUrl, project);
            HttpURLConnection componentsShowUrlConnection = openConnection(componentsShowUrl);
            JsonNode componentsShowRoot = mapper.readTree(componentsShowUrlConnection.getInputStream());
            String version = componentsShowRoot.path("component").path("version").asText();
            SonarBuild build = new SonarBuild(analysedAt, project, version, serverUrl);
            logWarningIfVersionIsMissing(build, componentsShowUrl);
            URL measurementsUrl = createSonarMeasurementsComponentUrl(build, configuration.getMetricKeys());
            HttpURLConnection measurementsUrlConnection = openConnection(measurementsUrl);
            JsonNode measurementsRoot = mapper.readTree(measurementsUrlConnection.getInputStream());
            parseMeasures(build.getMeasurements(), measurementsRoot.path("component").path("measures"));
            return build;
        }
    }

    long parseTimestamp(String timestamp) {
        return ZonedDateTime.parse(timestamp, isoZonedDateTimeformatter).toEpochSecond() * 1000;
    }

    private void logWarningIfVersionIsMissing(SonarBuild build, URL componentsShowUrl) {
        if ("".equals(build.getVersion())) {
            logservice.log(LogService.LOG_WARNING, String.format("Maven version is missing from build \"%s\". API URL used to request the version, is: %s", build.getProject(), componentsShowUrl.toString()));
        }
    }

    private int saveMeasuresInDatabase(SonarBuild build) throws SQLException {
        boolean isRelease = versionIsReleaseVersion(build.getVersion());
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("insert into measures (project_key, version, version_is_release, analysis_time, lines, bugs, new_bugs, vulnerabilities, new_vulnerabilities, code_smells, new_code_smells, coverage, new_coverage) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, build.getProject());
                statement.setString(2, build.getVersion());
                statement.setBoolean(3, isRelease);
                statement.setTimestamp(4, new Timestamp(build.getAnalysedAt()));
                statement.setLong(5, Long.valueOf(build.getMeasurements().get("lines")));
                statement.setLong(6, Long.valueOf(build.getMeasurements().get("bugs")));
                statement.setLong(7, Long.valueOf(build.getMeasurements().get("new_bugs")));
                statement.setLong(8, Long.valueOf(build.getMeasurements().get("vulnerabilities")));
                statement.setLong(9, Long.valueOf(build.getMeasurements().get("new_vulnerabilities")));
                statement.setLong(10, Long.valueOf(build.getMeasurements().get("code_smells")));
                statement.setLong(11, Long.valueOf(build.getMeasurements().get("new_code_smells")));
                statement.setDouble(12, Double.valueOf(build.getMeasurements().get("coverage")));
                statement.setDouble(13, Double.valueOf(build.getMeasurements().get("new_coverage")));

                return statement.executeUpdate();
            }
        }
    }

    boolean versionIsReleaseVersion(String version) {
        return !"".equals(version) && !version.endsWith("-SNAPSHOT");
    }

    public Map<String, String> parseMeasures(Map<String, String> measuresResults, JsonNode measuresNode) {
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

    public URL createSonarComponentsShowUrl(URL serverUrl, String project) throws IOException {
        String localPath = String.format("/api/components/show?component=%s", URLEncoder.encode(project,"UTF-8"));
        return new URL(serverUrl, localPath);
    }

    public URL createSonarMeasurementsComponentUrl(SonarBuild build, String[] metricKeys) throws IOException {
        String localPath = String.format("/api/measures/component?componentKey=%s&metricKeys=%s", URLEncoder.encode(build.getProject(),"UTF-8"), String.join(",", metricKeys));
        return new URL(build.getServerUrl(), localPath);
    }

    HttpURLConnection openConnection(URL url) throws IOException {
        return factory.openConnection(url);
    }

    public SonarCollectorConfiguration getConfiguration() {
        return configuration;
    }

}
