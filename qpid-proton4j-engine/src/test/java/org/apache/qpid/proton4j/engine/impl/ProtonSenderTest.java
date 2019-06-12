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
package org.apache.qpid.proton4j.engine.impl;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.qpid.proton4j.amqp.Binary;
import org.apache.qpid.proton4j.amqp.driver.AMQPTestDriver;
import org.apache.qpid.proton4j.amqp.driver.ScriptWriter;
import org.apache.qpid.proton4j.amqp.messaging.Accepted;
import org.apache.qpid.proton4j.amqp.messaging.Modified;
import org.apache.qpid.proton4j.amqp.messaging.Rejected;
import org.apache.qpid.proton4j.amqp.messaging.Released;
import org.apache.qpid.proton4j.amqp.transactions.TransactionalState;
import org.apache.qpid.proton4j.amqp.transport.DeliveryState;
import org.apache.qpid.proton4j.amqp.transport.Role;
import org.apache.qpid.proton4j.buffer.ProtonBuffer;
import org.apache.qpid.proton4j.buffer.ProtonByteBufferAllocator;
import org.apache.qpid.proton4j.engine.Connection;
import org.apache.qpid.proton4j.engine.OutgoingDelivery;
import org.apache.qpid.proton4j.engine.Sender;
import org.apache.qpid.proton4j.engine.Session;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Test the {@link ProtonSender}
 */
public class ProtonSenderTest extends ProtonEngineTestSupport {

    @Test
    public void testSenderOpenAndCloseAreIdempotent() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().respond();
        script.expectDetach().respond();

        Connection connection = engine.start();

        // Default engine should start and return a connection immediately
        assertNotNull(connection);

        connection.open();
        Session session = connection.session();
        session.open();
        Sender sender = session.sender("test");
        sender.open();

        // Should not emit another attach frame
        sender.open();

        sender.close();

        // Should not emit another detach frame
        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testEngineEmitsAttachAfterLocalSenderOpened() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().respond();
        script.expectDetach().respond();

        Connection connection = engine.start();

        // Default engine should start and return a connection immediately
        assertNotNull(connection);

        connection.open();
        Session session = connection.session();
        session.open();
        Sender sender = session.sender("test");
        sender.open();
        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testOpenBeginAttachBeforeRemoteResponds() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        // Create the test driver and link it to the engine for output handling.
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen();
        script.expectBegin();
        script.expectAttach();

        Connection connection = engine.start();

        // Default engine should start and return a connection immediately
        assertNotNull(connection);

        connection.open();
        Session session = connection.session();
        session.open();
        Sender sender = session.sender("test");
        sender.open();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testSenderFireOpenedEventAfterRemoteAttachArrives() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().respond();
        script.expectDetach().respond();

        final AtomicBoolean senderRemotelyOpened = new AtomicBoolean();

        Connection connection = engine.start();

        // Default engine should start and return a connection immediately
        assertNotNull(connection);

        connection.open();
        Session session = connection.session();
        session.open();
        Sender sender = session.sender("test");
        sender.openHandler(result -> {
            senderRemotelyOpened.set(true);
        });
        sender.open();

        assertTrue("Sender remote opened event did not fire", senderRemotelyOpened.get());

        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testOpenAndCloseMultipleSenders() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().withHandle(0).respond();
        script.expectAttach().withHandle(1).respond();
        script.expectDetach().withHandle(1).respond();
        script.expectDetach().withHandle(0).respond();

        Connection connection = engine.start();

        connection.open();
        Session session = connection.session();
        session.open();

        Sender sender1 = session.sender("sender-1");
        sender1.open();
        Sender sender2 = session.sender("sender-2");
        sender2.open();

        // Close in reverse order
        sender2.close();
        sender1.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testSenderFireClosedEventAfterRemoteDetachArrives() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().respond();
        script.expectDetach().respond();

        final AtomicBoolean senderRemotelyOpened = new AtomicBoolean();
        final AtomicBoolean senderRemotelyClosed = new AtomicBoolean();

        Connection connection = engine.start();

        // Default engine should start and return a connection immediately
        assertNotNull(connection);

        connection.open();
        Session session = connection.session();
        session.open();
        Sender sender = session.sender("test");
        sender.openHandler(result -> {
            senderRemotelyOpened.set(true);
        });
        sender.closeHandler(result -> {
            senderRemotelyClosed.set(true);
        });
        sender.open();

        assertTrue("Sender remote opened event did not fire", senderRemotelyOpened.get());

        sender.close();

        assertTrue("Sender remote closed event did not fire", senderRemotelyClosed.get());

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testConnectionSignalsRemoteSenderOpen() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.remoteAttach().withName("sender")
                             .withHandle(0)
                             .withRole(Role.RECEIVER)
                             .withInitialDeliveryCount(0)
                             .onChannel(0).queue();
        script.expectAttach();
        script.expectDetach().respond();

        final AtomicBoolean senderRemotelyOpened = new AtomicBoolean();
        final AtomicReference<Sender> sender = new AtomicReference<>();

        Connection connection = engine.start();

        connection.senderOpenEventHandler(result -> {
            senderRemotelyOpened.set(true);
            sender.set(result);
        });

        // Default engine should start and return a connection immediately
        assertNotNull(connection);

        connection.open();
        Session session = connection.session();
        session.open();

        assertTrue("Sender remote opened event did not fire", senderRemotelyOpened.get());

        sender.get().open();
        sender.get().close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testCannotOpenSenderAfterSessionClosed() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectEnd().respond();

        Connection connection = engine.start();

        // Default engine should start and return a connection immediately
        assertNotNull(connection);

        connection.open();
        Session session = connection.session();
        session.open();

        Sender sender = session.sender("test");

        session.close();

        try {
            sender.open();
            fail("Should not be able to open a link from a closed session.");
        } catch (IllegalStateException ise) {}

        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testCannotOpenSenderAfterSessionRemotelyClosed() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.remoteEnd().queue(); // TODO - Last opened is used here, but a thenEnd() on the expect begin would be more clear

        Connection connection = engine.start();

        // Default engine should start and return a connection immediately
        assertNotNull(connection);

        connection.open();
        Session session = connection.session();
        session.open();

        Sender sender = session.sender("test");

        try {
            sender.open();
            fail("Should not be able to open a link from a remotely closed session.");
        } catch (IllegalStateException ise) {}

        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testGetCurrentDeliveryFromSender() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().withHandle(0).respond();
        script.expectDetach().withHandle(0).respond();

        Connection connection = engine.start();

        connection.open();
        Session session = connection.session();
        session.open();

        Sender sender = session.sender("sender-1");

        sender.open();

        OutgoingDelivery delivery = sender.current();
        assertNotNull(delivery);

        assertFalse(delivery.isAborted());
        assertTrue(delivery.isPartial());
        assertFalse(delivery.isSettled());
        assertFalse(delivery.isRemotelySettled());

        // Always return same delivery until completed.
        assertSame(delivery, sender.current());

        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testSenderGetsCreditOnIncomingFlow() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().withRole(Role.SENDER).respond();
        script.remoteFlow().withDeliveryCount(0)
                           .withLinkCredit(10)
                           .withIncomingWindow(1024)
                           .withOutgoingWindow(10)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).queue();
        script.expectDetach().withHandle(0).respond();

        Connection connection = engine.start();

        connection.open();
        Session session = connection.session();
        session.open();

        Sender sender = session.sender("sender-1");

        assertFalse(sender.isSendable());

        sender.open();

        assertEquals(10, sender.getCredit());
        assertTrue(sender.isSendable());

        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testSendSmallPayloadWhenCreditAvailable() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        final byte [] payloadBuffer = new byte[] {0, 1, 2, 3, 4};

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().withRole(Role.SENDER).respond();
        script.remoteFlow().withDeliveryCount(0)     // TODO - Would be nice to automate filling in these
                           .withLinkCredit(10)       //        these bits using last session opened values
                           .withIncomingWindow(1024) //        plus some defaults or generated values.
                           .withOutgoingWindow(10)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).queue();
        script.expectTransfer().withHandle(0)
                               .withSettled(false)
                               .withState((DeliveryState) null)
                               .withDeliveryId(0)
                               .withDeliveryTag(new byte[] {0})
                               .withPayload(payloadBuffer);
        script.expectDetach().withHandle(0).respond();

        Connection connection = engine.start();

        connection.open();
        Session session = connection.session();
        session.open();

        ProtonBuffer payload = ProtonByteBufferAllocator.DEFAULT.wrap(payloadBuffer);

        Sender sender = session.sender("sender-1");

        assertFalse(sender.isSendable());

        sender.sendableEventHandler(handler -> {
            handler.current().setTag(new byte[] {0}).writeBytes(payload);
        });

        sender.open();
        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testSenderSignalsDeliveryUpdatedOnSettled() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        ProtonBuffer payload = ProtonByteBufferAllocator.DEFAULT.wrap(new byte[] {0, 1, 2, 3, 4});

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().withRole(Role.SENDER).respond();
        script.remoteFlow().withDeliveryCount(0)
                           .withLinkCredit(10)
                           .withIncomingWindow(1024)
                           .withOutgoingWindow(10)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).queue();
        script.expectTransfer().withHandle(0)
                               .withSettled(false)
                               .withState((DeliveryState) null)
                               .withDeliveryId(0)
                               .withDeliveryTag(new byte[] {0})
                               .withPayload(payload.copy());
        script.remoteDisposition().withSettled(true)
                                  .withRole(Role.RECEIVER)
                                  .withState(Accepted.getInstance())
                                  .withFirst(0).queue();
        script.expectDetach().withHandle(0).respond();

        Connection connection = engine.start();

        connection.open();
        Session session = connection.session();
        session.open();

        Sender sender = session.sender("sender-1");

        final AtomicBoolean deliveryUpdatedAndSettled = new AtomicBoolean();
        final AtomicReference<OutgoingDelivery> updatedDelivery = new AtomicReference<>();
        sender.deliveryUpdatedEventHandler(delivery -> {
            if (delivery.isRemotelySettled()) {
                deliveryUpdatedAndSettled.set(true);
            }

            updatedDelivery.set(delivery);
        });

        assertFalse(sender.isSendable());

        sender.sendableEventHandler(handler -> {
            handler.current().setTag(new byte[] {0}).writeBytes(payload);
        });

        sender.open();

        assertTrue("Delivery should have been updated and state settled", deliveryUpdatedAndSettled.get());
        assertEquals(Accepted.getInstance(), updatedDelivery.get().getRemoteState());

        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testOpenSenderBeforeOpenConnection() {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        // Create the connection but don't open, then open a session and a sender and
        // the session begin and sender attach shouldn't go out until the connection
        // is opened locally.
        Connection connection = engine.start();
        Session session = connection.session();
        session.open();
        Sender sender = session.sender("sender");
        sender.open();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond();
        script.expectBegin().respond();
        script.expectAttach().withHandle(0).withName("sender").withRole(Role.SENDER).respond();

        // Now open the connection, expect the Open, Begin, and Attach frames
        connection.open();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testOpenSenderBeforeOpenSession() {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond();

        // Create the connection and open it, then create a session and a sender
        // and observe that the sender doesn't send its attach until the session
        // is opened.
        Connection connection = engine.start();
        connection.open();
        Session session = connection.session();
        Sender sender = session.sender("sender");
        sender.open();

        script.expectBegin().respond();
        script.expectAttach().withHandle(0).withName("sender").withRole(Role.SENDER).respond();

        // Now open the session, expect the Begin, and Attach frames
        session.open();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testSenderDetachAfterEndSent() {
        doTestSenderClosedOrDetachedAfterEndSent(false);
    }

    @Test
    public void testSenderCloseAfterEndSent() {
        doTestSenderClosedOrDetachedAfterEndSent(true);
    }

    public void doTestSenderClosedOrDetachedAfterEndSent(boolean close) {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond();
        script.expectBegin().respond();
        script.expectAttach().withHandle(0).withName("sender").withRole(Role.SENDER).respond();
        script.expectEnd().respond();

        // Create the connection and open it, then create a session and a sender
        // and observe that the sender doesn't send its detach if the session has
        // already been closed.
        Connection connection = engine.start();
        connection.open();
        Session session = connection.session();
        session.open();
        Sender sender = session.sender("sender");
        sender.open();

        // Causes the End frame to be sent
        session.close();

        // The sender should not emit an end as the session was closed which implicitly
        // detached the link.
        if (close) {
            sender.close();
        } else {
            sender.detach();
        }

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testSenderDetachAfterCloseSent() {
        doTestSenderClosedOrDetachedAfterCloseSent(false);
    }

    @Test
    public void testSenderCloseAfterCloseSent() {
        doTestSenderClosedOrDetachedAfterCloseSent(true);
    }

    public void doTestSenderClosedOrDetachedAfterCloseSent(boolean close) {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond();
        script.expectBegin().respond();
        script.expectAttach().withHandle(0).withName("sender").withRole(Role.SENDER).respond();
        script.expectClose().respond();

        // Create the connection and open it, then create a session and a sender
        // and observe that the sender doesn't send its detach if the connection has
        // already been closed.
        Connection connection = engine.start();
        connection.open();
        Session session = connection.session();
        session.open();
        Sender sender = session.sender("sender");
        sender.open();

        // Cause an Close frame to be sent
        connection.close();

        // The sender should not emit an detach as the connection was closed which implicitly
        // detached the link.
        if (close) {
            sender.close();
        } else {
            sender.detach();
        }

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testNoDispositionSentAfterDeliverySettledForSender() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().withRole(Role.SENDER).respond();
        script.remoteFlow().withDeliveryCount(0)
                           .withLinkCredit(10)
                           .withIncomingWindow(1024)
                           .withOutgoingWindow(10)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).queue();
        script.expectTransfer().withHandle(0)
                               .withSettled(false)
                               .withState((DeliveryState) null)
                               .withDeliveryId(0)
                               .withDeliveryTag(new byte[] {0});
        script.expectDisposition().withFirst(0)
                                  .withSettled(true)
                                  .withState(Accepted.getInstance());
        script.expectDetach().withHandle(0).respond();

        Connection connection = engine.start();

        connection.open();
        Session session = connection.session();
        session.open();

        ProtonBuffer payload = ProtonByteBufferAllocator.DEFAULT.wrap(new byte[] {0, 1, 2, 3, 4});

        Sender sender = session.sender("sender-1");

        final AtomicBoolean deliverySentAfterSenable = new AtomicBoolean();
        sender.sendableEventHandler(handler -> {
            handler.current().setTag(new byte[] {0}).writeBytes(payload);
            deliverySentAfterSenable.set(true);
        });

        sender.open();

        assertTrue("Delivery should have been sent after credit arrived", deliverySentAfterSenable.get());

        OutgoingDelivery delivery1 = sender.current();
        delivery1.disposition(Accepted.getInstance(), true);
        OutgoingDelivery delivery2 = sender.current();
        // TODO - Currently we advance to next only after a settle which isn't really workable
        //        and we need an advance type API to allow sends without settling
        assertNotSame(delivery1, delivery2);
        delivery2.disposition(Released.getInstance(), true);

        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testSenderCannotSendAfterConnectionClosed() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().withRole(Role.SENDER).respond();
        script.remoteFlow().withDeliveryCount(0)
                           .withLinkCredit(10)
                           .withIncomingWindow(1024)
                           .withOutgoingWindow(10)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).queue();
        script.expectClose().respond();

        Connection connection = engine.start();

        connection.open();
        Session session = connection.session();
        session.open();

        Sender sender = session.sender("sender-1");

        assertFalse(sender.isSendable());

        OutgoingDelivery delivery = sender.current();
        assertNotNull(delivery);

        sender.open();

        assertEquals(10, sender.getCredit());
        assertTrue(sender.isSendable());

        connection.close();

        assertFalse(sender.isSendable());
        try {
            delivery.writeBytes(ProtonByteBufferAllocator.DEFAULT.wrap(new byte[] { 1 }));
            fail("Should not be able to write to delivery after connection closed.");
        } catch (IllegalStateException ise) {
            // Should not allow writes on past delivery instances after connection closed
        }

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testSendMultiFrameDeliveryAndSingleFrameDeliveryOnSingleSessionFromDifferentSenders() {
        doMultiplexMultiFrameDeliveryOnSingleSessionOutgoingTestImpl(false);
    }

    @Test
    public void testMultipleMultiFrameDeliveriesOnSingleSessionFromDifferentSenders() {
        doMultiplexMultiFrameDeliveryOnSingleSessionOutgoingTestImpl(true);
    }

    private void doMultiplexMultiFrameDeliveryOnSingleSessionOutgoingTestImpl(boolean bothDeliveriesMultiFrame) {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        int contentLength1 = 6000;
        int frameSizeLimit = 4000;
        int contentLength2 = 2000;
        if (bothDeliveriesMultiFrame) {
            contentLength2 = 6000;
        }

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().withMaxFrameSize(frameSizeLimit).respond().withContainerId("driver").withMaxFrameSize(frameSizeLimit);
        script.expectBegin().respond();
        script.expectAttach().withRole(Role.SENDER).respond();
        script.expectAttach().withRole(Role.SENDER).respond();

        Connection connection = engine.start();
        connection.setMaxFrameSize(frameSizeLimit);
        connection.open();
        Session session = connection.session();
        session.open();

        String linkName1 = "Sender1";
        Sender sender1 = session.sender(linkName1);
        sender1.open();

        String linkName2 = "Sender2";
        Sender sender2 = session.sender(linkName2);
        sender2.open();

        final AtomicBoolean sender1MarkedSendable = new AtomicBoolean();
        sender1.sendableEventHandler(handler -> {
            sender1MarkedSendable.set(true);
        });

        final AtomicBoolean sender2MarkedSendable = new AtomicBoolean();
        sender2.sendableEventHandler(handler -> {
            sender2MarkedSendable.set(true);
        });

        script.remoteFlow().withHandle(0)
                           .withDeliveryCount(0)
                           .withLinkCredit(10)
                           .withIncomingWindow(1024)
                           .withOutgoingWindow(10)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).now();
        script.remoteFlow().withHandle(1)
                           .withDeliveryCount(0)
                           .withLinkCredit(10)
                           .withIncomingWindow(1024)
                           .withOutgoingWindow(10)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).now();

        assertTrue("Sender 1 should now be sendable", sender1MarkedSendable.get());
        assertTrue("Sender 2 should now be sendable", sender2MarkedSendable.get());

        // Frames are not multiplexed for large deliveries as we write the full
        // writable portion out when a write is called.

        script.expectTransfer().withHandle(0)
                               .withSettled(true)
                               .withState(Accepted.getInstance())
                               .withDeliveryId(0)
                               .withMore(true)
                               .withDeliveryTag(new byte[] {1});
        script.expectTransfer().withHandle(0)
                               .withSettled(true)
                               .withState(Accepted.getInstance())
                               .withDeliveryId(0)
                               .withMore(false)
                               .withDeliveryTag(new byte[] {1});

        script.expectTransfer().withHandle(1)
                               .withSettled(true)
                               .withState(Accepted.getInstance())
                               .withDeliveryId(1)
                               .withMore(bothDeliveriesMultiFrame)
                               .withDeliveryTag(new byte[] {2});
        if (bothDeliveriesMultiFrame) {
            script.expectTransfer().withHandle(1)
                                   .withSettled(true)
                                   .withState(Accepted.getInstance())
                                   .withDeliveryId(1)
                                   .withMore(false)
                                   .withDeliveryTag(new byte[] {2});
        }

        ProtonBuffer messageContent1 = createContentBuffer(contentLength1);
        OutgoingDelivery delivery1 = sender1.current();
        delivery1.setTag(new byte[] { 1 });
        delivery1.disposition(Accepted.getInstance(), true);
        delivery1.writeBytes(messageContent1);

        ProtonBuffer messageContent2 = createContentBuffer(contentLength2);
        OutgoingDelivery delivery2 = sender2.current();
        delivery2.setTag(new byte[] { 2 });
        delivery2.disposition(Accepted.getInstance(), true);
        delivery2.writeBytes(messageContent2);

        script.expectClose().respond();
        connection.close();

        driver.assertScriptComplete();
        assertNull(failure);
    }

    @Test
    public void testMaxFrameSizeOfPeerHasEffect() {
        doMaxFrameSizeTestImpl(0, 0, 5700, 1);
        doMaxFrameSizeTestImpl(1024, 0, 5700, 6);
    }

    @Test
    public void testMaxFrameSizeOutgoingFrameSizeLimitHasEffect() {
        doMaxFrameSizeTestImpl(0, 512, 5700, 12);
        doMaxFrameSizeTestImpl(1024, 512, 5700, 12);
        doMaxFrameSizeTestImpl(1024, 2048, 5700, 6);
    }

    void doMaxFrameSizeTestImpl(int remoteMaxFrameSize, int outboundFrameSizeLimit, int contentLength, int expectedNumFrames) {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        if (outboundFrameSizeLimit == 0) {
            if (remoteMaxFrameSize == 0) {
                script.expectOpen().respond();
            } else {
                script.expectOpen().respond().withMaxFrameSize(remoteMaxFrameSize);
            }
        } else {
            if (remoteMaxFrameSize == 0) {
                script.expectOpen().withMaxFrameSize(outboundFrameSizeLimit).respond();
            } else {
                script.expectOpen().withMaxFrameSize(outboundFrameSizeLimit)
                                   .respond()
                                   .withMaxFrameSize(remoteMaxFrameSize);
            }
        }
        script.expectBegin().respond();
        script.expectAttach().withRole(Role.SENDER).respond();

        Connection connection = engine.start();
        if (outboundFrameSizeLimit != 0) {
            connection.setMaxFrameSize(outboundFrameSizeLimit);
        }
        connection.open();
        Session session = connection.session();
        session.open();

        String linkName = "mySender";
        Sender sender = session.sender(linkName);
        sender.open();

        final AtomicBoolean senderMarkedSendable = new AtomicBoolean();
        sender.sendableEventHandler(handler -> {
            senderMarkedSendable.set(true);
        });

        script.remoteFlow().withHandle(0)
                           .withDeliveryCount(0)
                           .withLinkCredit(50)
                           .withIncomingWindow(65535)
                           .withOutgoingWindow(65535)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).now();

        assertTrue("Sender should now be sendable", senderMarkedSendable.get());

        // This calculation isn't entirely precise, there is some added performative/frame overhead not
        // accounted for...but values are chosen to work, and verified here.
        final int frameCount;
        if(remoteMaxFrameSize == 0 && outboundFrameSizeLimit == 0) {
            frameCount = 1;
        } else if(remoteMaxFrameSize == 0 && outboundFrameSizeLimit != 0) {
            frameCount = (int) Math.ceil((double)contentLength / (double) outboundFrameSizeLimit);
        } else {
            int effectiveMaxFrameSize;
            if(outboundFrameSizeLimit != 0) {
                effectiveMaxFrameSize = Math.min(outboundFrameSizeLimit, remoteMaxFrameSize);
            } else {
                effectiveMaxFrameSize = remoteMaxFrameSize;
            }

            frameCount = (int) Math.ceil((double)contentLength / (double) effectiveMaxFrameSize);
        }

        assertEquals("Unexpected number of frames calculated", expectedNumFrames, frameCount);

        for (int i = 1; i <= expectedNumFrames; ++i) {
            script.expectTransfer().withHandle(0)
                                   .withSettled(true)
                                   .withState(Accepted.getInstance())
                                   .withDeliveryId(0)
                                   .withMore(i != expectedNumFrames ? true : false)
                                   .withDeliveryTag(notNullValue())
                                   .withPayload(notNullValue(ProtonBuffer.class));
        }

        ProtonBuffer messageContent = createContentBuffer(contentLength);
        OutgoingDelivery delivery = sender.current();
        delivery.setTag(new byte[] { 1 });
        delivery.disposition(Accepted.getInstance(), true);
        delivery.writeBytes(messageContent);

        script.expectClose().respond();
        connection.close();

        driver.assertScriptComplete();
        assertNull(failure);
    }

    @Test
    public void testAbortInProgressDelivery() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        ProtonBuffer payload = ProtonByteBufferAllocator.DEFAULT.wrap(new byte[] {0, 1, 2, 3, 4});

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().withRole(Role.SENDER).respond();
        script.remoteFlow().withDeliveryCount(0)
                           .withLinkCredit(10)
                           .withIncomingWindow(1024)
                           .withOutgoingWindow(10)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).queue();
        script.expectTransfer().withHandle(0)
                               .withMore(true)
                               .withSettled(false)
                               .withState((DeliveryState) null)
                               .withDeliveryId(0)
                               .withDeliveryTag(new byte[] {0})
                               .withPayload(payload.copy());
        script.expectTransfer().withHandle(0)
                               .withState((DeliveryState) null)
                               .withDeliveryId(0)
                               .withDeliveryTag(new byte[] {0})
                               .withAborted(true)
                               .withSettled(true)
                               .withMore(false)
                               .withPayload(nullValue(ProtonBuffer.class));
        script.expectDetach().withHandle(0).respond();

        Connection connection = engine.start();

        connection.open();
        Session session = connection.session();
        session.open();

        Sender sender = session.sender("sender-1");
        sender.open();

        final AtomicBoolean senderMarkedSendable = new AtomicBoolean();
        sender.sendableEventHandler(handler -> {
            senderMarkedSendable.set(true);
        });

        OutgoingDelivery delivery = sender.current();
        assertNotNull(delivery);

        delivery.setTag(new byte[] {0});
        delivery.streamBytes(payload);
        delivery.abort();

        assertTrue(delivery.isAborted());
        assertFalse(delivery.isPartial());
        assertTrue(delivery.isSettled());

        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testAbortAlreadyAbortedDelivery() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        ProtonBuffer payload = ProtonByteBufferAllocator.DEFAULT.wrap(new byte[] {0, 1, 2, 3, 4});

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().withRole(Role.SENDER).respond();
        script.remoteFlow().withDeliveryCount(0)
                           .withLinkCredit(10)
                           .withIncomingWindow(1024)
                           .withOutgoingWindow(10)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).queue();
        script.expectTransfer().withHandle(0)
                               .withMore(true)
                               .withSettled(false)
                               .withState((DeliveryState) null)
                               .withDeliveryId(0)
                               .withDeliveryTag(new byte[] {0})
                               .withPayload(payload.copy());
        script.expectTransfer().withHandle(0)
                               .withState((DeliveryState) null)
                               .withDeliveryId(0)
                               .withDeliveryTag(new byte[] {0})
                               .withAborted(true)
                               .withSettled(true)
                               .withMore(false)
                               .withPayload(nullValue(ProtonBuffer.class));
        script.expectDetach().withHandle(0).respond();

        Connection connection = engine.start();

        connection.open();
        Session session = connection.session();
        session.open();

        Sender sender = session.sender("sender-1");
        sender.open();

        final AtomicBoolean senderMarkedSendable = new AtomicBoolean();
        sender.sendableEventHandler(handler -> {
            senderMarkedSendable.set(true);
        });

        OutgoingDelivery delivery = sender.current();
        assertNotNull(delivery);

        delivery.setTag(new byte[] {0});
        delivery.streamBytes(payload);
        delivery.abort();

        assertTrue(delivery.isAborted());
        assertFalse(delivery.isPartial());
        assertTrue(delivery.isSettled());

        // Second abort attempt should not error out or trigger additional frames
        delivery.abort();

        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testAbortOnDeliveryThatHasNoWritesIsNoOp() throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().withRole(Role.SENDER).respond();
        script.remoteFlow().withDeliveryCount(0)
                           .withLinkCredit(10)
                           .withIncomingWindow(1024)
                           .withOutgoingWindow(10)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).queue();
        script.expectDetach().withHandle(0).respond();

        Connection connection = engine.start();

        connection.open();
        Session session = connection.session();
        session.open();

        Sender sender = session.sender("sender-1");
        sender.open();

        final AtomicBoolean senderMarkedSendable = new AtomicBoolean();
        sender.sendableEventHandler(handler -> {
            senderMarkedSendable.set(true);
        });

        OutgoingDelivery delivery = sender.current();
        assertNotNull(delivery);

        delivery.setTag(new byte[] {0});
        delivery.abort();

        assertSame(delivery, sender.current());
        assertFalse(delivery.isAborted());
        assertTrue(delivery.isPartial());
        assertFalse(delivery.isSettled());

        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testSettleTransferWithNullDisposition() throws Exception {
        doTestSettleTransferWithSpecifiedOutcome(null);
    }

    @Test
    public void testSettleTransferWithAcceptedDisposition() throws Exception {
        doTestSettleTransferWithSpecifiedOutcome(Accepted.getInstance());
    }

    @Test
    public void testSettleTransferWithReleasedDisposition() throws Exception {
        doTestSettleTransferWithSpecifiedOutcome(Released.getInstance());
    }

    @Test
    public void testSettleTransferWithRejectedDisposition() throws Exception {
        // TODO - Seems to be an issue with ErrorCondition matching
        // doTestSettleTransferWithSpecifiedOutcome(new Rejected().setError(new ErrorCondition(AmqpError.DECODE_ERROR, "test")));
        doTestSettleTransferWithSpecifiedOutcome(new Rejected());
    }

    @Test
    public void testSettleTransferWithModifiedDisposition() throws Exception {
        // TODO - Matcher has an issue with false types mapped to null by the codec.
        // doTestSettleTransferWithSpecifiedOutcome(new Modified().setDeliveryFailed(true).setUndeliverableHere(false));
        doTestSettleTransferWithSpecifiedOutcome(new Modified().setDeliveryFailed(true).setUndeliverableHere(true));
    }

    @Ignore("Issue with matching TransactionState in test driver fails the test")
    @Test
    public void testSettleTransferWithTransactionalDisposition() throws Exception {
        doTestSettleTransferWithSpecifiedOutcome(new TransactionalState().setTxnId(new Binary(new byte[] {1})).setOutcome(Accepted.getInstance()));
    }

    private void doTestSettleTransferWithSpecifiedOutcome(DeliveryState state) throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().withRole(Role.SENDER).respond();
        script.remoteFlow().withDeliveryCount(0)
                           .withLinkCredit(10)
                           .withIncomingWindow(1024)
                           .withOutgoingWindow(10)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).queue();
        script.expectTransfer().withHandle(0)
                               .withSettled(false)
                               .withState((DeliveryState) null)
                               .withDeliveryId(0)
                               .withDeliveryTag(new byte[] {0});
        script.expectDisposition().withFirst(0)
                                  .withSettled(true)
                                  .withState(state);
        script.expectDetach().withHandle(0).respond();

        Connection connection = engine.start();

        connection.open();
        Session session = connection.session();
        session.open();

        ProtonBuffer payload = ProtonByteBufferAllocator.DEFAULT.wrap(new byte[] {0, 1, 2, 3, 4});

        Sender sender = session.sender("sender-1");

        final AtomicBoolean deliverySentAfterSenable = new AtomicBoolean();
        sender.sendableEventHandler(handler -> {
            handler.current().setTag(new byte[] {0}).writeBytes(payload);
            deliverySentAfterSenable.set(true);
        });

        sender.open();

        assertTrue("Delivery should have been sent after credit arrived", deliverySentAfterSenable.get());

        OutgoingDelivery delivery = sender.current();
        assertNotNull(delivery);
        delivery.disposition(state, true);

        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }

    @Test
    public void testAttemptedSecondDispostionOnAlreadySettledDeliveryNull() throws Exception {
        doTestAttemptedSecondDispostionOnAlreadySettledDelivery(Accepted.getInstance(), null);
    }

    @Test
    public void testAttemptedSecondDispostionOnAlreadySettledDeliveryReleased() throws Exception {
        doTestAttemptedSecondDispostionOnAlreadySettledDelivery(Accepted.getInstance(), Released.getInstance());
    }

    @Test
    public void testAttemptedSecondDispostionOnAlreadySettledDeliveryModiified() throws Exception {
        doTestAttemptedSecondDispostionOnAlreadySettledDelivery(Released.getInstance(), new Modified().setDeliveryFailed(true));
    }

    @Test
    public void testAttemptedSecondDispostionOnAlreadySettledDeliveryRejected() throws Exception {
        doTestAttemptedSecondDispostionOnAlreadySettledDelivery(Released.getInstance(), new Rejected());
    }

    @Test
    public void testAttemptedSecondDispostionOnAlreadySettledDeliveryTransactional() throws Exception {
        doTestAttemptedSecondDispostionOnAlreadySettledDelivery(Released.getInstance(), new TransactionalState().setOutcome(Accepted.getInstance()));
    }

    private void doTestAttemptedSecondDispostionOnAlreadySettledDelivery(DeliveryState first, DeliveryState second) throws Exception {
        ProtonEngine engine = ProtonEngineFactory.createDefaultEngine();
        engine.errorHandler(result -> failure = result);
        AMQPTestDriver driver = new AMQPTestDriver(engine);
        engine.outputConsumer(driver);
        ScriptWriter script = driver.createScriptWriter();

        script.expectAMQPHeader().respondWithAMQPHeader();
        script.expectOpen().respond().withContainerId("driver");
        script.expectBegin().respond();
        script.expectAttach().withRole(Role.SENDER).respond();
        script.remoteFlow().withDeliveryCount(0)
                           .withLinkCredit(10)
                           .withIncomingWindow(1024)
                           .withOutgoingWindow(10)
                           .withNextIncomingId(0)
                           .withNextOutgoingId(1).queue();
        script.expectTransfer().withHandle(0)
                               .withSettled(false)
                               .withState((DeliveryState) null)
                               .withDeliveryId(0)
                               .withDeliveryTag(new byte[] {0});
        script.expectDisposition().withFirst(0)
                                  .withSettled(true)
                                  .withState(first);
        script.expectDetach().withHandle(0).respond();

        Connection connection = engine.start();

        connection.open();
        Session session = connection.session();
        session.open();

        ProtonBuffer payload = ProtonByteBufferAllocator.DEFAULT.wrap(new byte[] {0, 1, 2, 3, 4});

        Sender sender = session.sender("sender-1");

        final AtomicBoolean deliverySentAfterSenable = new AtomicBoolean();
        sender.sendableEventHandler(handler -> {
            handler.current().setTag(new byte[] {0}).writeBytes(payload);
            deliverySentAfterSenable.set(true);
        });

        sender.open();

        assertTrue("Delivery should have been sent after credit arrived", deliverySentAfterSenable.get());

        OutgoingDelivery delivery = sender.current();
        assertNotNull(delivery);
        delivery.disposition(first, true);

        // A second attempt at the same outcome should result in no action.
        delivery.disposition(first, true);

        try {
            delivery.disposition(second, true);
            fail("Should not be able to update outcome on already setttled delivery");
        } catch (IllegalStateException ise) {
            // Expected
        }

        sender.close();

        driver.assertScriptComplete();

        assertNull(failure);
    }
}
