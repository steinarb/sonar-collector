/*
 * Copyright 2017-2024 Steinar Bang
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
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Base64;
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
import org.osgi.service.log.LogService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import liquibase.Scope;
import liquibase.Scope.ScopedRunner;
import liquibase.ThreadLocalScopeManager;
import liquibase.changelog.ChangeLogParameters;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DatabaseChangelogCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import no.priv.bang.osgi.service.adapters.jdbc.DataSourceAdapter;
import no.priv.bang.osgi.service.adapters.logservice.LogServiceAdapter;
import no.priv.bang.osgi.service.adapters.logservice.LoggerAdapter;

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
    private final LogServiceAdapter logservice = new LogServiceAdapter();
    private final LoggerAdapter logger = new LoggerAdapter(getClass());
    final SonarCollectorConfiguration configuration = new SonarCollectorConfiguration();

    @Reference(target = "(osgi.jndi.service.name=jdbc/sonar-collector)")
    public void setDataSource(DataSource ds) {
        this.dataSource.setDatasource(ds);
    }

    @Reference
    public void setLogservice(LogService logservice) {
        this.logservice.setLogService(logservice);
        this.logger.setLogService(logservice);
    }

    @Activate
    public void activate(Map<String, Object> config) {
        configuration.loadProperties(logservice);
        configuration.setConfig(config);
        Scope.setScopeManager(new ThreadLocalScopeManager());
        createSchemaWithLiquibase(dataSource);
    }

    private void createSchemaWithLiquibase(DataSource db) {
        try (var connection = db.getConnection()) {
            try (var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection))) {
                Map<String, Object> scopeObjects = Map.of(
                    Scope.Attr.database.name(), database,
                    Scope.Attr.resourceAccessor.name(), new ClassLoaderResourceAccessor(getClass().getClassLoader()));

                Scope.child(scopeObjects, (ScopedRunner<?>) () -> new CommandScope("update")
                    .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database)
                    .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, "db-changelog/db-changelog-1.0.0.xml")
                    .addArgumentValue(DatabaseChangelogCommandStep.CHANGELOG_PARAMETERS, new ChangeLogParameters(database))
                    .execute());
            }
        } catch (Exception e) {
            logger.error("Sonar Collector servlet unable to create or update the database schema", e);
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
            var build = callbackToSonarServerToGetMetrics(request);
            saveMeasuresInDatabase(build);
        } catch (Exception e) {
            logger.error("Sonar Collector caught exception ", e);
            response.setStatus(500); // Report internal server error
        }
    }

    SonarBuild callbackToSonarServerToGetMetrics(ServletRequest request) throws IOException {
        try(var postbody = request.getInputStream()) {
            var root = mapper.readTree(postbody);
            var analysedAt = findAnalysisTimeAsMillisecondsFromEpoch(root);
            var project = findProjectKey(root);
            var serverUrl = findSonarServerUrl(root);
            logger.info("sonar-collector webhook called for project {} from server {}", project, serverUrl);

            var version = getAnalyzedProjectMavenVersionFromSonarServer(project, serverUrl);

            return getAnalyzedProjectMetricsFromSonarServer(serverUrl, project, version, analysedAt);
        }
    }

    private String getAnalyzedProjectMavenVersionFromSonarServer(String project, URL serverUrl) throws IOException {
        var componentsShowUrl = createSonarComponentsShowUrl(serverUrl, project);
        var componentsShowUrlConnection = openConnection(componentsShowUrl);
        var componentsShowRoot = mapper.readTree(componentsShowUrlConnection.getInputStream());
        var version = componentsShowRoot.path("component").path("version").asText();
        if ("".equals(version)) {
            logger.warn(String.format("Maven version is missing from build \"%s\". API URL used to request the version, is: %s", project, componentsShowUrl.toString()));
        }

        return version;
    }

    private SonarBuild getAnalyzedProjectMetricsFromSonarServer(URL serverUrl, String project, String version, long analysedAt) throws IOException {
        var build = new SonarBuild(analysedAt, project, version, serverUrl);
        var measurementsUrl = createSonarMeasurementsComponentUrl(build, configuration.getMetricKeys());
        var measurementsUrlConnection = openConnection(measurementsUrl);
        var measurementsRoot = mapper.readTree(measurementsUrlConnection.getInputStream());
        parseMeasures(build.getMeasurements(), measurementsRoot.path("component").path("measures"));
        return build;
    }

    private int saveMeasuresInDatabase(SonarBuild build) throws SQLException {
        var isRelease = versionIsReleaseVersion(build.getVersion());
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement("insert into measures (project_key, version, version_is_release, analysis_time, lines, bugs, new_bugs, vulnerabilities, new_vulnerabilities, code_smells, new_code_smells, coverage, new_coverage, complexity, sqale_rating, new_maintainability_rating, security_rating, new_security_rating, reliability_rating, new_reliability_rating) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
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
                statement.setLong(14, Long.valueOf(build.getMeasurements().get("complexity")));
                statement.setString(15, extractRating("sqale_rating", build.getMeasurements()));
                statement.setString(16, extractRating("new_maintainability_rating", build.getMeasurements()));
                statement.setString(17, extractRating("security_rating", build.getMeasurements()));
                statement.setString(18, extractRating("new_security_rating", build.getMeasurements()));
                statement.setString(19, extractRating("reliability_rating", build.getMeasurements()));
                statement.setString(20, extractRating("new_reliability_rating", build.getMeasurements()));

                return statement.executeUpdate();
            }
        }
    }

    boolean versionIsReleaseVersion(String version) {
        return !"".equals(version) && !version.endsWith("-SNAPSHOT");
    }

    public Map<String, String> parseMeasures(Map<String, String> measuresResults, JsonNode measuresNode) {
        for (var measureNode : measuresNode) {
            var metric = measureNode.path("metric").asText();
            var value = measureNode.path("value").asText();
            if ("".equals(value)) {
                value = measureNode.path("periods").path(0).path("value").asText();
            }

            measuresResults.put(metric, value);
        }
        return measuresResults;
    }

    public URL createSonarComponentsShowUrl(URL serverUrl, String project) throws IOException {
        var localPath = String.format("/api/components/show?component=%s", URLEncoder.encode(project,"UTF-8"));
        return new URL(serverUrl, localPath);
    }

    public URL createSonarMeasurementsComponentUrl(SonarBuild build, String[] metricKeys) throws IOException {
        var localPath = String.format("/api/measures/component?component=%s&metricKeys=%s", URLEncoder.encode(build.getProject(),"UTF-8"), String.join(",", metricKeys));
        return new URL(build.getServerUrl(), localPath);
    }

    private long findAnalysisTimeAsMillisecondsFromEpoch(JsonNode root) {
        return parseTimestamp(root.path("analysedAt").asText());
    }

    private String findProjectKey(JsonNode root) {
        return root.path("project").path("key").asText();
    }

    private URL findSonarServerUrl(JsonNode root) throws MalformedURLException {
        return new URL(root.path("serverUrl").asText());
    }

    long parseTimestamp(String timestamp) {
        return ZonedDateTime.parse(timestamp, isoZonedDateTimeformatter).toEpochSecond() * 1000;
    }

    HttpURLConnection openConnection(URL url) throws IOException {
        if (configuration.hasSonarApiUserToken()) {
            var connection = factory.openConnection(url);
            var authorization =
                "Basic " +
                Base64.getEncoder().encodeToString((configuration.getSonarApiUserToken() + ":").getBytes());
            connection.setRequestProperty("Authorization", authorization);
            return connection;
        } else {
            return factory.openConnection(url);
        }
    }

    public SonarCollectorConfiguration getConfiguration() {
        return configuration;
    }

    public String extractRating(String rating, Map<String, String> measuresResults) {
        return convertFromNumberToRating(rating, measuresResults.get(rating));
    }

    public String convertFromNumberToRating(String rating, String number) {
        if ("1.0".equals(number)) {
            return "A";
        } else if ("2.0".equals(number)) {
            return "B";
        } else if ("3.0".equals(number)) {
            return "C";
        } else if ("4.0".equals(number)) {
            return "D";
        } else if ("5.0".equals(number)) {
            return "E";
        }

        logger.warn("Unable to convert number \"{}\" to rating for rating \"{}\"", number, rating);
        return "";
    }

}
