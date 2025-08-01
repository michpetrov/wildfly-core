/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.controller;

import static java.security.AccessController.doPrivileged;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.DataOutput;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.as.controller.client.MessageSeverity;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.Operation;
import org.jboss.as.controller.client.OperationAttachments;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.controller.client.OperationMessageHandler;
import org.jboss.as.controller.client.impl.ExistingChannelModelControllerClient;
import org.jboss.as.controller.client.impl.InputStreamEntry;
import org.jboss.as.controller.remote.ModelControllerClientOperationHandler;
import org.jboss.as.controller.remote.ResponseAttachmentInputStreamSupport;
import org.jboss.as.controller.support.RemoteChannelPairSetup;
import org.jboss.as.protocol.mgmt.ManagementChannelHandler;
import org.jboss.as.protocol.mgmt.ManagementClientChannelStrategy;
import org.jboss.as.protocol.mgmt.support.ManagementChannelInitialization;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.jboss.remoting3.Channel;
import org.jboss.threads.JBossThreadFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.xnio.IoUtils;
/**
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @version $Revision: 1.1 $
 */
public class ModelControllerClientTestCase {

    Logger log = Logger.getLogger(ModelControllerClientTestCase.class);

    private RemoteChannelPairSetup channels;

    @Before
    public void start() throws Exception {
        channels = new RemoteChannelPairSetup();
    }

    @After
    public void stop() throws Exception {
        channels.stopChannels();
        channels.shutdownRemoting();
    }

    private ModelControllerClient setupTestClient(final ModelController controller) {
        try {
            channels.setupRemoting(new ManagementChannelInitialization() {
                @Override
                public ManagementChannelHandler startReceiving(Channel channel) {
                    final ManagementClientChannelStrategy strategy = ManagementClientChannelStrategy.create(channel);
                    final ManagementChannelHandler support = new ManagementChannelHandler(strategy, channels.getExecutorService());
                    support.addHandlerFactory(new ModelControllerClientOperationHandler(controller, support, new ResponseAttachmentInputStreamSupport(), getClientRequestExecutor()));
                    channel.receiveMessage(support.getReceiver());
                    return support;
                }

                private ExecutorService getClientRequestExecutor() {
                    final BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(512);
                    final ThreadFactory threadFactory = doPrivileged(new PrivilegedAction<>() {
                        public ThreadFactory run() {
                            return new JBossThreadFactory(new ThreadGroup("management-handler-thread"), Boolean.FALSE, null, "%G - %t", null, null);
                        }
                    });
                    ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4,
                            250L, TimeUnit.MILLISECONDS, workQueue,
                            threadFactory);
                    // Allow the core threads to time out as well
                    executor.allowCoreThreadTimeOut(true);
                    return executor;
                }
            });
            channels.startClientConnetion();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        final Channel clientChannel = channels.getClientChannel();
        return ExistingChannelModelControllerClient.createReceiving(clientChannel, channels.getExecutorService());
    }

    @Test @Ignore("WFCORE-1125")
    public void testSynchronousOperationMessageHandler() throws Exception {

        final CountDownLatch executeLatch = new CountDownLatch(1);
        MockModelController controller = new MockModelController() {
            @Override
            public ModelNode execute(ModelNode operation, OperationMessageHandler handler, OperationTransactionControl control, OperationAttachments attachments) {
                this.operation = operation;
                handler.handleReport(MessageSeverity.INFO, "Test1");
                handler.handleReport(MessageSeverity.INFO, "Test2");
                executeLatch.countDown();
                ModelNode result = new ModelNode();
                result.get("testing").set(operation.get("test"));
                return result;
            }
        };
        final ModelControllerClient client = setupTestClient(controller);
        try {
            ModelNode operation = new ModelNode();
            operation.get("test").set("123");

            final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

            ModelNode result = client.execute(operation,
                    new OperationMessageHandler() {

                        @Override
                        public void handleReport(MessageSeverity severity, String message) {
                            if (severity == MessageSeverity.INFO && message.startsWith("Test")) {
                                messages.add(message);
                            }
                        }
                    });
            assertEquals("123", controller.getOperation().get("test").asString());
            assertEquals("123", result.get("testing").asString());
            assertEquals("Test1", messages.take());
            assertEquals("Test2", messages.take());
        } finally {
            IoUtils.safeClose(client);
        }
    }

    @Test
    public void testSynchronousAttachmentInputStreams() throws Exception {

        final byte[] firstBytes = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        final byte[] secondBytes = new byte[] {10, 9, 8 , 7 , 6, 5, 4, 3, 2, 1};
        final byte[] thirdBytes = new byte[] {1};

        final CountDownLatch executeLatch = new CountDownLatch(1);
        final AtomicInteger size = new AtomicInteger();
        final AtomicReference<byte[]> firstResult = new AtomicReference<>();
        final AtomicReference<byte[]> secondResult = new AtomicReference<>();
        final AtomicReference<byte[]> thirdResult = new AtomicReference<>();
        MockModelController controller = new MockModelController() {
            @Override
            public ModelNode execute(ModelNode operation, OperationMessageHandler handler, OperationTransactionControl control, OperationAttachments attachments) {
                int streamIndex = 0;
                for (InputStream in : attachments.getInputStreams()) {
                    try {
                        ArrayList<Integer> readBytes = new ArrayList<>();
                        int b = in.read();
                        while (b != -1) {
                            readBytes.add(b);
                            b = in.read();
                        }

                        byte[] bytes = new byte[readBytes.size()];
                        for (int i = 0 ; i < bytes.length ; i++) {
                            bytes[i] = (byte)readBytes.get(i).intValue();
                        }

                        if (streamIndex == 0) {
                            firstResult.set(bytes);
                        } else if (streamIndex == 1) {
                            secondResult.set(bytes);
                        } else if (streamIndex == 2) {
                            thirdResult.set(bytes);
                        }
                        streamIndex++;

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                size.set(streamIndex);
                executeLatch.countDown();
                return new ModelNode();
            }
        };
        // Set the handler
        final ModelControllerClient client = setupTestClient(controller);
        try {
            ModelNode op = new ModelNode();
            op.get("operation").set("fake");
            op.get("name").set(123);
            OperationBuilder builder = new OperationBuilder(op);
            builder.addInputStream(new ByteArrayInputStream(firstBytes));
            builder.addInputStream(new ByteArrayInputStream(secondBytes));
            builder.addInputStream(new ByteArrayInputStream(thirdBytes));
            client.execute(builder.build());
            executeLatch.await();
            assertEquals(3, size.get());
            assertArrays(firstBytes, firstResult.get());
            assertArrays(secondBytes, secondResult.get());
            assertArrays(new byte[] { 1 }, thirdResult.get());
        } finally {
            IoUtils.safeClose(client);
        }
    }

    @Test @Ignore("WFCORE-1125")
    public void testAsynchronousOperationWithMessageHandler() throws Exception {
        final CountDownLatch executeLatch = new CountDownLatch(1);
        MockModelController controller = new MockModelController() {
            @Override
            public ModelNode execute(ModelNode operation, OperationMessageHandler handler, OperationTransactionControl control, OperationAttachments attachments) {
                this.operation = operation;
                handler.handleReport(MessageSeverity.INFO, "Test1");
                handler.handleReport(MessageSeverity.INFO, "Test2");
                executeLatch.countDown();
                ModelNode result = new ModelNode();
                result.get("testing").set(operation.get("test"));
                return result;
            }
        };

        // Set the handler
        final ModelControllerClient client = setupTestClient(controller);
        try {

            ModelNode operation = new ModelNode();
            operation.get("test").set("123");
            operation.get("operation").set("fake");

            final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

            Future<ModelNode> resultFuture = client.executeAsync(operation,
                    new OperationMessageHandler() {

                        @Override
                        public void handleReport(MessageSeverity severity, String message) {
                            if (severity == MessageSeverity.INFO && message.startsWith("Test")) {
                                messages.add(message);
                            }
                        }
                    });
            ModelNode result = resultFuture.get();
            assertEquals("123", controller.getOperation().get("test").asString());
            assertEquals("123", result.get("testing").asString());
            assertEquals("Test1", messages.take());
            assertEquals("Test2", messages.take());
        } finally {
            IoUtils.safeClose(client);
        }
    }

    @Test
    public void testCancelAsynchronousOperation() throws Exception {
        final CountDownLatch executeLatch = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        MockModelController controller = new MockModelController() {
            @Override
            public ModelNode execute(ModelNode operation, OperationMessageHandler handler, OperationTransactionControl control, OperationAttachments attachments) {
                this.operation = operation;
                executeLatch.countDown();

                try {
                    log.debug("Waiting for interrupt");
                    //Wait for this operation to be cancelled
                    Thread.sleep(10000000);
                    ModelNode result = new ModelNode();
                    result.get("testing").set(operation.get("test"));
                    return result;
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw new RuntimeException(e);
                }
            }
        };

        // Set the handler
        final ModelControllerClient client = setupTestClient(controller);
        try {
            ModelNode operation = new ModelNode();
            operation.get("test").set("123");
            operation.get("operation").set("fake");

            final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

            Future<ModelNode> resultFuture = client.executeAsync(operation,
                    new OperationMessageHandler() {

                        @Override
                        public void handleReport(MessageSeverity severity, String message) {
                            if (severity == MessageSeverity.INFO && message.startsWith("Test")) {
                                messages.add(message);
                            }
                        }
                    });
            executeLatch.await();
            resultFuture.cancel(false);
            interrupted.await();
        } finally {
            IoUtils.safeClose(client);
        }

    }

    @Test
    public void testCloseInputStreamEntry() throws Exception {
        final MockModelController controller = new MockModelController() {
            @Override
            public ModelNode execute(ModelNode operation, OperationMessageHandler handler, OperationTransactionControl control, OperationAttachments attachments) {
                ModelNode result = new ModelNode();
                result.get("testing").set(operation.get("test"));
                return result;
            }
        };
        final ModelControllerClient client = setupTestClient(controller);
        try {
            final ModelNode op = new ModelNode();
            op.get("operation").set("fake");
            final TestEntry entry = new TestEntry();
            final Operation operation = OperationBuilder.create(op)
                    .addInputStream(entry)
                    .build();

            // Execute the operation
            client.execute(operation);
            // Check closed
            entry.latch.await();
            Assert.assertTrue(entry.closed);
        } finally {
            IoUtils.safeClose(client);
        }
    }

    private void assertArrays(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0 ; i < expected.length ; i++) {
            assertEquals(expected[i], actual[i]);
        }
    }

    private abstract static class MockModelController extends org.jboss.as.controller.MockModelController {
        protected volatile ModelNode operation;

        ModelNode getOperation() {
            return operation;
        }
    }

    static class TestEntry extends FilterInputStream implements InputStreamEntry {

        final CountDownLatch latch = new CountDownLatch(1);
        boolean closed = false;

        TestEntry() {
            super(new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public void copyStream(DataOutput output) {
        }

        @Override
        public int initialize() {
            return 0;
        }

        @Override
        public void close() throws IOException {
            super.close();
            closed = true;
            latch.countDown();
        }
    }

}
