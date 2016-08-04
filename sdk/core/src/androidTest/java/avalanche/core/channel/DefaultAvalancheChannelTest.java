package avalanche.core.channel;

import android.content.Context;
import android.content.pm.PackageManager;
import android.support.test.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import avalanche.core.AndroidTestUtils;
import avalanche.core.ingestion.AvalancheIngestion;
import avalanche.core.ingestion.ServiceCallback;
import avalanche.core.ingestion.http.AvalancheIngestionHttp;
import avalanche.core.ingestion.http.HttpException;
import avalanche.core.ingestion.models.Device;
import avalanche.core.ingestion.models.Log;
import avalanche.core.ingestion.models.LogContainer;
import avalanche.core.ingestion.models.json.DefaultLogSerializer;
import avalanche.core.ingestion.models.json.LogSerializer;
import avalanche.core.ingestion.models.json.MockLogFactory;
import avalanche.core.persistence.AvalancheDatabasePersistence;
import avalanche.core.persistence.AvalanchePersistence;
import avalanche.core.utils.AvalancheLog;
import avalanche.core.utils.StorageHelper;
import avalanche.core.utils.UUIDUtils;

import static avalanche.core.ingestion.models.json.MockLog.MOCK_LOG_TYPE;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unused")
public class DefaultAvalancheChannelTest {

    private final static String TEST_GROUP = "group_test";

    private final static int BATCH_TIME_INTERVAL = 3000;
    private final static int MAX_PARALLEL_BATCHES = 3;

    private static Log sMockLog;
    private static LogSerializer sLogSerializer;
    private static Context sContext;

    @BeforeClass
    public static void setUpBeforeClass() {
        AvalancheLog.setLogLevel(android.util.Log.VERBOSE);

        sContext = InstrumentationRegistry.getTargetContext();
        StorageHelper.initialize(sContext);

        sMockLog = AndroidTestUtils.generateMockLog();
        sLogSerializer = new DefaultLogSerializer();
        sLogSerializer.addLogFactory(MOCK_LOG_TYPE, new MockLogFactory());
    }

    private static Answer<String> getGetLogsAnswer() {
        return getGetLogsAnswer(0);
    }

    private static Answer<String> getGetLogsAnswer(final int size) {
        return new Answer<String>() {
            @Override
            @SuppressWarnings("unchecked")
            public String answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                String uuidString = null;
                if (args[0] instanceof String) {
                    if ((args[0]).equals(TEST_GROUP)) {
                        if (args[2] instanceof ArrayList) {
                            ArrayList logs = (ArrayList) args[2];
                            int length = size > 0 ? size : (int) args[1];
                            for (int i = 0; i < length; i++) {
                                logs.add(sMockLog);
                            }
                        }
                        uuidString = UUIDUtils.randomUUID().toString();
                    }

                }
                return uuidString;
            }
        };
    }

    private static Answer<String> getEmptyGetLogsAnswer() {
        return new Answer<String>() {
            @Override
            public String answer(InvocationOnMock invocationOnMock) throws Throwable {
                return null;
            }
        };
    }

    private static Answer<Object> getSendAsyncAnswer() {
        return getSendAsyncAnswer(null);
    }

    private static Answer<Object> getSendAsyncAnswer(final Exception e) {
        return new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                if (args[3] instanceof ServiceCallback) {
                    if (e == null)
                        ((ServiceCallback) invocation.getArguments()[3]).onCallSucceeded();
                    else
                        ((ServiceCallback) invocation.getArguments()[3]).onCallFailed(e);
                }
                return null;
            }
        };
    }

    @Test
    public void creationWorks() {
        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, UUIDUtils.randomUUID(), sLogSerializer);
        assertNotNull(channel);
    }

    @Test
    public void invalidGroup() throws AvalanchePersistence.PersistenceException {
        Log log = mock(Log.class);
        AvalanchePersistence persistence = mock(AvalanchePersistence.class);
        @SuppressWarnings("ConstantConditions")
        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, null, mock(AvalancheIngestionHttp.class), persistence, sLogSerializer);

        /* Enqueue a log before group is registered = failure. */
        channel.enqueue(log, "invalid");
        verify(log, never()).setDevice(any(Device.class));
        verify(log, never()).setToffset(anyLong());
        verify(persistence, never()).putLog("invalid", log);
    }

    @Test
    public void persistAnalytics() throws AvalanchePersistence.PersistenceException {
        AvalanchePersistence mockPersistence = mock(AvalanchePersistence.class);
        AvalancheIngestionHttp mockIngestion = mock(AvalancheIngestionHttp.class);

        //don't provide a UUID to prevent sending
        @SuppressWarnings("ConstantConditions")
        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, null, mockIngestion, mockPersistence, sLogSerializer);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null);

        //Enqueuing 49 events.
        for (int i = 0; i < 49; i++) {
            channel.enqueue(sMockLog, TEST_GROUP);
            assertTrue(System.currentTimeMillis() - sMockLog.getToffset() <= 100);
        }

        //Check if our counter is equal the number of events.
        assertEquals(49, channel.getCounter(TEST_GROUP));

        //Enqueue another event.
        channel.enqueue(sMockLog, TEST_GROUP);

        //The counter should be 0 as we reset the counter after reaching the limit of 50.
        assertEquals(0, channel.getCounter(TEST_GROUP));

        //Verifying that 50 items have been persisted.
        verify(mockPersistence, times(50)).putLog(TEST_GROUP, sMockLog);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void analyticsSuccess() throws AvalanchePersistence.PersistenceException {
        AvalanchePersistence mockPersistence = mock(AvalanchePersistence.class);
        AvalancheIngestionHttp mockIngestion = mock(AvalancheIngestionHttp.class);

        when(mockPersistence.getLogs(any(String.class), anyInt(), any(ArrayList.class))).then(getGetLogsAnswer()).then(getEmptyGetLogsAnswer());

        when(mockIngestion.sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class))).then(getSendAsyncAnswer());

        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, UUIDUtils.randomUUID(), sLogSerializer);
        AvalancheChannel.GroupListener mockListener = mock(AvalancheChannel.GroupListener.class);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, mockListener);

        channel.setPersistence(mockPersistence);
        channel.setIngestion(mockIngestion);

        //Enqueuing 50 events.
        for (int i = 0; i < 50; i++) {
            channel.enqueue(sMockLog, TEST_GROUP);
        }

        //Verifying that 5 items have been persisted.
        verify(mockPersistence, times(50)).putLog(TEST_GROUP, sMockLog);

        //Verify that we have called sendAsync on the ingestion
        verify(mockIngestion).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        //Verify that we have called deleteLogs on the persistence
        verify(mockPersistence).deleteLogs(any(String.class), any(String.class));

        //Verify that we have called onSuccess in the listener
        verify(mockListener, times(50)).onSuccess(any(Log.class));

        //The counter should be 0 now as we sent data.
        assertEquals(0, channel.getCounter(TEST_GROUP));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void maxRequests() throws AvalanchePersistence.PersistenceException {
        AvalanchePersistence mockPersistence = mock(AvalanchePersistence.class);
        AvalancheIngestionHttp mockIngestion = mock(AvalancheIngestionHttp.class);

        when(mockPersistence.getLogs(any(String.class), anyInt(), any(ArrayList.class))).then(getGetLogsAnswer());

        final List<ServiceCallback> callbacks = new ArrayList<>();
        when(mockIngestion.sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class))).then(new Answer<Object>() {
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                if (args[3] instanceof ServiceCallback) {
                    callbacks.add((ServiceCallback) invocation.getArguments()[3]);
                }
                return null;
            }
        });

        /* Init channel with mocks. */
        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, UUIDUtils.randomUUID(), sLogSerializer);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null);

        channel.setPersistence(mockPersistence);
        channel.setIngestion(mockIngestion);

        /* Enqueue enough logs to be split in N + 1 maximum requests. */
        for (int i = 0; i < 200; i++) {
            channel.enqueue(sMockLog, TEST_GROUP);
        }

        /* Verify all logs stored, N requests sent, not log deleted yet. */
        verify(mockPersistence, times(200)).putLog(TEST_GROUP, sMockLog);
        verify(mockIngestion, times(3)).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));
        verify(mockPersistence, never()).deleteLogs(any(String.class), any(String.class));

        /* Make 1 of the call succeed. Verify log deleted. */
        callbacks.get(0).onCallSucceeded();
        verify(mockPersistence).deleteLogs(any(String.class), any(String.class));

        /* The request N+1 is now unlocked. */
        verify(mockIngestion, times(4)).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        /* Unlock all requests and check logs deleted. */
        for (int i = 1; i < 4; i++)
            callbacks.get(i).onCallSucceeded();
        verify(mockPersistence, times(4)).deleteLogs(any(String.class), any(String.class));

        /* The counter should be 0 now as we sent data. */
        assertEquals(0, channel.getCounter(TEST_GROUP));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void analyticsRecoverable() throws AvalanchePersistence.PersistenceException {
        AvalanchePersistence mockPersistence = mock(AvalanchePersistence.class);
        AvalancheIngestionHttp mockIngestion = mock(AvalancheIngestionHttp.class);

        when(mockPersistence.getLogs(any(String.class), anyInt(), any(ArrayList.class))).then(getGetLogsAnswer());
        when(mockIngestion.sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class))).then(getSendAsyncAnswer(new SocketException()));

        //don't provide a UUID to prevent sending
        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, UUIDUtils.randomUUID(), mockIngestion, mockPersistence, sLogSerializer);
        AvalancheChannel.GroupListener mockListener = mock(AvalancheChannel.GroupListener.class);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, mockListener);

        //Enqueuing 50 events.
        for (int i = 0; i < 50; i++) {
            channel.enqueue(sMockLog, TEST_GROUP);
        }

        //Verifying that 50 items have been persisted.
        verify(mockPersistence, times(50)).putLog(TEST_GROUP, sMockLog);

        //Verify that we have called sendAsync on the ingestion
        verify(mockIngestion).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        //Verify that we have not called deleteLogs on the persistence
        verify(mockPersistence, never()).deleteLogs(any(String.class), any(String.class));

        //Verify that the Channel is disabled
        assertFalse(channel.isEnabled());
        verify(mockPersistence).clearPendingLogState();
        verify(mockPersistence, never()).clear();

        //Enqueuing 20 more events.
        for (int i = 0; i < 20; i++) {
            channel.enqueue(sMockLog, TEST_GROUP);
        }

        //The counter should have been 0 now as we are disabled and the counter is not increased.
        assertEquals(0, channel.getCounter(TEST_GROUP));

        //Using a fresh ingestion object to change our stub to use the analyticsSuccess()-callback
        AvalancheIngestionHttp newIngestion = mock(AvalancheIngestionHttp.class);
        when(newIngestion.sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class))).then(getSendAsyncAnswer());

        channel.setIngestion(newIngestion);

        //Use a fresh persistence, that will return 50 objects, then another 25 objects.
        AvalancheDatabasePersistence newPersistence = mock(AvalancheDatabasePersistence.class);
        when(newPersistence.getLogs(any(String.class), anyInt(), any(ArrayList.class)))
                .then(getGetLogsAnswer())
                .then(getGetLogsAnswer())
                .then(getGetLogsAnswer(25))
                .then(getEmptyGetLogsAnswer());

        channel.setPersistence(newPersistence);

        channel.setEnabled(true);
        channel.triggerIngestion();

        //The counter should back to 0 now.
        assertEquals(0, channel.getCounter(TEST_GROUP));

        //Verify that we have called sendAsync on the ingestion 5 times total.
        verify(newIngestion, times(3)).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        //Verify that we have called deleteLogs on the persistence
        verify(newPersistence, times(3)).deleteLogs(any(String.class), any(String.class));

        //Verify that we have called onSuccess in the listener
        verify(mockListener, times(50)).onFailure(any(Log.class), any(Exception.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void analyticsFatal() throws AvalanchePersistence.PersistenceException {
        AvalanchePersistence mockPersistence = mock(AvalanchePersistence.class);
        AvalancheIngestionHttp mockIngestion = mock(AvalancheIngestionHttp.class);

        when(mockPersistence.getLogs(any(String.class), anyInt(), any(ArrayList.class))).then(getGetLogsAnswer());
        when(mockIngestion.sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class))).then(getSendAsyncAnswer(new HttpException(403)));

        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, UUIDUtils.randomUUID(), mockIngestion, mockPersistence, sLogSerializer);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null);

        //Enqueuing 50 events.
        for (int i = 0; i < 50; i++) {
            channel.enqueue(sMockLog, TEST_GROUP);
        }

        //Verifying that 50 items have been persisted.
        verify(mockPersistence, times(50)).putLog(TEST_GROUP, sMockLog);

        //Verify that we have called sendAsync on the ingestion
        verify(mockIngestion).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        //Verify that we have deleted the failed batch
        verify(mockPersistence).deleteLogs(any(String.class), any(String.class));

        //Verify that the Channel is disabled
        assertFalse(channel.isEnabled());
        verify(mockPersistence).clearPendingLogState();
        verify(mockPersistence, never()).clear();

        //Enqueuing 20 more events.
        for (int i = 0; i < 20; i++) {
            channel.enqueue(sMockLog, TEST_GROUP);
        }

        //The counter should have been 0 now as we are disabled and the counter is not increased.
        assertEquals(0, channel.getCounter(TEST_GROUP));

        //Using a fresh ingestion object to change our stub to use the analyticsSuccess()-callback
        AvalancheIngestionHttp newIngestion = mock(AvalancheIngestionHttp.class);
        when(newIngestion.sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class))).then(getSendAsyncAnswer());

        channel.setIngestion(newIngestion);

        //Use a fresh persistence, that will return 50 objects, then another 25 objects.
        AvalancheDatabasePersistence newPersistence = mock(AvalancheDatabasePersistence.class);
        when(newPersistence.getLogs(any(String.class), anyInt(), any(ArrayList.class)))
                .then(getGetLogsAnswer())
                .then(getGetLogsAnswer())
                .then(getGetLogsAnswer(25))
                .then(getEmptyGetLogsAnswer());

        channel.setPersistence(newPersistence);

        channel.setEnabled(true);
        channel.triggerIngestion();

        //The counter should back to 0 now.
        assertEquals(0, channel.getCounter(TEST_GROUP));

        //Verify that we have called sendAsync on the ingestion 5 times total.
        verify(newIngestion, times(3)).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        //Verify that we have called deleteLogs on the persistence
        verify(newPersistence, times(3)).deleteLogs(any(String.class), any(String.class));
    }

    @Test
    public void persistErrorLog() throws AvalanchePersistence.PersistenceException {
        AvalanchePersistence mockPersistence = mock(AvalanchePersistence.class);
        AvalancheIngestionHttp mockIngestion = mock(AvalancheIngestionHttp.class);

        //don't provide a UUID to prevent sending
        @SuppressWarnings("ConstantConditions")
        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, null, mockIngestion, mockPersistence, sLogSerializer);
        channel.addGroup(TEST_GROUP, 1, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null);

        //Enqueuing 4 events.
        for (int i = 0; i < 4; i++) {
            channel.enqueue(sMockLog, TEST_GROUP);
            assertTrue(System.currentTimeMillis() - sMockLog.getToffset() <= 100);
        }

        //The counter should have been 0 now as we have reached the limit
        assertEquals(0, channel.getCounter(TEST_GROUP));

        //Enqueue another event.
        channel.enqueue(sMockLog, TEST_GROUP);

        //Verifying that 5 items have been persisted.
        verify(mockPersistence, times(5)).putLog(TEST_GROUP, sMockLog);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void errorLogSuccess() throws AvalanchePersistence.PersistenceException {
        AvalanchePersistence mockPersistence = mock(AvalanchePersistence.class);
        AvalancheIngestion mockIngestion = mock(AvalancheIngestion.class);

        when(mockPersistence.getLogs(any(String.class), anyInt(), any(ArrayList.class))).then(getGetLogsAnswer()).then(getEmptyGetLogsAnswer());
        when(mockIngestion.sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class))).then(getSendAsyncAnswer());

        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, UUIDUtils.randomUUID(), sLogSerializer);
        AvalancheChannel.GroupListener mockListener = mock(AvalancheChannel.GroupListener.class);
        channel.addGroup(TEST_GROUP, 1, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, mockListener);

        channel.setIngestion(mockIngestion);
        channel.setPersistence(mockPersistence);

        //Enqueuing 1 error log.
        channel.enqueue(sMockLog, TEST_GROUP);

        //Verifying that 1 item have been persisted.
        verify(mockPersistence).putLog(TEST_GROUP, sMockLog);

        //Verify that we have called sendAsync on the ingestion
        verify(mockIngestion).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        //Verify that we have called deleteLogs on the persistence
        verify(mockPersistence).deleteLogs(any(String.class), any(String.class));

        //Verify that we have called onSuccess in the listener
        verify(mockListener).onSuccess(any(Log.class));

        //The counter should be 0 now as we sent data.
        assertEquals(0, channel.getCounter(TEST_GROUP));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void errorLogRecoverable() throws AvalanchePersistence.PersistenceException {
        AvalanchePersistence mockPersistence = mock(AvalanchePersistence.class);
        AvalancheIngestion mockIngestion = mock(AvalancheIngestion.class);

        when(mockPersistence.getLogs(any(String.class), anyInt(), any(ArrayList.class))).then(getGetLogsAnswer());
        when(mockIngestion.sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class))).then(getSendAsyncAnswer(new SocketException()));

        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, UUIDUtils.randomUUID(), mockIngestion, mockPersistence, sLogSerializer);
        AvalancheChannel.GroupListener mockListener = mock(AvalancheChannel.GroupListener.class);
        channel.addGroup(TEST_GROUP, 1, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, mockListener);

        //Enqueuing 1 error.
        channel.enqueue(sMockLog, TEST_GROUP);

        //Verifying that 1 items have been persisted.
        verify(mockPersistence).putLog(TEST_GROUP, sMockLog);

        //Verify that we have called sendAsync on the ingestion once for the first item, but not more than that.
        verify(mockIngestion).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        //Verify that we have not called deleteLogs on the persistence
        verify(mockPersistence, never()).deleteLogs(any(String.class), any(String.class));

        //Verify that we have called onSuccess in the listener
        verify(mockListener).onFailure(any(Log.class), any(Exception.class));

        //Verify that the Channel is disabled
        assertFalse(channel.isEnabled());
        verify(mockPersistence).clearPendingLogState();
        verify(mockPersistence, never()).clear();

        //Using a fresh ingestion object to change our stub to use the analyticsSuccess()-callback
        AvalancheIngestion newIngestion = mock(AvalancheIngestion.class);
        when(newIngestion.sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class))).then(getSendAsyncAnswer());

        channel.setIngestion(newIngestion);

        AvalanchePersistence newPersistence = mock(AvalanchePersistence.class);
        when(newPersistence.getLogs(anyString(), anyInt(), any(ArrayList.class)))
                .then(getGetLogsAnswer())
                .then(getGetLogsAnswer())
                .then(getGetLogsAnswer())
                .then(getGetLogsAnswer())
                .then(getGetLogsAnswer())
                .then(getEmptyGetLogsAnswer());

        channel.setPersistence(newPersistence);

        channel.setEnabled(true);
        channel.triggerIngestion();

        //Verify that we have called sendAsync on the ingestion 5 times total
        verify(newIngestion, times(5)).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        //Verify that we have called deleteLogs on the persistence 5 times
        verify(newPersistence, times(5)).deleteLogs(any(String.class), any(String.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void handlerInvoked() throws AvalanchePersistence.PersistenceException, InterruptedException {
        AvalanchePersistence mockPersistence = mock(AvalanchePersistence.class);
        AvalancheIngestionHttp mockIngestion = mock(AvalancheIngestionHttp.class);

        when(mockPersistence.getLogs(any(String.class), anyInt(), any(ArrayList.class))).then(getGetLogsAnswer()).then(getEmptyGetLogsAnswer());
        when(mockIngestion.sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class))).then(getSendAsyncAnswer());

        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, UUIDUtils.randomUUID(), mockIngestion, mockPersistence, sLogSerializer);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null);

        //Enqueuing 20 events.
        for (int i = 0; i < 20; i++) {
            channel.enqueue(sMockLog, TEST_GROUP);
        }

        //Verifying that 5 items have been persisted.
        verify(mockPersistence, times(20)).putLog(TEST_GROUP, sMockLog);

        Thread.sleep(4000);

        //Verify that we have called sendAsync on the ingestion
        verify(mockIngestion).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));

        //Verify that we have called deleteLogs on the persistence
        verify(mockPersistence).deleteLogs(any(String.class), any(String.class));

        //The counter should be 0 now as we sent data.
        assertEquals(0, channel.getCounter(TEST_GROUP));
    }

    @Test
    public void enqueuePersistenceFailure() throws AvalanchePersistence.PersistenceException {
        AvalanchePersistence mockPersistence = mock(AvalanchePersistence.class);

        /* Simulate persistence failing. */
        doThrow(new AvalanchePersistence.PersistenceException("mock", new IOException("mock"))).
                when(mockPersistence).putLog(anyString(), any(Log.class));
        AvalancheIngestionHttp mockIngestion = mock(AvalancheIngestionHttp.class);
        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, UUIDUtils.randomUUID(), sLogSerializer);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null);
        channel.setPersistence(mockPersistence);
        channel.setIngestion(mockIngestion);

        /* Verify no request is sent if persistence fails. */
        for (int i = 0; i < 50; i++) {
            channel.enqueue(sMockLog, TEST_GROUP);
        }
        verify(mockPersistence, times(50)).putLog(TEST_GROUP, sMockLog);
        verify(mockIngestion, never()).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));
        assertEquals(0, channel.getCounter(TEST_GROUP));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void setEnabled() throws IOException, InterruptedException {
        AvalancheIngestion ingestion = mock(AvalancheIngestion.class);
        doThrow(new IOException()).when(ingestion).close();
        AvalanchePersistence persistence = mock(AvalanchePersistence.class);
        when(persistence.getLogs(anyString(), anyInt(), anyList())).thenAnswer(getGetLogsAnswer()).thenAnswer(getEmptyGetLogsAnswer());
        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, UUIDUtils.randomUUID(), ingestion, persistence, sLogSerializer);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null);

        channel.enqueue(sMockLog, TEST_GROUP);
        channel.setEnabled(false);
        verify(ingestion).close();
        verify(persistence).clear();
        Thread.sleep(4000);
        verify(ingestion, never()).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));
        channel.setEnabled(true);
        channel.enqueue(sMockLog, TEST_GROUP);
        Thread.sleep(4000);
        verify(ingestion).sendAsync(any(UUID.class), any(UUID.class), any(LogContainer.class), any(ServiceCallback.class));
    }

    @Test
    public void listener() throws AvalanchePersistence.PersistenceException {

        @SuppressWarnings("ConstantConditions")
        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(sContext, null, mock(AvalancheIngestionHttp.class), mock(AvalanchePersistence.class), sLogSerializer);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null);
        AvalancheChannel.Listener listener = mock(AvalancheChannel.Listener.class);
        channel.addListener(listener);
        channel.enqueue(sMockLog, TEST_GROUP);
        verify(listener).onEnqueuingLog(sMockLog, TEST_GROUP);

        /* Check no more calls after removing listener. */
        channel.removeListener(listener);
        channel.enqueue(sMockLog, TEST_GROUP);
        verify(listener).onEnqueuingLog(sMockLog, TEST_GROUP);
    }

    @Test
    public void packageManagerIsBroken() throws PackageManager.NameNotFoundException, AvalanchePersistence.PersistenceException {

        /* Setup mocking to make device properties generation fail. */
        Context context = mock(Context.class);
        PackageManager packageManager = mock(PackageManager.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getPackageManager()).thenReturn(packageManager);
        //noinspection WrongConstant
        when(packageManager.getPackageInfo(any(String.class), anyInt())).thenThrow(new PackageManager.NameNotFoundException());
        AvalanchePersistence persistence = mock(AvalanchePersistence.class);
        @SuppressWarnings("ConstantConditions")
        DefaultAvalancheChannel channel = new DefaultAvalancheChannel(context, null, mock(AvalancheIngestionHttp.class), persistence, sLogSerializer);
        channel.addGroup(TEST_GROUP, 50, BATCH_TIME_INTERVAL, MAX_PARALLEL_BATCHES, null);
        AvalancheChannel.Listener listener = mock(AvalancheChannel.Listener.class);
        channel.addListener(listener);

        /* Enqueue a log: nothing will happen as device property generation fails. */
        channel.enqueue(sMockLog, TEST_GROUP);
        verify(listener, never()).onEnqueuingLog(sMockLog, TEST_GROUP);
        verify(persistence, never()).putLog(TEST_GROUP, sMockLog);
    }
}
