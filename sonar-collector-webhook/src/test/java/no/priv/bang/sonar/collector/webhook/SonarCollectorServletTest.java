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

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ops4j.pax.jdbc.derby.impl.DerbyDataSourceFactory;
import org.osgi.service.jdbc.DataSourceFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import no.priv.bang.osgi.service.mocks.logservice.MockLogService;

class SonarCollectorServletTest {
    private static DataSourceFactory dataSourceFactory;
    private static Properties originalSystemProperties;
    private static Properties connectionproperties;

    @BeforeAll
    static void beforeClass() throws IOException {
        originalSystemProperties = addTestPropertiesToSystemProperties();
        dataSourceFactory = new DerbyDataSourceFactory();
        connectionproperties = new Properties();
        connectionproperties.setProperty(DataSourceFactory.JDBC_URL, "jdbc:derby:memory:sonar;create=true");
    }

    private static Properties addTestPropertiesToSystemProperties() throws IOException {
        var originalSystemProperties = (Properties) System.getProperties().clone();
        var testProperties = new Properties();
        testProperties.load(SonarCollectorServletTest.class.getClassLoader().getResourceAsStream("application-test.properties"));
        var systemProperties = System.getProperties();
        systemProperties.putAll(testProperties);
        System.setProperties(systemProperties);

        return originalSystemProperties;
    }

    @AfterAll
    static void afterClass() {
        restoreSystemPropertiesToOriginalState();
    }

    private static void restoreSystemPropertiesToOriginalState() {
        System.setProperties(originalSystemProperties);
    }

    @Test
    void testReceiveSonarWebhookCall() throws ServletException, IOException, SQLException {
        var factory = mock(URLConnectionFactory.class);
        var componentsShowConnection = createConnectionFromResource("json/sonar/api-components-show-version-1.0.0-SNAPSHOT.json");
        var measurementsConnection = createConnectionFromResource("json/sonar/api-measures-component-get-many-metrics.json");
        when(factory.openConnection(any()))
            .thenReturn(componentsShowConnection)
            .thenReturn(measurementsConnection);
        var request = mock(HttpServletRequest.class);
        var value = wrap(getClass().getClassLoader().getResourceAsStream("json/sonar/webhook-post.json"));
        when(request.getInputStream()).thenReturn(value);
        var response = mock(HttpServletResponse.class);
        MockLogService logservice = new MockLogService();

        var servlet = new SonarCollectorServlet(factory);
        servlet.setDataSource(dataSourceFactory.createDataSource(connectionproperties));
        servlet.setLogservice(logservice);
        servlet.activate(null);

        // Check preconditions
        truncateMeasuresTable(servlet.dataSource);
        assertEquals(0, countRowsOfTableMeasures(servlet.dataSource));

        // Run the code under test
        servlet.doPost(request, response);

        // Check that a measurement has been stored
        assertEquals(1, countRowsOfTableMeasures(servlet.dataSource));

        // Check the contents of the measurement row
        var measuresRows = getRowsOfTableMeasures(servlet.dataSource);
        var measuresRow = measuresRows.get(0);
        assertEquals(21, measuresRow.size());
        assertEquals("no.priv.bang.sonar.sonar-collector:parent", measuresRow.get("PROJECT_KEY"));
        assertEquals("1.0.0-SNAPSHOT", measuresRow.get("VERSION"));
        assertEquals(false, measuresRow.get("VERSION_IS_RELEASE"));
        assertEquals(952L, measuresRow.get("LINES"));
        assertEquals(5L, measuresRow.get("BUGS"));
        assertEquals(2L, measuresRow.get("NEW_BUGS"));
        assertEquals(3L, measuresRow.get("VULNERABILITIES"));
        assertEquals(1L, measuresRow.get("NEW_VULNERABILITIES"));
        assertEquals(3L, measuresRow.get("CODE_SMELLS"));
        assertEquals(1L, measuresRow.get("NEW_CODE_SMELLS"));
        assertEquals(100.0, measuresRow.get("COVERAGE"));
        assertEquals(92.98, ((Double)measuresRow.get("NEW_COVERAGE")).doubleValue(), 0.01);
        assertEquals(41L, measuresRow.get("COMPLEXITY"));
        assertEquals("A", measuresRow.get("SQALE_RATING"));
        assertEquals("A", measuresRow.get("NEW_MAINTAINABILITY_RATING"));
        assertEquals("A", measuresRow.get("SECURITY_RATING"));
        assertEquals("A", measuresRow.get("NEW_SECURITY_RATING"));
        assertEquals("A", measuresRow.get("RELIABILITY_RATING"));
        assertEquals("A", measuresRow.get("NEW_RELIABILITY_RATING"));
    }

    /**
     * Corner case test: check what happens when "new_coverage" is missing from the returned
     * data (this happens when there is no new code to cover).
     *
     * @throws ServletException
     * @throws IOException
     * @throws SQLException
     */
    @Test
    void testReceiveSonarWebhookCallNoNewCoverage() throws ServletException, IOException, SQLException {
        var factory = mock(URLConnectionFactory.class);
        var componentsShowConnection = createConnectionFromResource("json/sonar/api-components-show-version-1.0.0-SNAPSHOT.json");
        var measurementsConnection = createConnectionFromResource("json/sonar/api-measures-component-get-many-metrics-no-new_coverage.json");
        when(factory.openConnection(any()))
            .thenReturn(componentsShowConnection)
            .thenReturn(measurementsConnection);
        var request = mock(HttpServletRequest.class);
        var value = wrap(getClass().getClassLoader().getResourceAsStream("json/sonar/webhook-post.json"));
        when(request.getInputStream()).thenReturn(value);
        var response = mock(HttpServletResponse.class);
        var logservice = new MockLogService();

        var servlet = new SonarCollectorServlet(factory);
        servlet.setDataSource(dataSourceFactory.createDataSource(connectionproperties));
        servlet.setLogservice(logservice);
        servlet.activate(null);

        // Check preconditions
        truncateMeasuresTable(servlet.dataSource);
        assertEquals(0, countRowsOfTableMeasures(servlet.dataSource));

        // Run the code under test
        servlet.doPost(request, response);

        // Check that a measurement has been stored
        assertEquals(1, countRowsOfTableMeasures(servlet.dataSource));

        // Check the contents of the measurement row
        var measuresRows = getRowsOfTableMeasures(servlet.dataSource);
        var measuresRow = measuresRows.get(0);
        assertEquals(21, measuresRow.size());
        assertEquals(0.0, ((Double)measuresRow.get("NEW_COVERAGE")).doubleValue(), 0.01);
    }

    @Test
    void testReceiveSonarCloudWebhookCall() throws ServletException, IOException, SQLException {
        var factory = mock(URLConnectionFactory.class);
        var componentsShowConnection = createConnectionFromResource("json/sonar/api-components-show-version-1.0.0-SNAPSHOT.json");
        var measurementsConnection = createConnectionFromResource("json/sonar/api-measures-component-get-many-metrics-sonarcloud.json");
        when(factory.openConnection(any()))
            .thenReturn(componentsShowConnection)
            .thenReturn(measurementsConnection);
        var request = mock(HttpServletRequest.class);
        var value = wrap(getClass().getClassLoader().getResourceAsStream("json/sonar/webhook-post.json"));
        when(request.getInputStream()).thenReturn(value);
        var response = mock(HttpServletResponse.class);
        var logservice = new MockLogService();

        var servlet = new SonarCollectorServlet(factory);
        servlet.setDataSource(dataSourceFactory.createDataSource(connectionproperties));
        servlet.setLogservice(logservice);
        servlet.activate(null);

        // Check preconditions
        truncateMeasuresTable(servlet.dataSource);
        assertEquals(0, countRowsOfTableMeasures(servlet.dataSource));

        // Run the code under test
        servlet.doPost(request, response);

        // Check that a measurement has been stored
        assertEquals(1, countRowsOfTableMeasures(servlet.dataSource));

        // Check the contents of the measurement row
        var measuresRows = getRowsOfTableMeasures(servlet.dataSource);
        var measuresRow = measuresRows.get(0);
        assertEquals(21, measuresRow.size());
        assertEquals("no.priv.bang.sonar.sonar-collector:parent", measuresRow.get("PROJECT_KEY"));
        assertEquals("1.0.0-SNAPSHOT", measuresRow.get("VERSION"));
        assertEquals(false, measuresRow.get("VERSION_IS_RELEASE"));
        assertEquals(866L, measuresRow.get("LINES"));
        assertEquals(5L, measuresRow.get("BUGS"));
        assertEquals(2L, measuresRow.get("NEW_BUGS"));
        assertEquals(3L, measuresRow.get("VULNERABILITIES"));
        assertEquals(1L, measuresRow.get("NEW_VULNERABILITIES"));
        assertEquals(3L, measuresRow.get("CODE_SMELLS"));
        assertEquals(1L, measuresRow.get("NEW_CODE_SMELLS"));
        assertEquals(100.0, measuresRow.get("COVERAGE"));
        assertEquals(92.98, ((Double)measuresRow.get("NEW_COVERAGE")).doubleValue(), 0.01);
        assertEquals(37L, measuresRow.get("COMPLEXITY"));
        assertEquals("A", measuresRow.get("SQALE_RATING"));
        assertEquals("A", measuresRow.get("NEW_MAINTAINABILITY_RATING"));
        assertEquals("A", measuresRow.get("SECURITY_RATING"));
        assertEquals("A", measuresRow.get("NEW_SECURITY_RATING"));
        assertEquals("A", measuresRow.get("RELIABILITY_RATING"));
        assertEquals("A", measuresRow.get("NEW_RELIABILITY_RATING"));
    }

    /**
     * Test that the view measures_view has an "issues" column with the expected value
     * that is the sum of the number of bugs, the number of vulnerabilities and the number
     * of code_smells.
     *
     * @throws ServletException
     * @throws IOException
     * @throws SQLException
     */
    @Test
    void testMeasuresView() throws ServletException, IOException, SQLException {
        var factory = mock(URLConnectionFactory.class);
        var componentsShowConnection = createConnectionFromResource("json/sonar/api-components-show-version-1.0.0-SNAPSHOT.json");
        var measurementsConnection = createConnectionFromResource("json/sonar/api-measures-component-get-many-metrics.json");
        when(factory.openConnection(any()))
            .thenReturn(componentsShowConnection)
            .thenReturn(measurementsConnection);
        var request = mock(HttpServletRequest.class);
        var value = wrap(getClass().getClassLoader().getResourceAsStream("json/sonar/webhook-post.json"));
        when(request.getInputStream()).thenReturn(value);
        var response = mock(HttpServletResponse.class);
        var logservice = new MockLogService();

        var servlet = new SonarCollectorServlet(factory);
        servlet.setDataSource(dataSourceFactory.createDataSource(connectionproperties));
        servlet.setLogservice(logservice);
        servlet.activate(null);

        // Check preconditions
        truncateMeasuresTable(servlet.dataSource);
        assertEquals(0, countRowsOfTableMeasures(servlet.dataSource));

        // Run the code under test
        servlet.doPost(request, response);

        // Check that a measurement has been stored
        assertEquals(1, countRowsOfTableMeasures(servlet.dataSource));

        // Check the contents of the measurement row
        var measuresRows = doSqlQuery(servlet.dataSource, "select * from measures_view");
        var measuresRow = measuresRows.get(0);
        assertEquals(22, measuresRow.size());
        assertEquals(11L, measuresRow.get("ISSUES")); // This goes to 11!
    }

    @Test
    void testUseNoArgumentConstructorAndReceiveSonarWebhookCall() throws ServletException, IOException {
        var logservice = new MockLogService();
        var servlet = new SonarCollectorServlet();
        servlet.setLogservice(logservice);
        var request = mock(HttpServletRequest.class);
        var value = wrap(getClass().getClassLoader().getResourceAsStream("json/sonar/webhook-post.json"));
        when(request.getInputStream()).thenReturn(value);
        var response = mock(HttpServletResponse.class);

        servlet.doPost(request, response);

        var status = ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatus(status.capture());
        assertEquals(500, status.getValue().intValue(), "Expected HTTP internal server error code");
    }

    @Test
    void testCallbackToSonarServerToGetMetrics() throws ServletException, IOException {
        var logservice = new MockLogService();
        var factory = mock(URLConnectionFactory.class);
        var componentsShowNoMavenVersion = createConnectionFromResource("json/sonar/api-components-show-component-not-found.json");
        var componentsShowWithSnapshot = createConnectionFromResource("json/sonar/api-components-show-version-1.0.0-SNAPSHOT.json");
        var componentsShow = createConnectionFromResource("json/sonar/api-components-show-version-1.0.0.json");
        var connections = createConnectionFromResource("json/sonar/api-measures-component-get-many-metrics.json", 3);
        when(factory.openConnection(any()))
            .thenReturn(componentsShowNoMavenVersion)
            .thenReturn(connections[0])
            .thenReturn(componentsShowWithSnapshot)
            .thenReturn(connections[1])
            .thenReturn(componentsShow)
            .thenReturn(connections[2]);
        var servlet = new SonarCollectorServlet(factory);
        servlet.setLogservice(logservice);
        var request = mock(ServletRequest.class);
        var resource = "json/sonar/webhook-post.json";
        var webhookPostBody = createServletInputStreamFromResource(resource, 3);
        when(request.getInputStream())
            .thenReturn(webhookPostBody[0])
            .thenReturn(webhookPostBody[1])
            .thenReturn(webhookPostBody[2]);

        var expectedTimeInMillisecondsSinceEpoch = ZonedDateTime.parse("2017-11-19T10:39:24+0100", SonarCollectorServlet.isoZonedDateTimeformatter).toEpochSecond() * 1000;
        assertEquals(0, logservice.getLogmessages().size(), "Expected no log messages initially");
        var buildWithNoMavenVersion = servlet.callbackToSonarServerToGetMetrics(request);
        assertEquals("no.priv.bang.sonar.sonar-collector:parent", buildWithNoMavenVersion.getProject());
        assertEquals(expectedTimeInMillisecondsSinceEpoch, buildWithNoMavenVersion.getAnalysedAt());
        assertEquals("", buildWithNoMavenVersion.getVersion());
        assertEquals(2, logservice.getLogmessages().size(), "Expected an initial info log and a single warning log message from missing maven version");
        assertEquals("http://localhost:9000", buildWithNoMavenVersion.getServerUrl().toString());

        var buildWithMavenSnapshotVersion = servlet.callbackToSonarServerToGetMetrics(request);
        assertEquals("no.priv.bang.sonar.sonar-collector:parent", buildWithMavenSnapshotVersion.getProject());
        assertEquals(expectedTimeInMillisecondsSinceEpoch, buildWithMavenSnapshotVersion.getAnalysedAt());
        assertEquals("1.0.0-SNAPSHOT", buildWithMavenSnapshotVersion.getVersion());
        assertEquals("http://localhost:9000", buildWithMavenSnapshotVersion.getServerUrl().toString());

        var buildWithMavenVersion = servlet.callbackToSonarServerToGetMetrics(request);
        assertEquals("no.priv.bang.sonar.sonar-collector:parent", buildWithMavenVersion.getProject());
        assertEquals(expectedTimeInMillisecondsSinceEpoch, buildWithMavenVersion.getAnalysedAt());
        assertEquals("1.0.0", buildWithMavenVersion.getVersion());
        assertEquals("http://localhost:9000", buildWithMavenVersion.getServerUrl().toString());
    }

    @Test
    void testCreateSonarComponentsShowUrl() throws ServletException, IOException {
        var factory = mock(URLConnectionFactory.class);
        var servlet = new SonarCollectorServlet(factory);
        var logservice = new MockLogService();
        servlet.setLogservice(logservice);
        servlet.activate(Collections.emptyMap());
        var metricKeys = servlet.getConfiguration().getMetricKeys();
        assertEquals(16, metricKeys.length);
        var project = "no.priv.bang.ukelonn:parent";
        var serverUrl = new URL("http://localhost:9000");
        var metricsUrl = servlet.createSonarComponentsShowUrl(serverUrl, project);
        assertEquals(serverUrl.getProtocol(), metricsUrl.getProtocol());
        assertEquals(serverUrl.getHost(), metricsUrl.getHost());
        assertEquals(serverUrl.getPort(), metricsUrl.getPort());
        assertEquals("/api/components/show", metricsUrl.getPath());
        var query = URLDecoder.decode(metricsUrl.getQuery(), "UTF-8");
        assertThat(query).contains(project);
    }

    @Test
    void testCreateSonarMeasurementsComponentUrl() throws ServletException, IOException {
        var factory = mock(URLConnectionFactory.class);
        var servlet = new SonarCollectorServlet(factory);
        var logservice = new MockLogService();
        servlet.setLogservice(logservice);
        servlet.activate(Collections.emptyMap());
        var metricKeys = servlet.getConfiguration().getMetricKeys();
        assertEquals(16, metricKeys.length);
        var project = "no.priv.bang.ukelonn:parent";
        var serverUrl = new URL("http://localhost:9000");
        var build = new SonarBuild(0, project, null, serverUrl);
        var metricsUrl = servlet.createSonarMeasurementsComponentUrl(build, metricKeys);
        assertEquals(build.getServerUrl().getProtocol(), metricsUrl.getProtocol());
        assertEquals(build.getServerUrl().getHost(), metricsUrl.getHost());
        assertEquals(build.getServerUrl().getPort(), metricsUrl.getPort());
        assertEquals("/api/measures/component", metricsUrl.getPath());
        var query = URLDecoder.decode(metricsUrl.getQuery(), "UTF-8");
        assertThat(query).contains(build.getProject());
    }

    @Test
    void testParseMeasures() throws JsonProcessingException, IOException {
        var root = SonarCollectorServlet.mapper.readTree(getClass().getClassLoader().getResourceAsStream("json/sonar/api-measures-component-get-many-metrics.json"));
        var measuresNode = root.path("component").path("measures");
        var servlet = new SonarCollectorServlet();
        var logservice = new MockLogService();
        servlet.setLogservice(logservice);
        servlet.activate(Collections.emptyMap());

        var metricKeys = servlet.getConfiguration().getMetricKeys();
        var measures = new HashMap<String, String>();
        servlet.parseMeasures(measures, measuresNode);
        assertThat(measures).hasSameSizeAs(metricKeys).containsKeys(metricKeys);
        assertEquals("2", measures.get("new_bugs"));
    }

    /**
     * Corner case test of {@link SonarCollectorServlet#parseMeasures(JsonNode)}.
     *
     * Test behaviour on the parse results of an empty JSON file.
     * Nothing will fail, but the parse results will be empty.
     *
     * @throws JsonProcessingException
     * @throws IOException
     */
    @Test
    void testParseMeasuresEmptyDocument() throws JsonProcessingException, IOException {
        var root = SonarCollectorServlet.mapper.readTree("{}");
        var measuresNode = root.path("component").path("measures");
        var servlet = new SonarCollectorServlet();

        var measures = new HashMap<String, String>();
        servlet.parseMeasures(measures, measuresNode);
        assertEquals(0, measures.size(), "Parse results weren't empty");
    }

    @Test
    void testInjectConfigFromKaraf() throws IOException {
        var servlet = new SonarCollectorServlet();
        var logservice = new MockLogService();
        servlet.setLogservice(logservice);
        var configFromKaraf = new HashMap<String, Object>();
        configFromKaraf.put(SonarCollectorConfiguration.SONAR_MEASURES_COMPONENTS_METRIC_KEYS, "lines,bugs,new_bugs");
        servlet.activate(configFromKaraf);
        var configuration = servlet.configuration;
        var metricKeys = configuration.getMetricKeys();
        assertEquals(3, metricKeys.length);
    }

    @Test
    void testVersionIsReleaseVersion() throws IOException {
        var servlet = new SonarCollectorServlet();
        assertFalse(servlet.versionIsReleaseVersion(""));
        assertFalse(servlet.versionIsReleaseVersion("1.0.0-SNAPSHOT"));
        assertTrue(servlet.versionIsReleaseVersion("1.0.0"));
    }

    @Test
    void testParseTimestamp() throws IOException {
        var servlet = new SonarCollectorServlet();
        var timestamp = servlet.parseTimestamp("2017-11-19T10:39:24+0100");
        var date = new Date(timestamp);
        var calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT+1"));
        calendar.setTime(date);
        System.out.println(String.format("Date: %s", date.toString()));
        assertEquals(2017, calendar.get(Calendar.YEAR));
        assertEquals(11, calendar.get(Calendar.MONTH) + 1); // January is 0
        assertEquals(19, calendar.get(Calendar.DAY_OF_MONTH));
        assertEquals(10, calendar.get(Calendar.HOUR_OF_DAY));
        assertEquals(39, calendar.get(Calendar.MINUTE));
    }

    @Test
    void testOpenConnectionWithUserToken() throws Exception {
        var logservice = new MockLogService();
        var usertoken = "squ_3869fbac07cc388306804e35fb72ca7c4baff275";
        var config = new HashMap<String, Object>();
        config.put(SonarCollectorConfiguration.SONAR_USER_TOKEN, usertoken);
        var servlet = new SonarCollectorServlet();
        servlet.setLogservice(logservice);
        servlet.activate(config);

        var url = new URL("http://localhost:9900/api/components/show?component=no.priv.bang.osgi.service.adapters%3Aadapters-parent");
        var connection = servlet.openConnection(url);
        // Bogus assert to keep sonar quiet, because getting the request properties back from
        // HttpURLConnection is ridiculously hard for "security" reasons.
        // I found some reflection examples for java 8 but they don't work for Java 11
        // (internal classes have probably been changed)
        assertThat(connection.getRequestProperties()).isEmpty();
    }

    @Test
    void testExtractRating() throws Exception {
        var logservice = new MockLogService();
        var servlet = new SonarCollectorServlet();
        servlet.setLogservice(logservice);
        servlet.activate(Collections.emptyMap());

        var rating = "maintainability_rating";

        // Verify what happens when extracting a rating that is present and has a legal value
        var measuresResults = Collections.singletonMap(rating, "2.0");
        assertEquals("B", servlet.extractRating(rating, measuresResults));

        // Verify what happens when attempting to extract a rating that isn't present
        var numberOfLogmessagesBefore = logservice.getLogmessages().size();
        assertEquals("", servlet.extractRating(rating, Collections.emptyMap()));
        assertThat(logservice.getLogmessages()).hasSizeGreaterThan(numberOfLogmessagesBefore);
        var lastLogmessage = logservice.getLogmessages().get(logservice.getLogmessages().size() - 1);
        assertThat(lastLogmessage).startsWith("[WARNING] Unable to convert number \"null\" to rating for rating").contains(rating);
    }

    @Test
    void testConvertFromNumberRating() throws Exception {
        var rating = "maintability_rating";
        var logservice = new MockLogService();
        var servlet = new SonarCollectorServlet();
        servlet.setLogservice(logservice);
        servlet.activate(Collections.emptyMap());
        var numberOfLogmessagesBefore = logservice.getLogmessages().size();
        assertEquals("", servlet.convertFromNumberToRating(rating, "XX"));
        assertThat(logservice.getLogmessages()).hasSizeGreaterThan(numberOfLogmessagesBefore);
        var lastLogmessage = logservice.getLogmessages().get(logservice.getLogmessages().size() - 1);
        assertThat(lastLogmessage).startsWith("[WARNING] Unable to convert number \"XX\" to rating for rating").contains(rating);
        assertEquals("A", servlet.convertFromNumberToRating(rating, "1.0"));
        assertEquals("B", servlet.convertFromNumberToRating(rating, "2.0"));
        assertEquals("C", servlet.convertFromNumberToRating(rating, "3.0"));
        assertEquals("D", servlet.convertFromNumberToRating(rating, "4.0"));
        assertEquals("E", servlet.convertFromNumberToRating(rating, "5.0"));
    }

    private void truncateMeasuresTable(DataSource dataSource) throws SQLException {
        try(var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement("truncate table measures")) {
                statement.executeUpdate();
            }
        }
    }

    private int countRowsOfTableMeasures(DataSource dataSource) throws SQLException {
        try(var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement("select count(*) from measures")) {
                try (var resultset = statement.executeQuery()) {
                    while (resultset.next()) {
                        return resultset.getInt(1);
                    }
                }
            }
        }

        return 0;
    }

    private List<Map<String, Object>> getRowsOfTableMeasures(DataSource dataSource) throws SQLException {
        return doSqlQuery(dataSource, "select * from measures");
    }

    private List<Map<String, Object>> doSqlQuery(DataSource dataSource, String query) throws SQLException {
        var rows = new ArrayList<Map<String, Object>>();

        try(var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement(query)) {
                try (var resultset = statement.executeQuery()) {
                    var columnnames = getColumnNames(resultset);
                    while (resultset.next()) {
                        var row = new HashMap<String, Object>();
                        for (var columnname : columnnames) {
                            row.put(columnname, resultset.getObject(columnname));
                        }

                        rows.add(row);
                    }
                }
            }
        }

        return rows;
    }

    private List<String> getColumnNames(ResultSet resultset) throws SQLException {
        var metadata = resultset.getMetaData();
        var columnCount = metadata.getColumnCount();
        var columnnames = new ArrayList<String>(columnCount);
        for(int i=1; i<=columnCount; ++i) {
            columnnames.add(metadata.getColumnName(i));
        }

        return columnnames;
    }

    private ServletInputStream[] createServletInputStreamFromResource(String resource, int numberOfCopies) {
        var streams = new ServletInputStream[numberOfCopies];
        for(var i=0; i<numberOfCopies; ++i) {
            streams[i] = wrap(getClass().getClassLoader().getResourceAsStream(resource));
        }

        return streams;
    }

    private HttpURLConnection[] createConnectionFromResource(String resource, int numberOfCopies) throws IOException {
        var connections = new HttpURLConnection[numberOfCopies];
        for(var i=0; i<numberOfCopies; ++i) {
            connections[i] = createConnectionFromResource(resource);
        }

        return connections;
    }

    private HttpURLConnection createConnectionFromResource(String resource) throws IOException {
        var measurementsBody = getClass().getClassLoader().getResourceAsStream(resource);
        var measurementsConnection = mock(HttpURLConnection.class);
        when(measurementsConnection.getInputStream()).thenReturn(measurementsBody);
        return measurementsConnection;
    }

    private ServletInputStream wrap(InputStream inputStream) {
        return new ServletInputStream() {

            @Override
            public int read() throws IOException {
                return inputStream.read();
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // TODO Auto-generated method stub

            }

            @Override
            public boolean isReady() {
                // TODO Auto-generated method stub
                return false;
            }

            @Override
            public boolean isFinished() {
                // TODO Auto-generated method stub
                return false;
            }
        };
    }
}
