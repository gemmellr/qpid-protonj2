/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.qpid.protonj2.client.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.qpid.protonj2.client.Client;
import org.apache.qpid.protonj2.client.Connection;
import org.apache.qpid.protonj2.client.ConnectionOptions;
import org.apache.qpid.protonj2.client.DeliveryMode;
import org.apache.qpid.protonj2.client.Message;
import org.apache.qpid.protonj2.client.OutputStreamOptions;
import org.apache.qpid.protonj2.client.ReceiverOptions;
import org.apache.qpid.protonj2.client.SenderOptions;
import org.apache.qpid.protonj2.client.Session;
import org.apache.qpid.protonj2.client.StreamSender;
import org.apache.qpid.protonj2.client.StreamSenderMessage;
import org.apache.qpid.protonj2.client.StreamSenderOptions;
import org.apache.qpid.protonj2.client.Tracker;
import org.apache.qpid.protonj2.client.exceptions.ClientException;
import org.apache.qpid.protonj2.client.exceptions.ClientIllegalStateException;
import org.apache.qpid.protonj2.client.exceptions.ClientUnsupportedOperationException;
import org.apache.qpid.protonj2.client.test.ImperativeClientTestCase;
import org.apache.qpid.protonj2.test.driver.matchers.messaging.ApplicationPropertiesMatcher;
import org.apache.qpid.protonj2.test.driver.matchers.messaging.DeliveryAnnotationsMatcher;
import org.apache.qpid.protonj2.test.driver.matchers.messaging.FooterMatcher;
import org.apache.qpid.protonj2.test.driver.matchers.messaging.HeaderMatcher;
import org.apache.qpid.protonj2.test.driver.matchers.messaging.MessageAnnotationsMatcher;
import org.apache.qpid.protonj2.test.driver.matchers.messaging.PropertiesMatcher;
import org.apache.qpid.protonj2.test.driver.matchers.transport.TransferPayloadCompositeMatcher;
import org.apache.qpid.protonj2.test.driver.matchers.types.EncodedAmqpValueMatcher;
import org.apache.qpid.protonj2.test.driver.matchers.types.EncodedDataMatcher;
import org.apache.qpid.protonj2.test.driver.matchers.types.EncodedPartialDataSectionMatcher;
import org.apache.qpid.protonj2.test.driver.netty.NettyTestPeer;
import org.apache.qpid.protonj2.types.messaging.AmqpValue;
import org.apache.qpid.protonj2.types.messaging.Header;
import org.apache.qpid.protonj2.types.transport.Role;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the {@link message} implementation
 */
@Timeout(20)
public class StreamSenderTest extends ImperativeClientTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(StreamSenderTest.class);

    @Test
    public void testOpenStreamSenderWithLinCapabilities() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().withRole(Role.SENDER.getValue())
                               .withTarget().withCapabilities("queue").and()
                               .respond();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("StreamSender test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSenderOptions senderOptions = new StreamSenderOptions();
            senderOptions.targetOptions().capabilities("queue");
            StreamSender sender = connection.openStreamSender("test-queue", senderOptions);

            sender.openFuture().get();
            sender.close();

            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testSendCustomMessageWithMultipleAmqpValueSections() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectBegin().respond(); // Hidden session for stream sender
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(10).queue();
            peer.expectAttach().respond();  // Open a receiver to ensure sender link has processed
            peer.expectFlow();              // the inbound flow frame we sent previously before send.
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Sender test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort()).openFuture().get();
            Session session = connection.openSession().openFuture().get();

            StreamSenderOptions options = new StreamSenderOptions();
            options.deliveryMode(DeliveryMode.AT_MOST_ONCE);
            options.writeBufferSize(Integer.MAX_VALUE);

            StreamSender sender = connection.openStreamSender("test-qos", options);

            // Create a custom message format send context and ensure that no early buffer writes take place
            StreamSenderMessage message = sender.beginMessage();

            message.messageFormat(17);

            // Gates send on remote flow having been sent and received
            session.openReceiver("dummy").openFuture().get();

            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withTtl(65535);
            headerMatcher.withFirstAcquirer(true);
            headerMatcher.withDeliveryCount(2);
            // Note: This is a specification violation but could be used by other message formats
            //       and we don't attempt to enforce at the Send Context what users write
            EncodedAmqpValueMatcher bodyMatcher1 = new EncodedAmqpValueMatcher("one", true);
            EncodedAmqpValueMatcher bodyMatcher2 = new EncodedAmqpValueMatcher("two", true);
            EncodedAmqpValueMatcher bodyMatcher3 = new EncodedAmqpValueMatcher("three", false);
            TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setHeadersMatcher(headerMatcher);
            payloadMatcher.addMessageContentMatcher(bodyMatcher1);
            payloadMatcher.addMessageContentMatcher(bodyMatcher2);
            payloadMatcher.addMessageContentMatcher(bodyMatcher3);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withMore(false).withMessageFormat(17).withPayload(payloadMatcher).accept();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            // Populate all Header values
            Header header = new Header();
            header.setDurable(true);
            header.setPriority((byte) 1);
            header.setTimeToLive(65535);
            header.setFirstAcquirer(true);
            header.setDeliveryCount(2);

            message.header(header);
            message.addBodySection(new AmqpValue<>("one"));
            message.addBodySection(new AmqpValue<>("two"));
            message.addBodySection(new AmqpValue<>("three"));

            message.complete();

            assertNotNull(message.tracker().settlementFuture().isDone());
            assertNotNull(message.tracker().settlementFuture().get().settled());

            sender.closeAsync().get(10, TimeUnit.SECONDS);

            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testMessageFormatCannotBeModifiedAfterBodyWritesStart() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond(); // Hidden session for stream sender
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(10).queue();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Sender test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort()).openFuture().get();

            StreamSender sender = connection.openStreamSender("test-qos");
            StreamSenderMessage message = sender.beginMessage();

            sender.openFuture().get();

            message.durable(true);
            message.messageFormat(17);
            message.body();

            try {
                message.messageFormat(16);
                fail("Should not be able to modify message format after body writes started");
            } catch (ClientIllegalStateException ex) {
                // Expected
            } catch (Exception unexpected) {
                fail("Failed test due to message format set throwing unexpected error: " + unexpected);
            }

            message.abort();

            sender.closeAsync().get(10, TimeUnit.SECONDS);
            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testCannotCreateNewStreamingMessageWhileCurrentInstanceIsIncomplete() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond(); // Hidden session for stream sender
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(10).queue();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Sender test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort()).openFuture().get();

            StreamSender sender = connection.openStreamSender("test-qos");
            StreamSenderMessage message = sender.beginMessage();

            try {
                sender.beginMessage();
                fail("Should not be able create a new streaming sender message before last one is compelted.");
            } catch (ClientIllegalStateException ex) {
                // Expected
            }

            message.abort();

            sender.closeAsync().get(10, TimeUnit.SECONDS);
            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testCannotModifyMessagePreambleAfterWritesHaveStarted() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond(); // Hidden session for stream sender
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(10).queue();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Sender test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort()).openFuture().get();

            StreamSender sender = connection.openStreamSender("test-qos");
            StreamSenderMessage message = sender.beginMessage();

            message.durable(true);
            message.messageId("test");
            message.annotation("key", "value");
            message.applicationProperty("key", "value");
            message.body();

            try {
                message.durable(false);
                fail("Should not be able to modify message preamble after body writes started");
            } catch (ClientIllegalStateException ex) {
                // Expected
            }

            try {
                message.messageId("test1");
                fail("Should not be able to modify message preamble after body writes started");
            } catch (ClientIllegalStateException ex) {
                // Expected
            }

            try {
                message.annotation("key1", "value");
                fail("Should not be able to modify message preamble after body writes started");
            } catch (ClientIllegalStateException ex) {
                // Expected
            }

            try {
                message.applicationProperty("key", "value");
                fail("Should not be able to modify message preamble after body writes started");
            } catch (ClientIllegalStateException ex) {
                // Expected
            }

            message.abort();

            sender.closeAsync().get(10, TimeUnit.SECONDS);
            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testCreateStream() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.expectTransfer().withMore(false).withNullPayload();
            peer.expectDetach().withClosed(true).respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-qos");
            StreamSenderMessage tracker = sender.beginMessage();

            OutputStreamOptions options = new OutputStreamOptions();
            OutputStream stream = tracker.body(options);

            assertNotNull(stream);

            sender.openFuture().get();

            stream.close();

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testFlushWithSetNonBodySectionsThenClose() throws Exception {
        doTestNonBodySectionWrittenWhenNoWritesToStream(true);
    }

    @Test
    public void testCloseWithSetNonBodySections() throws Exception {
        doTestNonBodySectionWrittenWhenNoWritesToStream(false);
    }

    private void doTestNonBodySectionWrittenWhenNoWritesToStream(boolean flushBeforeClose) throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage message = sender.beginMessage();

            // Populate all Header values
            Header header = new Header();
            header.setDurable(true);
            header.setPriority((byte) 1);
            header.setTimeToLive(65535);
            header.setFirstAcquirer(true);
            header.setDeliveryCount(2);

            message.header(header);

            OutputStreamOptions options = new OutputStreamOptions();
            OutputStream stream = message.body(options);

            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withTtl(65535);
            headerMatcher.withFirstAcquirer(true);
            headerMatcher.withDeliveryCount(2);
            TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setHeadersMatcher(headerMatcher);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            if (flushBeforeClose) {
                peer.expectTransfer().withMore(true).withPayload(payloadMatcher);
                peer.expectTransfer().withMore(false).withNullPayload()
                                     .respond()
                                     .withSettled(true).withState().accepted();
            } else {
                peer.expectTransfer().withMore(false).withPayload(payloadMatcher)
                                     .respond()
                                     .withSettled(true).withState().accepted();
            }
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            // Once flush is called than anything in the buffer is written regardless of
            // there being any actual stream writes.  Default close action is to complete
            // the delivery.
            if (flushBeforeClose) {
                stream.flush();
            }
            stream.close();

            message.tracker().awaitSettlement(10, TimeUnit.SECONDS);

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testFlushAfterFirstWriteEncodesAMQPHeaderAndMessageBuffer() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage message = sender.beginMessage();

            // Populate all Header values
            Header header = new Header();
            header.setDurable(true);
            header.setPriority((byte) 1);
            header.setTimeToLive(65535);
            header.setFirstAcquirer(true);
            header.setDeliveryCount(2);

            message.header(header);

            OutputStreamOptions options = new OutputStreamOptions();
            OutputStream stream = message.body(options);

            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withTtl(65535);
            headerMatcher.withFirstAcquirer(true);
            headerMatcher.withDeliveryCount(2);
            EncodedDataMatcher dataMatcher = new EncodedDataMatcher(new byte[] { 0, 1, 2, 3 });
            TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setHeadersMatcher(headerMatcher);
            payloadMatcher.setMessageContentMatcher(dataMatcher);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withMore(true).withPayload(payloadMatcher);
            peer.expectTransfer().withMore(false).withNullPayload();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            // Stream won't output until some body bytes are written since the buffer was not
            // filled by the header write.  Then the close will complete the stream message.
            stream.write(new byte[] { 0, 1, 2, 3 });
            stream.flush();
            stream.close();

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testAutoFlushAfterSingleWriteExceedsConfiguredBufferLimit() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue", new StreamSenderOptions().writeBufferSize(512));
            StreamSenderMessage tracker = sender.beginMessage();

            final byte[] payload = new byte[512];
            Arrays.fill(payload, (byte) 16);

            // Populate all Header values
            Header header = new Header();
            header.setDurable(true);
            header.setPriority((byte) 1);
            header.setTimeToLive(65535);
            header.setFirstAcquirer(true);
            header.setDeliveryCount(2);

            tracker.header(header);

            OutputStreamOptions options = new OutputStreamOptions();
            OutputStream stream = tracker.body(options);

            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withTtl(65535);
            headerMatcher.withFirstAcquirer(true);
            headerMatcher.withDeliveryCount(2);
            EncodedDataMatcher dataMatcher = new EncodedDataMatcher(payload);
            TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setHeadersMatcher(headerMatcher);
            payloadMatcher.setMessageContentMatcher(dataMatcher);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher).withMore(true);

            // Stream won't output until some body bytes are written.
            stream.write(payload);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withNullPayload().withMore(false).accept();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            stream.close();

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testAutoFlushDuringWriteThatExceedConfiguredBufferLimit() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue", new StreamSenderOptions().writeBufferSize(256));
            StreamSenderMessage tracker = sender.beginMessage();

            final byte[] payload = new byte[1024];
            Arrays.fill(payload, 0, 256, (byte) 1);
            Arrays.fill(payload, 256, 512, (byte) 2);
            Arrays.fill(payload, 512, 768, (byte) 3);
            Arrays.fill(payload, 768, 1024, (byte) 4);

            final byte[] payload1 = new byte[256];
            Arrays.fill(payload1, (byte) 1);
            final byte[] payload2 = new byte[256];
            Arrays.fill(payload2, (byte) 2);
            final byte[] payload3 = new byte[256];
            Arrays.fill(payload3, (byte) 3);
            final byte[] payload4 = new byte[256];
            Arrays.fill(payload4, (byte) 4);

            // Populate all Header values
            Header header = new Header();
            header.setDurable(true);
            header.setPriority((byte) 1);
            header.setTimeToLive(65535);
            header.setFirstAcquirer(true);
            header.setDeliveryCount(2);

            tracker.header(header);

            OutputStreamOptions options = new OutputStreamOptions();
            OutputStream stream = tracker.body(options);

            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withTtl(65535);
            headerMatcher.withFirstAcquirer(true);
            headerMatcher.withDeliveryCount(2);
            EncodedDataMatcher dataMatcher1 = new EncodedDataMatcher(payload1);
            TransferPayloadCompositeMatcher payloadMatcher1 = new TransferPayloadCompositeMatcher();
            payloadMatcher1.setHeadersMatcher(headerMatcher);
            payloadMatcher1.setMessageContentMatcher(dataMatcher1);

            EncodedDataMatcher dataMatcher2 = new EncodedDataMatcher(payload2);
            TransferPayloadCompositeMatcher payloadMatcher2 = new TransferPayloadCompositeMatcher();
            payloadMatcher2.setMessageContentMatcher(dataMatcher2);

            EncodedDataMatcher dataMatcher3 = new EncodedDataMatcher(payload3);
            TransferPayloadCompositeMatcher payloadMatcher3 = new TransferPayloadCompositeMatcher();
            payloadMatcher3.setMessageContentMatcher(dataMatcher3);

            EncodedDataMatcher dataMatcher4 = new EncodedDataMatcher(payload4);
            TransferPayloadCompositeMatcher payloadMatcher4 = new TransferPayloadCompositeMatcher();
            payloadMatcher4.setMessageContentMatcher(dataMatcher4);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher1).withMore(true);
            peer.expectTransfer().withPayload(payloadMatcher2).withMore(true);
            peer.expectTransfer().withPayload(payloadMatcher3).withMore(true);
            peer.expectTransfer().withPayload(payloadMatcher4).withMore(true);

            // Stream won't output until some body bytes are written.
            stream.write(payload);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withNullPayload().withMore(false).accept();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            stream.close();

            sender.close();
            connection.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testAutoFlushDuringWriteThatExceedConfiguredBufferLimitSessionCreditLimitOnTransfer() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue", new StreamSenderOptions().writeBufferSize(256));
            StreamSenderMessage tracker = sender.beginMessage();

            final byte[] payload = new byte[1024];
            Arrays.fill(payload, 0, 256, (byte) 1);
            Arrays.fill(payload, 256, 512, (byte) 2);
            Arrays.fill(payload, 512, 768, (byte) 3);
            Arrays.fill(payload, 768, 1024, (byte) 4);

            final byte[] payload1 = new byte[256];
            Arrays.fill(payload1, (byte) 1);
            final byte[] payload2 = new byte[256];
            Arrays.fill(payload2, (byte) 2);
            final byte[] payload3 = new byte[256];
            Arrays.fill(payload3, (byte) 3);
            final byte[] payload4 = new byte[256];
            Arrays.fill(payload4, (byte) 4);

            // Populate all Header values
            Header header = new Header();
            header.setDurable(true);
            header.setPriority((byte) 1);
            header.setTimeToLive(65535);
            header.setFirstAcquirer(true);
            header.setDeliveryCount(2);

            tracker.header(header);

            OutputStreamOptions options = new OutputStreamOptions();
            OutputStream stream = tracker.body(options);

            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withTtl(65535);
            headerMatcher.withFirstAcquirer(true);
            headerMatcher.withDeliveryCount(2);
            EncodedDataMatcher dataMatcher1 = new EncodedDataMatcher(payload1);
            TransferPayloadCompositeMatcher payloadMatcher1 = new TransferPayloadCompositeMatcher();
            payloadMatcher1.setHeadersMatcher(headerMatcher);
            payloadMatcher1.setMessageContentMatcher(dataMatcher1);

            EncodedDataMatcher dataMatcher2 = new EncodedDataMatcher(payload2);
            TransferPayloadCompositeMatcher payloadMatcher2 = new TransferPayloadCompositeMatcher();
            payloadMatcher2.setMessageContentMatcher(dataMatcher2);

            EncodedDataMatcher dataMatcher3 = new EncodedDataMatcher(payload3);
            TransferPayloadCompositeMatcher payloadMatcher3 = new TransferPayloadCompositeMatcher();
            payloadMatcher3.setMessageContentMatcher(dataMatcher3);

            EncodedDataMatcher dataMatcher4 = new EncodedDataMatcher(payload4);
            TransferPayloadCompositeMatcher payloadMatcher4 = new TransferPayloadCompositeMatcher();
            payloadMatcher4.setMessageContentMatcher(dataMatcher4);

            final CountDownLatch sendComplete = new CountDownLatch(1);
            final AtomicBoolean sendFailed = new AtomicBoolean();
            // Stream won't output until some body bytes are written.
            ForkJoinPool.commonPool().execute(() -> {
                try {
                    stream.write(payload);
                } catch (IOException e) {
                    LOG.info("send failed with error: ", e);
                    sendFailed.set(true);
                } finally {
                    sendComplete.countDown();
                }
            });

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher1).withMore(true);
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(2).withLinkCredit(10).queue();
            peer.expectTransfer().withPayload(payloadMatcher2).withMore(true);
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(3).withLinkCredit(10).queue();
            peer.expectTransfer().withPayload(payloadMatcher3).withMore(true);
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(4).withLinkCredit(10).queue();
            peer.expectTransfer().withPayload(payloadMatcher4).withMore(true);
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(5).withLinkCredit(10).queue();
            peer.expectTransfer().withNullPayload().withMore(false).accept();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            // Initiate the above script of transfers and flows
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(1).withLinkCredit(10).now();

            assertTrue(sendComplete.await(10, TimeUnit.SECONDS));

            stream.close();

            assertFalse(sendFailed.get());

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testCloseAfterSingleWriteEncodesAndCompletesTransferWhenNoStreamSizeConfigured() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage tracker = sender.beginMessage();

            // Populate all Header values
            Header header = new Header();
            header.setDurable(true);
            header.setPriority((byte) 1);
            header.setTimeToLive(65535);
            header.setFirstAcquirer(true);
            header.setDeliveryCount(2);

            tracker.header(header);

            OutputStreamOptions options = new OutputStreamOptions();
            OutputStream stream = tracker.body(options);

            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withTtl(65535);
            headerMatcher.withFirstAcquirer(true);
            headerMatcher.withDeliveryCount(2);
            EncodedDataMatcher dataMatcher = new EncodedDataMatcher(new byte[] { 0, 1, 2, 3 });
            TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setHeadersMatcher(headerMatcher);
            payloadMatcher.setMessageContentMatcher(dataMatcher);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher).withMore(false).accept();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            // Stream won't output until some body bytes are written.
            stream.write(new byte[] { 0, 1, 2, 3 });
            stream.close();

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testFlushAfterSecondWriteDoesNotEncodeAMQPHeaderFromConfiguration() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage tracker = sender.beginMessage();

            // Populate all Header values
            Header header = new Header();
            header.setDurable(true);
            header.setPriority((byte) 1);
            header.setTimeToLive(65535);
            header.setFirstAcquirer(true);
            header.setDeliveryCount(2);

            tracker.header(header);

            OutputStreamOptions options = new OutputStreamOptions();
            OutputStream stream = tracker.body(options);

            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withTtl(65535);
            headerMatcher.withFirstAcquirer(true);
            headerMatcher.withDeliveryCount(2);
            EncodedDataMatcher dataMatcher1 = new EncodedDataMatcher(new byte[] { 0, 1, 2, 3 });
            TransferPayloadCompositeMatcher payloadMatcher1 = new TransferPayloadCompositeMatcher();
            payloadMatcher1.setHeadersMatcher(headerMatcher);
            payloadMatcher1.setMessageContentMatcher(dataMatcher1);

            // Second flush expectation
            EncodedDataMatcher dataMatcher2 = new EncodedDataMatcher(new byte[] { 4, 5, 6, 7 });
            TransferPayloadCompositeMatcher payloadMatcher2 = new TransferPayloadCompositeMatcher();
            payloadMatcher2.setMessageContentMatcher(dataMatcher2);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher1).withMore(true);
            peer.expectTransfer().withPayload(payloadMatcher2).withMore(true);
            peer.expectTransfer().withNullPayload().withMore(false).accept();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            // Stream won't output until some body bytes are written.
            stream.write(new byte[] { 0, 1, 2, 3 });
            stream.flush();

            // Next write should only be a single Data section
            stream.write(new byte[] { 4, 5, 6, 7 });
            stream.flush();

            // Final Transfer that completes the Delivery
            stream.close();

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testIncompleteStreamClosureCausesTransferAbort() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage tracker = sender.beginMessage();

            final byte[] payload = new byte[] { 0, 1, 2, 3 };

            // Populate all Header values
            Header header = new Header();
            header.setDurable(true);
            header.setPriority((byte) 1);
            header.setDeliveryCount(1);

            tracker.header(header);

            OutputStreamOptions options = new OutputStreamOptions().bodyLength(8192);
            OutputStream stream = tracker.body(options);

            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withDeliveryCount(1);
            EncodedPartialDataSectionMatcher partialDataMatcher = new EncodedPartialDataSectionMatcher(8192, payload);
            TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setHeadersMatcher(headerMatcher);
            payloadMatcher.setMessageContentMatcher(partialDataMatcher);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher);
            peer.expectTransfer().withAborted(true).withNullPayload();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            stream.write(payload);
            stream.flush();

            // Stream should abort the send now since the configured size wasn't sent.
            stream.close();

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testIncompleteStreamClosureWithNoWritesAbortsTransfer() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage message = sender.beginMessage();

            // Populate all Header values
            Header header = new Header();
            header.setDurable(true);
            header.setPriority((byte) 1);
            header.setDeliveryCount(1);

            message.header(header);

            OutputStreamOptions options = new OutputStreamOptions().bodyLength(8192).completeSendOnClose(false);
            OutputStream stream = message.body(options);

            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withDeliveryCount(1);
            TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setHeadersMatcher(headerMatcher);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            // This should abort the transfer as we might have triggered output upon create when the
            // preamble was written.
            stream.close();

            assertTrue(message.aborted());

            // Should have no affect.
            message.abort();

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testCompleteStreamClosureCausesTransferCompleted() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(3).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage tracker = sender.beginMessage();

            final byte[] payload1 = new byte[] { 0, 1, 2, 3, 4, 5 };
            final byte[] payload2 = new byte[] { 6, 7, 8, 9, 10, 11, 12, 13, 14 };
            final byte[] payload3 = new byte[] { 15 };

            final int payloadSize = payload1.length + payload2.length + payload3.length;

            // Populate all Header values
            Header header = new Header();
            header.setDurable(true);
            header.setPriority((byte) 1);
            header.setDeliveryCount(1);

            tracker.header(header);

            // Populate message application properties
            tracker.applicationProperty("ap1", 1);
            tracker.applicationProperty("ap2", 2);
            tracker.applicationProperty("ap3", 3);

            OutputStreamOptions options = new OutputStreamOptions().bodyLength(payloadSize);
            OutputStream stream = tracker.body(options);

            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withDeliveryCount(1);
            ApplicationPropertiesMatcher apMatcher = new ApplicationPropertiesMatcher(true);
            apMatcher.withEntry("ap1", Matchers.equalTo(1));
            apMatcher.withEntry("ap2", Matchers.equalTo(2));
            apMatcher.withEntry("ap3", Matchers.equalTo(3));
            EncodedPartialDataSectionMatcher partialDataMatcher = new EncodedPartialDataSectionMatcher(payloadSize, payload1);
            TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setHeadersMatcher(headerMatcher);
            payloadMatcher.setMessageContentMatcher(partialDataMatcher);
            payloadMatcher.setApplicationPropertiesMatcher(apMatcher);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher);

            stream.write(payload1);
            stream.flush();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            partialDataMatcher = new EncodedPartialDataSectionMatcher(payload2);
            payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setMessageContentMatcher(partialDataMatcher);
            peer.expectTransfer().withMore(true).withPayload(partialDataMatcher);

            stream.write(payload2);
            stream.flush();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            partialDataMatcher = new EncodedPartialDataSectionMatcher(payload3);
            payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setMessageContentMatcher(partialDataMatcher);
            peer.expectTransfer().withMore(false).withPayload(partialDataMatcher).accept();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            stream.write(payload3);
            stream.flush();

            // Stream should already be completed so no additional frames should be written.
            stream.close();

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testRawOutputStreamFromMessageWritesUnmodifiedBytes() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage message = sender.beginMessage();

            OutputStream stream = message.rawOutputStream();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withMore(true).withPayload(new byte[] { 0, 1, 2, 3 });
            peer.expectTransfer().withMore(false).withNullPayload();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            stream.write(new byte[] { 0, 1, 2, 3 });
            stream.flush();
            stream.close();

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testStreamSenderMessageWithDeliveryAnnotations() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(10).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Sender test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort()).openFuture().get();

            // Populate delivery annotations
            final Map<String, Object> deliveryAnnotations = new HashMap<>();
            deliveryAnnotations.put("da1", 1);
            deliveryAnnotations.put("da2", 2);
            deliveryAnnotations.put("da3", 3);

            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage message = sender.beginMessage(deliveryAnnotations);

            final byte[] payload = new byte[] { 0, 1, 2, 3, 4, 5 };

            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withTtl(65535);
            headerMatcher.withFirstAcquirer(true);
            headerMatcher.withDeliveryCount(2);
            PropertiesMatcher propertiesMatcher = new PropertiesMatcher(true);
            propertiesMatcher.withMessageId("ID:12345");
            propertiesMatcher.withUserId("user".getBytes(StandardCharsets.UTF_8));
            propertiesMatcher.withTo("the-management");
            propertiesMatcher.withSubject("amqp");
            propertiesMatcher.withReplyTo("the-minions");
            propertiesMatcher.withCorrelationId("abc");
            propertiesMatcher.withContentEncoding("application/json");
            propertiesMatcher.withContentEncoding("gzip");
            propertiesMatcher.withAbsoluteExpiryTime(123);
            propertiesMatcher.withCreationTime(1);
            propertiesMatcher.withGroupId("disgruntled");
            propertiesMatcher.withGroupSequence(8192);
            propertiesMatcher.withReplyToGroupId("/dev/null");
            DeliveryAnnotationsMatcher daMatcher = new DeliveryAnnotationsMatcher(true);
            daMatcher.withEntry("da1", Matchers.equalTo(1));
            daMatcher.withEntry("da2", Matchers.equalTo(2));
            daMatcher.withEntry("da3", Matchers.equalTo(3));
            MessageAnnotationsMatcher maMatcher = new MessageAnnotationsMatcher(true);
            maMatcher.withEntry("ma1", Matchers.equalTo(1));
            maMatcher.withEntry("ma2", Matchers.equalTo(2));
            maMatcher.withEntry("ma3", Matchers.equalTo(3));
            ApplicationPropertiesMatcher apMatcher = new ApplicationPropertiesMatcher(true);
            apMatcher.withEntry("ap1", Matchers.equalTo(1));
            apMatcher.withEntry("ap2", Matchers.equalTo(2));
            apMatcher.withEntry("ap3", Matchers.equalTo(3));
            EncodedDataMatcher bodyMatcher = new EncodedDataMatcher(payload);
            TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setHeadersMatcher(headerMatcher);
            payloadMatcher.setDeliveryAnnotationsMatcher(daMatcher);
            payloadMatcher.setMessageAnnotationsMatcher(maMatcher);
            payloadMatcher.setPropertiesMatcher(propertiesMatcher);
            payloadMatcher.setApplicationPropertiesMatcher(apMatcher);
            payloadMatcher.setMessageContentMatcher(bodyMatcher);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher).withMore(false).accept();

            // Populate all Header values
            message.durable(true);
            message.priority((byte) 1);
            message.timeToLive(65535);
            message.firstAcquirer(true);
            message.deliveryCount(2);
            // Populate message annotations
            message.annotation("ma1", 1);
            message.annotation("ma2", 2);
            message.annotation("ma3", 3);
            // Populate all Properties values
            message.messageId("ID:12345");
            message.userId("user".getBytes(StandardCharsets.UTF_8));
            message.to("the-management");
            message.subject("amqp");
            message.replyTo("the-minions");
            message.correlationId("abc");
            message.contentEncoding("application/json");
            message.contentEncoding("gzip");
            message.absoluteExpiryTime(123);
            message.creationTime(1);
            message.groupId("disgruntled");
            message.groupSequence(8192);
            message.replyToGroupId("/dev/null");
            // Populate message application properties
            message.applicationProperty("ap1", 1);
            message.applicationProperty("ap2", 2);
            message.applicationProperty("ap3", 3);

            OutputStream stream = message.body();

            stream.write(payload);
            stream.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            assertNotNull(message.tracker());
            assertNotNull(message.tracker().settlementFuture().isDone());
            assertNotNull(message.tracker().settlementFuture().get().settled());

            sender.closeAsync().get(10, TimeUnit.SECONDS);

            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testStreamSenderWritesFooterAfterStreamClosed() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(10).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Sender test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort()).openFuture().get();
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage message = sender.beginMessage();

            final byte[] payload = new byte[] { 0, 1, 2, 3, 4, 5 };

            // First frame should include only the bits up to the body
            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withTtl(65535);
            headerMatcher.withFirstAcquirer(true);
            headerMatcher.withDeliveryCount(2);
            ApplicationPropertiesMatcher apMatcher = new ApplicationPropertiesMatcher(true);
            apMatcher.withEntry("ap1", Matchers.equalTo(1));
            apMatcher.withEntry("ap2", Matchers.equalTo(2));
            apMatcher.withEntry("ap3", Matchers.equalTo(3));
            FooterMatcher footerMatcher = new FooterMatcher(false);
            footerMatcher.withEntry("f1", Matchers.equalTo(1));
            footerMatcher.withEntry("f2", Matchers.equalTo(2));
            footerMatcher.withEntry("f3", Matchers.equalTo(3));
            EncodedDataMatcher bodyMatcher = new EncodedDataMatcher(payload, true);
            TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setHeadersMatcher(headerMatcher);
            payloadMatcher.setApplicationPropertiesMatcher(apMatcher);
            payloadMatcher.setMessageContentMatcher(bodyMatcher);
            payloadMatcher.setFootersMatcher(footerMatcher);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher).withMore(false).accept();

            // Populate all Header values
            message.durable(true);
            message.priority((byte) 1);
            message.timeToLive(65535);
            message.firstAcquirer(true);
            message.deliveryCount(2);
            // Populate message application properties
            message.applicationProperty("ap1", 1);
            message.applicationProperty("ap2", 2);
            message.applicationProperty("ap3", 3);
            // Populate message footers
            message.footer("f1", 1);
            message.footer("f2", 2);
            message.footer("f3", 3);

            OutputStreamOptions bodyOptions = new OutputStreamOptions().completeSendOnClose(true);
            OutputStream stream = message.body(bodyOptions);

            stream.write(payload);
            stream.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            assertNotNull(message.tracker());
            assertNotNull(message.tracker().settlementFuture().isDone());
            assertNotNull(message.tracker().settlementFuture().get().settled());

            sender.closeAsync().get(10, TimeUnit.SECONDS);

            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testStreamSenderWritesFooterAfterMessageCompleted() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(10).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Sender test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort()).openFuture().get();
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage message = sender.beginMessage();

            final byte[] payload = new byte[] { 0, 1, 2, 3, 4, 5 };

            // First frame should include only the bits up to the body
            HeaderMatcher headerMatcher = new HeaderMatcher(true);
            headerMatcher.withDurable(true);
            headerMatcher.withPriority((byte) 1);
            headerMatcher.withTtl(65535);
            headerMatcher.withFirstAcquirer(true);
            headerMatcher.withDeliveryCount(2);
            ApplicationPropertiesMatcher apMatcher = new ApplicationPropertiesMatcher(true);
            apMatcher.withEntry("ap1", Matchers.equalTo(1));
            apMatcher.withEntry("ap2", Matchers.equalTo(2));
            apMatcher.withEntry("ap3", Matchers.equalTo(3));
            EncodedDataMatcher bodyMatcher = new EncodedDataMatcher(payload);
            TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setHeadersMatcher(headerMatcher);
            payloadMatcher.setApplicationPropertiesMatcher(apMatcher);
            payloadMatcher.setMessageContentMatcher(bodyMatcher);

            // Second Frame should contains the appended footers
            FooterMatcher footerMatcher = new FooterMatcher(false);
            footerMatcher.withEntry("f1", Matchers.equalTo(1));
            footerMatcher.withEntry("f2", Matchers.equalTo(2));
            footerMatcher.withEntry("f3", Matchers.equalTo(3));
            TransferPayloadCompositeMatcher payloadFooterMatcher = new TransferPayloadCompositeMatcher();
            payloadFooterMatcher.setFootersMatcher(footerMatcher);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher).withMore(true);
            peer.expectTransfer().withPayload(payloadFooterMatcher).withMore(false).accept();

            // Populate all Header values
            message.durable(true);
            message.priority((byte) 1);
            message.timeToLive(65535);
            message.firstAcquirer(true);
            message.deliveryCount(2);
            // Populate message application properties
            message.applicationProperty("ap1", 1);
            message.applicationProperty("ap2", 2);
            message.applicationProperty("ap3", 3);

            OutputStreamOptions bodyOptions = new OutputStreamOptions().completeSendOnClose(false);
            OutputStream stream = message.body(bodyOptions);

            stream.write(payload);
            stream.close();

            // Populate message footers
            message.footer("f1", 1);
            message.footer("f2", 2);
            message.footer("f3", 3);

            message.complete();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            assertNotNull(message.tracker());
            assertNotNull(message.tracker().settlementFuture().isDone());
            assertNotNull(message.tracker().settlementFuture().get().settled());

            sender.closeAsync().get(10, TimeUnit.SECONDS);

            connection.closeAsync().get(10, TimeUnit.SECONDS);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testAutoFlushDuringMessageSendThatExceedConfiguredBufferLimitSessionCreditLimitOnTransfer() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            ConnectionOptions options = new ConnectionOptions().maxFrameSize(1024);
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);
            StreamSender sender = connection.openStreamSender("test-queue");

            final byte[] payload = new byte[4800];
            Arrays.fill(payload, (byte) 1);

            final AtomicBoolean sendFailed = new AtomicBoolean();
            ForkJoinPool.commonPool().execute(() -> {
                try {
                    sender.send(Message.create(payload));
                } catch (Exception e) {
                    LOG.info("send failed with error: ", e);
                    sendFailed.set(true);
                }
            });

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(1).withLinkCredit(10).now();
            peer.expectTransfer().withNonNullPayload().withMore(true);
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(2).withLinkCredit(10).queue();
            peer.expectTransfer().withNonNullPayload().withMore(true);
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(3).withLinkCredit(10).queue();
            peer.expectTransfer().withNonNullPayload().withMore(true);
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(4).withLinkCredit(10).queue();
            peer.expectTransfer().withNonNullPayload().withMore(true);
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(5).withLinkCredit(10).queue();
            peer.expectTransfer().withNonNullPayload().withMore(false).accept();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            assertFalse(sendFailed.get());

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testConcurrentMessageSendOnlyBlocksForInitialSendInProgress() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(2).queue();
            peer.expectBegin().respond();
            peer.expectAttach().ofReceiver().respond();
            peer.expectFlow();
            peer.expectTransfer().withNonNullPayload().withMore(false).respond().withSettled(true).withState().accepted();
            peer.expectTransfer().withNonNullPayload().withMore(false).respond().withSettled(true).withState().accepted();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            sender.openFuture().get();

            // Ensure that sender gets its flow before the sends are triggered.
            connection.openReceiver("test-queue").openFuture().get();

            final byte[] payload = new byte[1024];
            Arrays.fill(payload, (byte) 1);

            // One should block on the send waiting for the others send to finish
            // otherwise they should not care about concurrency of sends.

            final AtomicBoolean sendFailed = new AtomicBoolean();
            ForkJoinPool.commonPool().execute(() -> {
                try {
                    LOG.info("Test send 1 is preparing to fire:");
                    Tracker tracker = sender.send(Message.create(payload));
                    tracker.awaitSettlement(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOG.info("Test send 1 failed with error: ", e);
                    sendFailed.set(true);
                }
            });

            ForkJoinPool.commonPool().execute(() -> {
                try {
                    LOG.info("Test send 2 is preparing to fire:");
                    Tracker tracker = sender.send(Message.create(payload));
                    tracker.awaitSettlement(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOG.info("Test send 2 failed with error: ", e);
                    sendFailed.set(true);
                }
            });

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            assertFalse(sendFailed.get());

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testConcurrentMessageSendsBlocksBehindSendWaitingForCredit() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");

            final byte[] payload = new byte[1024];
            Arrays.fill(payload, (byte) 1);

            final CountDownLatch send1Started = new CountDownLatch(1);
            final CountDownLatch send2Completed = new CountDownLatch(1);

            final AtomicBoolean sendFailed = new AtomicBoolean();
            ForkJoinPool.commonPool().execute(() -> {
                try {
                    LOG.info("Test send 1 is preparing to fire:");
                    ForkJoinPool.commonPool().execute(() -> send1Started.countDown());
                    sender.send(Message.create(payload));
                } catch (Exception e) {
                    LOG.info("Test send 1 failed with error: ", e);
                    sendFailed.set(true);
                }
            });

            ForkJoinPool.commonPool().execute(() -> {
                try {
                    assertTrue(send1Started.await(10, TimeUnit.SECONDS));
                    LOG.info("Test send 2 is preparing to fire:");
                    Tracker tracker = sender.send(Message.create(payload));
                    tracker.awaitSettlement(10, TimeUnit.SECONDS);
                    send2Completed.countDown();
                } catch (Exception e) {
                    LOG.info("Test send 2 failed with error: ", e);
                    sendFailed.set(true);
                }
            });

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.remoteFlow().withIncomingWindow(1).withDeliveryCount(0).withNextIncomingId(1).withLinkCredit(1).now();
            peer.expectTransfer().withNonNullPayload().withMore(false).respond().withSettled(true).withState().accepted();
            peer.remoteFlow().withIncomingWindow(1).withDeliveryCount(1).withNextIncomingId(2).withLinkCredit(1).queue();
            peer.expectTransfer().withNonNullPayload().withMore(false).respond().withSettled(true).withState().accepted();

            assertTrue(send2Completed.await(10, TimeUnit.SECONDS));

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            assertFalse(sendFailed.get());

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testConcurrentMessageSendWaitingOnSplitFramedSendToCompleteIsSentAfterCreditUpdated() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            ConnectionOptions options = new ConnectionOptions().maxFrameSize(1024);
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort(), options);
            StreamSender sender = connection.openStreamSender("test-queue");

            final byte[] payload = new byte[1536];
            Arrays.fill(payload, (byte) 1);

            final CountDownLatch send1Started = new CountDownLatch(1);
            final CountDownLatch send2Completed = new CountDownLatch(1);

            final AtomicBoolean sendFailed = new AtomicBoolean();
            ForkJoinPool.commonPool().execute(() -> {
                try {
                    LOG.info("Test send 1 is preparing to fire:");
                    ForkJoinPool.commonPool().execute(() -> send1Started.countDown());
                    sender.send(Message.create(payload));
                } catch (Exception e) {
                    LOG.info("Test send 1 failed with error: ", e);
                    sendFailed.set(true);
                }
            });

            ForkJoinPool.commonPool().execute(() -> {
                try {
                    assertTrue(send1Started.await(10, TimeUnit.SECONDS));
                    LOG.info("Test send 2 is preparing to fire:");
                    Tracker tracker = sender.send(Message.create(payload));
                    tracker.awaitSettlement(10, TimeUnit.SECONDS);
                    send2Completed.countDown();
                } catch (Exception e) {
                    LOG.info("Test send 2 failed with error: ", e);
                    sendFailed.set(true);
                }
            });

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.remoteFlow().withIncomingWindow(1).withDeliveryCount(0).withNextIncomingId(1).withLinkCredit(1).now();
            peer.expectTransfer().withNonNullPayload().withMore(true);
            peer.remoteFlow().withIncomingWindow(1).withDeliveryCount(0).withNextIncomingId(2).withLinkCredit(1).queue();
            peer.expectTransfer().withNonNullPayload().withMore(false).respond().withSettled(true).withState().accepted();
            peer.remoteFlow().withIncomingWindow(1).withDeliveryCount(1).withNextIncomingId(3).withLinkCredit(1).queue();
            peer.expectTransfer().withNonNullPayload().withMore(true);
            peer.remoteFlow().withIncomingWindow(1).withDeliveryCount(1).withNextIncomingId(4).withLinkCredit(1).queue();
            peer.expectTransfer().withNonNullPayload().withMore(false).respond().withSettled(true).withState().accepted();

            assertTrue(send2Completed.await(10, TimeUnit.SECONDS));

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            assertFalse(sendFailed.get());

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testMessageSendWhileStreamSendIsOpenShouldBlock() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();
            final byte[] payload = new byte[1536];
            Arrays.fill(payload, (byte) 1);

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage message = sender.beginMessage();
            OutputStreamOptions options = new OutputStreamOptions().bodyLength(8192).completeSendOnClose(false);
            OutputStream stream = message.body(options);

            final CountDownLatch sendStarted = new CountDownLatch(1);
            final CountDownLatch sendCompleted = new CountDownLatch(1);
            final AtomicBoolean sendFailed = new AtomicBoolean();

            ForkJoinPool.commonPool().execute(() -> {
                try {
                    LOG.info("Test send 1 is preparing to fire:");
                    sendStarted.countDown();
                    sender.send(Message.create(payload));
                    sendCompleted.countDown();
                } catch (Exception e) {
                    LOG.info("Test send 1 failed with error: ", e);
                    sendFailed.set(true);
                }
            });

            EncodedDataMatcher bodyMatcher = new EncodedDataMatcher(payload);
            TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
            payloadMatcher.setMessageContentMatcher(bodyMatcher);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher).accept();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            assertTrue(sendStarted.await(10, TimeUnit.SECONDS));

            // This should abort the streamed send as we provided a size for the body.
            stream.close();
            assertTrue(message.aborted());
            assertTrue(sendCompleted.await(100, TimeUnit.SECONDS));

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testStreamSenderSessionCannotCreateNewResources() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");

            assertThrows(ClientUnsupportedOperationException.class, () -> sender.session().openReceiver("test"));
            assertThrows(ClientUnsupportedOperationException.class, () -> sender.session().openReceiver("test", new ReceiverOptions()));
            assertThrows(ClientUnsupportedOperationException.class, () -> sender.session().openDurableReceiver("test", "test"));
            assertThrows(ClientUnsupportedOperationException.class, () -> sender.session().openDurableReceiver("test", "test", new ReceiverOptions()));
            assertThrows(ClientUnsupportedOperationException.class, () -> sender.session().openDynamicReceiver());
            assertThrows(ClientUnsupportedOperationException.class, () -> sender.session().openDynamicReceiver(new HashMap<>()));
            assertThrows(ClientUnsupportedOperationException.class, () -> sender.session().openDynamicReceiver(new ReceiverOptions()));
            assertThrows(ClientUnsupportedOperationException.class, () -> sender.session().openDynamicReceiver(new HashMap<>(), new ReceiverOptions()));
            assertThrows(ClientUnsupportedOperationException.class, () -> sender.session().openSender("test"));
            assertThrows(ClientUnsupportedOperationException.class, () -> sender.session().openSender("test", new SenderOptions()));
            assertThrows(ClientUnsupportedOperationException.class, () -> sender.session().openAnonymousSender());
            assertThrows(ClientUnsupportedOperationException.class, () -> sender.session().openAnonymousSender(new SenderOptions()));

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);

            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            sender.close();
            connection.close();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testStreamMessageWaitingOnCreditWritesWhileCompleteSendWaitsInQueue() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage tracker = sender.beginMessage();
            OutputStream stream = tracker.body();

            final byte[] payload1 = new byte[256];
            Arrays.fill(payload1, (byte) 1);
            final byte[] payload2 = new byte[256];
            Arrays.fill(payload2, (byte) 2);
            final byte[] payload3 = new byte[256];
            Arrays.fill(payload3, (byte) 3);

            EncodedDataMatcher dataMatcher1 = new EncodedDataMatcher(payload1);
            TransferPayloadCompositeMatcher payloadMatcher1 = new TransferPayloadCompositeMatcher();
            payloadMatcher1.setMessageContentMatcher(dataMatcher1);

            EncodedDataMatcher dataMatcher2 = new EncodedDataMatcher(payload2);
            TransferPayloadCompositeMatcher payloadMatcher2 = new TransferPayloadCompositeMatcher();
            payloadMatcher2.setMessageContentMatcher(dataMatcher2);

            EncodedDataMatcher dataMatcher3 = new EncodedDataMatcher(payload3);
            TransferPayloadCompositeMatcher payloadMatcher3 = new TransferPayloadCompositeMatcher();
            payloadMatcher3.setMessageContentMatcher(dataMatcher3);

            final AtomicBoolean sendFailed = new AtomicBoolean();
            final CountDownLatch streamSend1Complete = new CountDownLatch(1);
            // Stream won't output until some body bytes are written.
            ForkJoinPool.commonPool().execute(() -> {
                try {
                    stream.write(payload1);
                    stream.flush();
                } catch (IOException e) {
                    LOG.info("send failed with error: ", e);
                    sendFailed.set(true);
                } finally {
                    streamSend1Complete.countDown();
                }
            });

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher1).withMore(true);
            // Now trigger the next send by granting credit for payload 1
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(1).withLinkCredit(10).now();

            assertTrue(streamSend1Complete.await(5, TimeUnit.SECONDS), "Stream sender completed first send");
            assertFalse(sendFailed.get());

            final CountDownLatch sendStarted = new CountDownLatch(1);
            final CountDownLatch sendCompleted = new CountDownLatch(1);

            ForkJoinPool.commonPool().execute(() -> {
                try {
                    LOG.info("Test send 1 is preparing to fire:");
                    sendStarted.countDown();
                    sender.send(Message.create(payload3));
                } catch (Exception e) {
                    LOG.info("Test send 1 failed with error: ", e);
                    sendFailed.set(true);
                } finally {
                    sendCompleted.countDown();
                }
            });

            assertTrue(sendStarted.await(10, TimeUnit.SECONDS));

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher2).withMore(true);
            // Queue a flow that will allow send by granting credit for payload 3 via sender.send
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(3).withLinkCredit(10).queue();
            // Now trigger the next send by granting credit for payload 2
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(2).withLinkCredit(10).now();

            stream.write(payload2);
            stream.flush();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withNullPayload().withMore(false).accept();
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(4).withLinkCredit(10).queue();
            peer.expectTransfer().withPayload(payloadMatcher3).withMore(false);
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            stream.close();

            assertTrue(sendCompleted.await(100, TimeUnit.SECONDS));
            assertFalse(sendFailed.get());

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testWriteToCreditLimitFramesOfMessagePayload() throws Exception {
        final int WRITE_COUNT = 10;

        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withIncomingWindow(WRITE_COUNT).withNextIncomingId(1).withLinkCredit(WRITE_COUNT).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage tracker = sender.beginMessage();
            OutputStream stream = tracker.body();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);

            final byte[][] payloads = new byte[WRITE_COUNT][256];
            for (int i = 0; i < WRITE_COUNT; ++i) {
                payloads[i] = new byte[256];
                Arrays.fill(payloads[i], (byte)(i + 1));
            }

            for (int i = 0; i < WRITE_COUNT; ++i) {
                EncodedDataMatcher dataMatcher = new EncodedDataMatcher(payloads[i]);
                TransferPayloadCompositeMatcher payloadMatcher = new TransferPayloadCompositeMatcher();
                payloadMatcher.setMessageContentMatcher(dataMatcher);

                peer.expectTransfer().withPayload(payloadMatcher).withMore(true);
            }

            for (int i = 0; i < WRITE_COUNT; ++i) {
                stream.write(payloads[i]);
                stream.flush();
            }

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withNullPayload().withMore(false).accept();
            peer.expectDetach().respond();
            peer.expectEnd().respond();
            peer.expectClose().respond();

            // grant one more credit for the complete to arrive.
            peer.remoteFlow().withIncomingWindow(1).withNextIncomingId(WRITE_COUNT + 1).withLinkCredit(1).now();

            stream.close();

            sender.closeAsync().get();
            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testStreamMessageFlushFailsAfterConnectionDropped() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage message = sender.beginMessage();

            OutputStream stream = message.body();

            EncodedDataMatcher dataMatcher1 = new EncodedDataMatcher(new byte[] { 0, 1, 2, 3 });
            TransferPayloadCompositeMatcher payloadMatcher1 = new TransferPayloadCompositeMatcher();
            payloadMatcher1.setMessageContentMatcher(dataMatcher1);

            EncodedDataMatcher dataMatcher2 = new EncodedDataMatcher(new byte[] { 4, 5, 6, 7 });
            TransferPayloadCompositeMatcher payloadMatcher2 = new TransferPayloadCompositeMatcher();
            payloadMatcher2.setMessageContentMatcher(dataMatcher2);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher1).withMore(true);
            peer.expectTransfer().withPayload(payloadMatcher2).withMore(true);
            peer.dropAfterLastHandler();

            // Write two then after connection drops the message should fail on future writes
            stream.write(new byte[] { 0, 1, 2, 3 });
            stream.flush();
            stream.write(new byte[] { 4, 5, 6, 7 });
            stream.flush();

            peer.waitForScriptToComplete();

            // Next write should fail as connection should have dropped.
            stream.write(new byte[] { 8, 9, 10, 11 });

            try {
                stream.flush();
                fail("Should not be able to flush after connection drop");
            } catch (IOException ioe) {
                assertTrue(ioe.getCause() instanceof ClientException);
            }

            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testStreamMessageCloseThatFlushesFailsAfterConnectionDropped() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSender sender = connection.openStreamSender("test-queue");
            StreamSenderMessage message = sender.beginMessage();

            OutputStream stream = message.body();

            EncodedDataMatcher dataMatcher1 = new EncodedDataMatcher(new byte[] { 0, 1, 2, 3 });
            TransferPayloadCompositeMatcher payloadMatcher1 = new TransferPayloadCompositeMatcher();
            payloadMatcher1.setMessageContentMatcher(dataMatcher1);

            EncodedDataMatcher dataMatcher2 = new EncodedDataMatcher(new byte[] { 4, 5, 6, 7 });
            TransferPayloadCompositeMatcher payloadMatcher2 = new TransferPayloadCompositeMatcher();
            payloadMatcher2.setMessageContentMatcher(dataMatcher2);

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
            peer.expectTransfer().withPayload(payloadMatcher1).withMore(true);
            peer.expectTransfer().withPayload(payloadMatcher2).withMore(true);
            peer.dropAfterLastHandler();

            // Write two then after connection drops the message should fail on future writes
            stream.write(new byte[] { 0, 1, 2, 3 });
            stream.flush();
            stream.write(new byte[] { 4, 5, 6, 7 });
            stream.flush();

            peer.waitForScriptToComplete();

            // Next write should fail as connection should have dropped.
            stream.write(new byte[] { 8, 9, 10, 11 });

            try {
                stream.close();
                fail("Should not be able to close after connection drop");
            } catch (IOException ioe) {
                assertTrue(ioe.getCause() instanceof ClientException);
            }

            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testStreamMessageWriteThatFlushesFailsAfterConnectionDropped() throws Exception {
        try (NettyTestPeer peer = new NettyTestPeer()) {
            peer.expectSASLAnonymousConnect();
            peer.expectOpen().respond();
            peer.expectBegin().respond();
            peer.expectAttach().ofSender().respond();
            peer.remoteFlow().withLinkCredit(1).queue();
            peer.dropAfterLastHandler();
            peer.start();

            URI remoteURI = peer.getServerURI();

            LOG.info("Test started, peer listening on: {}", remoteURI);

            Client container = Client.create();
            Connection connection = container.connect(remoteURI.getHost(), remoteURI.getPort());
            StreamSenderOptions options = new StreamSenderOptions().writeBufferSize(1024);
            StreamSender sender = connection.openStreamSender("test-queue", options);
            StreamSenderMessage message = sender.beginMessage();

            byte[] payload = new byte[65535];
            Arrays.fill(payload, (byte) 65);
            OutputStreamOptions streamOptions = new OutputStreamOptions().bodyLength(65535);
            OutputStream stream = message.body(streamOptions);

            peer.waitForScriptToComplete();

            try {
                stream.write(payload);
                fail("Should not be able to write section after connection drop");
            } catch (IOException ioe) {
                assertTrue(ioe.getCause() instanceof ClientException);
            }

            connection.closeAsync().get();

            peer.waitForScriptToComplete(5, TimeUnit.SECONDS);
        }
    }
}
