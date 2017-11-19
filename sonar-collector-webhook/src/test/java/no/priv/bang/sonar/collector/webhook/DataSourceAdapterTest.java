package no.priv.bang.sonar.collector.webhook;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.PrintWriter;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;

public class DataSourceAdapterTest {

    @Before
    public void before() throws SQLException {
        NullDataSource.getInstance().setLoginTimeout(0);
        NullDataSource.getInstance().setLogWriter(null);
    }

    @Test
    public void testThatAdapterInitiallyWrapsNullDataSource() throws SQLException {
        DataSourceAdapter adapter = new DataSourceAdapter();
        assertNull(adapter.getConnection());
        assertNull(adapter.getConnection("userdoesntmatter", "passwordoesntmatter"));
        assertNull(adapter.getParentLogger());

        // Verify that setting the log is transferred to the NullDataSource
        assertNull(NullDataSource.getInstance().getLogWriter());
        PrintWriter log = mock(PrintWriter.class);
        adapter.setLogWriter(log);
        assertEquals(log, NullDataSource.getInstance().getLogWriter());
        assertEquals(adapter.getLogWriter(), NullDataSource.getInstance().getLogWriter());

        // Verify that setting the timeout is transferred to the NullDataSource
        assertEquals(0, NullDataSource.getInstance().getLoginTimeout());
        int timeout = 3316;
        adapter.setLoginTimeout(timeout);
        assertEquals(timeout, NullDataSource.getInstance().getLoginTimeout());
        assertEquals(adapter.getLoginTimeout(), NullDataSource.getInstance().getLoginTimeout());

        // Verify that it doesn't matter what class is sent to the adaptive method
        assertFalse(adapter.isWrapperFor(getClass()));
        assertFalse(adapter.isWrapperFor(null));
        assertNull(adapter.unwrap(getClass()));
        assertNull(adapter.unwrap(null));
    }

    @Test
    public void testSetDatasource() throws SQLException {
        DataSourceAdapter adapter = new DataSourceAdapter();

        // Set a new datasource into the adapter
        DataSource datasource = mock(DataSource.class);
        adapter.setDatasource(datasource);

        // Verify that setting the timeout now isn't transferred to the NullDataSource
        assertEquals(0, NullDataSource.getInstance().getLoginTimeout());
        int timeout = 3316;
        adapter.setLoginTimeout(timeout);
        assertNotEquals(timeout, NullDataSource.getInstance().getLoginTimeout());

        // Verify that setting the datasource to null will cause the adapter to use the NullDataSource
        adapter.setDatasource(null);
        assertEquals(0, NullDataSource.getInstance().getLoginTimeout());
        int timeout2 = 7754;
        adapter.setLoginTimeout(timeout2);
        assertEquals(timeout2, NullDataSource.getInstance().getLoginTimeout());
    }

}
