package no.priv.bang.sonar.collector.webhook;

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;

import org.junit.Test;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.LogService;

import no.priv.bang.sonar.collector.webhook.mocks.MockLogService;

@SuppressWarnings("rawtypes")
public class LogServiceAdapterTest {

    @Test
    public void testLogErrorWithoutException() {
        MockLogService logservice = new MockLogService();
        LogServiceAdapter adapter = new LogServiceAdapter();

        // Log to an adapter with null logservice
        adapter.log(LogService.LOG_ERROR, "This is an error without exception");

        // Verify that nothing has been logged yet in the log service
        assertEquals(0, logservice.getLogmessages().size());

        // Inject the service into the adapter
        adapter.setService(logservice);

        // Verify that the saved log message in the adapter have been logged into the injected log service
        assertEquals(1, logservice.getLogmessages().size());
        assertThat(logservice.getLogmessages().get(0), containsString("[ERROR]"));
        assertThat(logservice.getLogmessages().get(0), containsString("This is an error without exception"));

        // Log a new message
        adapter.log(LogService.LOG_DEBUG, "This is a debug message without exception");

        // Verify that the log message appeared immediately in the underlying log
        assertEquals(2, logservice.getLogmessages().size());
        assertThat(logservice.getLogmessages().get(1), containsString("[DEBUG]"));
        assertThat(logservice.getLogmessages().get(1), containsString("This is a debug message without exception"));
    }

    /***
     * Corner case test for {@link LogServiceAdapter#setService(LogService)}, verify that
     * saved log message is preserved when a null log service is injected before a non-null
     * log service is injected.
     */
    @Test
    public void verifyThatSavedLogMessageSurvicesInjectingNullLogService() {
        MockLogService logservice = new MockLogService();
        LogServiceAdapter adapter = new LogServiceAdapter();

        // Log to an adapter with null logservice
        adapter.log(LogService.LOG_ERROR, "This is an error without exception");

        // Verify that nothing has been logged yet in the log service
        assertEquals(0, logservice.getLogmessages().size());

        // Inject a null log service into the adaptter
        adapter.setService(null);

        // Inject a non-null service into the adapter
        adapter.setService(logservice);

        // Verify that the saved log message in the adapter have been logged into the injected log service
        // (and that it hasn't been nulled by the null log service)
        assertEquals(1, logservice.getLogmessages().size());
        assertThat(logservice.getLogmessages().get(0), containsString("[ERROR]"));
        assertThat(logservice.getLogmessages().get(0), containsString("This is an error without exception"));
    }

    @Test
    public void testLogErrorWithException() {
        MockLogService logservice = new MockLogService();
        LogServiceAdapter adapter = new LogServiceAdapter();

        // Log to an adapter with null logservice
        adapter.log(LogService.LOG_ERROR, "This is an error with exception", new IOException());

        // Verify that nothing has been logged yet in the log service
        assertEquals(0, logservice.getLogmessages().size());

        // Inject the service into the adapter
        adapter.setService(logservice);

        // Verify that the saved log message in the adapter have been logged into the injected log service
        assertEquals(1, logservice.getLogmessages().size());
        assertThat(logservice.getLogmessages().get(0), containsString("[ERROR]"));
        assertThat(logservice.getLogmessages().get(0), containsString("This is an error with exception"));
        assertThat(logservice.getLogmessages().get(0), containsString("IOException"));

        // Log a new message
        adapter.log(LogService.LOG_DEBUG, "This is a debug message without exception", new RuntimeException());

        // Verify that the log message appeared immediately in the underlying log
        assertEquals(2, logservice.getLogmessages().size());
        assertThat(logservice.getLogmessages().get(1), containsString("[DEBUG]"));
        assertThat(logservice.getLogmessages().get(1), containsString("This is a debug message without exception"));
        assertThat(logservice.getLogmessages().get(1), containsString("RuntimeException"));
    }

    @Test
    public void testLogErrorWithoutExceptionAndWithServiceReference() {
        MockLogService logservice = new MockLogService();
        LogServiceAdapter adapter = new LogServiceAdapter();
        ServiceReference reference = mock(ServiceReference.class);
        when(reference.toString()).thenReturn("serviceReference");

        // Log to an adapter with null logservice
        adapter.log(reference, LogService.LOG_ERROR, "This is an error without exception");

        // Verify that nothing has been logged yet in the log service
        assertEquals(0, logservice.getLogmessages().size());

        // Inject the service into the adapter
        adapter.setService(logservice);

        // Verify that the saved log message in the adapter have been logged into the injected log service
        assertEquals(1, logservice.getLogmessages().size());
        assertThat(logservice.getLogmessages().get(0), containsString("[ERROR]"));
        assertThat(logservice.getLogmessages().get(0), containsString("serviceReference"));
        assertThat(logservice.getLogmessages().get(0), containsString("This is an error without exception"));

        // Log a new message
        adapter.log(reference, LogService.LOG_DEBUG, "This is a debug message without exception");

        // Verify that the log message appeared immediately in the underlying log
        assertEquals(2, logservice.getLogmessages().size());
        assertThat(logservice.getLogmessages().get(1), containsString("[DEBUG]"));
        assertThat(logservice.getLogmessages().get(1), containsString("serviceReference"));
        assertThat(logservice.getLogmessages().get(1), containsString("This is a debug message without exception"));
    }

    @Test
    public void testLogErrorWithExceptionAndWithServiceReference() {
        MockLogService logservice = new MockLogService();
        LogServiceAdapter adapter = new LogServiceAdapter();
        ServiceReference reference = mock(ServiceReference.class);
        when(reference.toString()).thenReturn("serviceReference");

        // Log to an adapter with null logservice
        adapter.log(reference, LogService.LOG_ERROR, "This is an error with exception", new IOException());

        // Verify that nothing has been logged yet in the log service
        assertEquals(0, logservice.getLogmessages().size());

        // Inject the service into the adapter
        adapter.setService(logservice);

        // Verify that the saved log message in the adapter have been logged into the injected log service
        assertEquals(1, logservice.getLogmessages().size());
        assertThat(logservice.getLogmessages().get(0), containsString("[ERROR]"));
        assertThat(logservice.getLogmessages().get(0), containsString("This is an error with exception"));
        assertThat(logservice.getLogmessages().get(0), containsString("serviceReference"));
        assertThat(logservice.getLogmessages().get(0), containsString("IOException"));

        // Log a new message
        adapter.log(reference, LogService.LOG_DEBUG, "This is a debug message without exception", new RuntimeException());

        // Verify that the log message appeared immediately in the underlying log
        assertEquals(2, logservice.getLogmessages().size());
        assertThat(logservice.getLogmessages().get(1), containsString("[DEBUG]"));
        assertThat(logservice.getLogmessages().get(1), containsString("This is a debug message without exception"));
        assertThat(logservice.getLogmessages().get(1), containsString("serviceReference"));
        assertThat(logservice.getLogmessages().get(1), containsString("RuntimeException"));
    }

}
