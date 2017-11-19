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

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;
import java.util.TimeZone;

import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.ops4j.pax.jdbc.derby.impl.DerbyDataSourceFactory;
import org.osgi.service.jdbc.DataSourceFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import no.priv.bang.sonar.collector.webhook.SonarBuild;
import no.priv.bang.sonar.collector.webhook.SonarCollectorServlet;
import no.priv.bang.sonar.collector.webhook.URLConnectionFactory;
import no.priv.bang.sonar.collector.webhook.mocks.MockLogService;

public class SonarCollectorServletTest {
    private static DataSourceFactory dataSourceFactory;

    @BeforeClass
    public static void beforeClass() throws IOException {
        addTestPropertiesToSystemProperties();
        dataSourceFactory = new DerbyDataSourceFactory();
    }

    private static void addTestPropertiesToSystemProperties() throws IOException {
        Properties testProperties = new Properties();
        testProperties.load(SonarCollectorServletTest.class.getClassLoader().getResourceAsStream("application-test.properties"));
        Properties systemProperties = System.getProperties();
        systemProperties.putAll(testProperties);
        System.setProperties(systemProperties);
    }

    @Test
    public void testReceiveSonarWebhookCall() throws ServletException, IOException, SQLException {
        URLConnectionFactory factory = mock(URLConnectionFactory.class);
        InputStream measurementsBody = getClass().getClassLoader().getResourceAsStream("json/sonar/api-measures-component-get-many-metrics.json");
        HttpURLConnection measurementsConnection = mock(HttpURLConnection.class);
        when(measurementsConnection.getInputStream()).thenReturn(measurementsBody);
        when(factory.openConnection(any())).thenReturn(measurementsConnection);
        HttpServletRequest request = mock(HttpServletRequest.class);
        ServletInputStream value = wrap(getClass().getClassLoader().getResourceAsStream("json/sonar/webhook-post.json"));
        when(request.getInputStream()).thenReturn(value);
        HttpServletResponse response = mock(HttpServletResponse.class);
        MockLogService logservice = new MockLogService();

        SonarCollectorServlet servlet = new SonarCollectorServlet(factory);
        servlet.setDataSourceFactory(dataSourceFactory);
        servlet.setLogservice(logservice);

        // Check preconditions
        assertEquals(0, countRowsOfTableMeasures(servlet.dataSource));

        // Run the code under test
        servlet.doPost(request, response);

        // Check that a measurement has been stored
        assertEquals(1, countRowsOfTableMeasures(servlet.dataSource));
    }

    private int countRowsOfTableMeasures(DataSource dataSource) throws SQLException {
        try(Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("select count(*) from measures")) {
                try (ResultSet resultset = statement.executeQuery()) {
                    while (resultset.next()) {
                        return resultset.getInt(1);
                    }
                }
            }
        }

        return 0;
    }

    @Test
    public void testUseNoArgumentConstructorAndReceiveSonarWebhookCall() throws ServletException, IOException {
        MockLogService logservice = new MockLogService();
        SonarCollectorServlet servlet = new SonarCollectorServlet();
        servlet.setLogservice(logservice);
        HttpServletRequest request = mock(HttpServletRequest.class);
        ServletInputStream value = wrap(getClass().getClassLoader().getResourceAsStream("json/sonar/webhook-post.json"));
        when(request.getInputStream()).thenReturn(value);
        HttpServletResponse response = mock(HttpServletResponse.class);

        servlet.doPost(request, response);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(response).setStatus(status.capture());
        assertEquals("Expected HTTP internal server error code", 500, status.getValue().intValue());
    }

    @Test
    public void testDoCallbackToSonarServerToGetMetrics() throws ServletException, IOException {
        MockLogService logservice = new MockLogService();
        URLConnectionFactory factory = mock(URLConnectionFactory.class);
        HttpURLConnection[] connections = createConnectionFromResource("json/sonar/api-measures-component-get-many-metrics.json", 3);
        when(factory.openConnection(any()))
            .thenReturn(connections[0])
            .thenReturn(connections[1])
            .thenReturn(connections[2]);
        SonarCollectorServlet servlet = new SonarCollectorServlet(factory);
        servlet.setLogservice(logservice);
        ServletRequest request = mock(ServletRequest.class);
        ServletInputStream webhookPostBody = wrap(getClass().getClassLoader().getResourceAsStream("json/sonar/webhook-post.json"));
        ServletInputStream webhookPostBodyWithMavenSnapshotVersion = wrap(getClass().getClassLoader().getResourceAsStream("json/sonar/webhook-post-with-snapshot-maven-version.json"));
        ServletInputStream webhookPostBodyWithMavenVersion = wrap(getClass().getClassLoader().getResourceAsStream("json/sonar/webhook-post-with-maven-version.json"));
        when(request.getInputStream())
            .thenReturn(webhookPostBody)
            .thenReturn(webhookPostBodyWithMavenSnapshotVersion)
            .thenReturn(webhookPostBodyWithMavenVersion);

        long expectedTimeInMillisecondsSinceEpoch = ZonedDateTime.parse("2016-11-18T10:46:28+0100", SonarCollectorServlet.isoZonedDateTimeformatter).toEpochSecond() * 1000;
        SonarBuild build = servlet.doCallbackToSonarServerToGetMetrics(request);
        assertEquals("org.sonarqube:example", build.project);
        assertEquals(expectedTimeInMillisecondsSinceEpoch, build.analysedAt);
        assertEquals("", build.version);
        assertEquals("http://localhost:9000", build.serverUrl.toString());

        SonarBuild buildWithMavenSnapshotVersion = servlet.doCallbackToSonarServerToGetMetrics(request);
        assertEquals("org.sonarqube:example", buildWithMavenSnapshotVersion.project);
        assertEquals(expectedTimeInMillisecondsSinceEpoch, buildWithMavenSnapshotVersion.analysedAt);
        assertEquals("1.0.0-SNAPSHOT", buildWithMavenSnapshotVersion.version);
        assertEquals("http://localhost:9000", buildWithMavenSnapshotVersion.serverUrl.toString());

        SonarBuild buildWithMavenVersion = servlet.doCallbackToSonarServerToGetMetrics(request);
        assertEquals("org.sonarqube:example", buildWithMavenVersion.project);
        assertEquals(expectedTimeInMillisecondsSinceEpoch, buildWithMavenVersion.analysedAt);
        assertEquals("1.0.0", buildWithMavenVersion.version);
        assertEquals("http://localhost:9000", buildWithMavenVersion.serverUrl.toString());
    }

    @Test
    public void testCreateGetMetricsUrl() throws ServletException, IOException {
        URLConnectionFactory factory = mock(URLConnectionFactory.class);
        SonarCollectorServlet servlet = new SonarCollectorServlet(factory);
        String[] metricKeys = servlet.getMetricKeys();
        assertEquals(9, metricKeys.length);
        SonarBuild build = new SonarBuild();
        build.project = "no.priv.bang.ukelonn:parent";
        build.serverUrl = new URL("http://localhost:9000");
        URL metricsUrl = servlet.createSonarMeasurementsComponentUrl(build, metricKeys);
        assertEquals(build.serverUrl.getProtocol(), metricsUrl.getProtocol());
        assertEquals(build.serverUrl.getHost(), metricsUrl.getHost());
        assertEquals(build.serverUrl.getPort(), metricsUrl.getPort());
        assertEquals("/api/measures/component", metricsUrl.getPath());
        String query = URLDecoder.decode(metricsUrl.getQuery(), "UTF-8");
        assertThat(query, containsString(build.project));
    }

    @Test
    public void testParseMeasures() throws JsonProcessingException, IOException {
        JsonNode root = SonarCollectorServlet.mapper.readTree(getClass().getClassLoader().getResourceAsStream("json/sonar/api-measures-component-get-many-metrics.json"));
        JsonNode measuresNode = root.path("component").path("measures");
        SonarCollectorServlet servlet = new SonarCollectorServlet();

        String[] metricKeys = servlet.getMetricKeys();
        HashMap<String, String> measures = new HashMap<>();
        servlet.parseMeasures(measures, measuresNode);
        assertEquals(metricKeys.length, measures.size());
        assertEquals("0", measures.get("new_bugs"));
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
    public void testParseMeasuresEmptyDocument() throws JsonProcessingException, IOException {
        JsonNode root = SonarCollectorServlet.mapper.readTree("{}");
        JsonNode measuresNode = root.path("component").path("measures");
        SonarCollectorServlet servlet = new SonarCollectorServlet();

        HashMap<String, String> measures = new HashMap<>();
        servlet.parseMeasures(measures, measuresNode);
        assertEquals("Parse results weren't empty", 0, measures.size());
    }

    @Test
    public void testGetJdbcUrl() throws IOException {
        SonarCollectorServlet servlet = new SonarCollectorServlet();
        String jdbcurl = servlet.getJdbcUrl();
        assertEquals("jdbc:derby:memory:ukelonn;create=true", jdbcurl);
    }

    @Test
    public void testVersionIsReleaseVersion() throws IOException {
        SonarCollectorServlet servlet = new SonarCollectorServlet();
        assertFalse(servlet.versionIsReleaseVersion(""));
        assertFalse(servlet.versionIsReleaseVersion("1.0.0-SNAPSHOT"));
        assertTrue(servlet.versionIsReleaseVersion("1.0.0"));
    }

    @Test
    public void testParseTimestamp() throws IOException {
        SonarCollectorServlet servlet = new SonarCollectorServlet();
        long timestamp = servlet.parseTimestamp("2017-11-19T10:39:24+0100");
        Date date = new Date(timestamp);
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT+1"));
        calendar.setTime(date);
        System.out.println(String.format("Date: %s", date.toString()));
        assertEquals(2017, calendar.get(Calendar.YEAR));
        assertEquals(11, calendar.get(Calendar.MONTH) + 1); // January is 0
        assertEquals(19, calendar.get(Calendar.DAY_OF_MONTH));
        assertEquals(10, calendar.get(Calendar.HOUR_OF_DAY));
        assertEquals(39, calendar.get(Calendar.MINUTE));
    }

    private HttpURLConnection[] createConnectionFromResource(String resource, int numberOfCopies) throws IOException {
        HttpURLConnection[] connections = new HttpURLConnection[numberOfCopies];
        for(int i=0; i<numberOfCopies; ++i) {
            connections[i] = createConnectionFromResource(resource);
        }

        return connections;
    }

    private HttpURLConnection createConnectionFromResource(String resource) throws IOException {
        InputStream measurementsBody = getClass().getClassLoader().getResourceAsStream(resource);
        HttpURLConnection measurementsConnection = mock(HttpURLConnection.class);
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
