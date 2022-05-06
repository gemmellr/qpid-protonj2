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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.qpid.protonj2.client.StreamDelivery;
import org.apache.qpid.protonj2.client.StreamReceiver;
import org.apache.qpid.protonj2.client.StreamReceiverOptions;
import org.apache.qpid.protonj2.client.exceptions.ClientException;
import org.apache.qpid.protonj2.client.exceptions.ClientIllegalStateException;
import org.apache.qpid.protonj2.client.exceptions.ClientOperationTimedOutException;
import org.apache.qpid.protonj2.client.exceptions.ClientResourceRemotelyClosedException;
import org.apache.qpid.protonj2.client.futures.ClientFuture;
import org.apache.qpid.protonj2.engine.IncomingDelivery;
import org.apache.qpid.protonj2.engine.Receiver;
import org.apache.qpid.protonj2.types.messaging.Released;
import org.apache.qpid.protonj2.types.transport.DeliveryState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client implementation of a {@link StreamReceiver}.
 */
public final class ClientStreamReceiver extends ClientLinkType<StreamReceiver, org.apache.qpid.protonj2.engine.Receiver> implements StreamReceiver {

    private static final Logger LOG = LoggerFactory.getLogger(ClientReceiver.class);

    private ClientFuture<StreamReceiver> drainingFuture;
    private ScheduledFuture<?> drainingTimeout;
    private final StreamReceiverOptions options;
    private final Map<ClientFuture<StreamDelivery>, ScheduledFuture<?>> receiveRequests = new LinkedHashMap<>();

    private org.apache.qpid.protonj2.engine.Receiver protonReceiver;

    ClientStreamReceiver(ClientSession session, StreamReceiverOptions options, String receiverId, org.apache.qpid.protonj2.engine.Receiver receiver) {
        super(session, receiverId, options);

        this.options = options;
        this.protonReceiver = receiver.setLinkedResource(this);

        if (options.creditWindow() > 0) {
            protonReceiver.addCredit(options.creditWindow());
        }
    }

    @Override
    public StreamDelivery receive() throws ClientException {
        return receive(-1, TimeUnit.MILLISECONDS);
    }

    @Override
    public StreamDelivery receive(long timeout, TimeUnit unit) throws ClientException {
        checkClosedOrFailed();
        final ClientFuture<StreamDelivery> receive = session.getFutureFactory().createFuture();

        executor.execute(() -> {
            if (notClosedOrFailed(receive)) {
                IncomingDelivery delivery = null;

                // Scan for an unsettled delivery that isn't yet assigned to a client delivery
                // either it is a complete delivery or the initial stage of the next incoming
                for (IncomingDelivery unsettled : protonReceiver.unsettled()) {
                    if (unsettled.getLinkedResource() == null) {
                        delivery = unsettled;
                        break;
                    }
                }

                if (delivery == null) {
                    if (timeout == 0) {
                        receive.complete(null);
                    } else {
                        final ScheduledFuture<?> timeoutFuture;

                        if (timeout > 0) {
                            timeoutFuture = session.getScheduler().schedule(() -> {
                                receiveRequests.remove(receive);
                                receive.complete(null); // Timed receive returns null on failed wait.
                            }, timeout, unit);
                        } else {
                            timeoutFuture = null;
                        }

                        receiveRequests.put(receive, timeoutFuture);
                    }
                } else {
                    receive.complete(new ClientStreamDelivery(this, delivery));
                    asyncReplenishCreditIfNeeded();
                }
            }
        });

        return session.request(this, receive);
    }

    @Override
    public StreamDelivery tryReceive() throws ClientException {
        checkClosedOrFailed();
        return receive(0, TimeUnit.MILLISECONDS);
    }

    @Override
    public StreamReceiver addCredit(int credits) throws ClientException {
        checkClosedOrFailed();
        ClientFuture<StreamReceiver> creditAdded = session.getFutureFactory().createFuture();

        executor.execute(() -> {
            if (notClosedOrFailed(creditAdded)) {
                if (options.creditWindow() != 0) {
                    creditAdded.failed(new ClientIllegalStateException("Cannot add credit when a credit window has been configured"));
                } else if (protonReceiver.isDraining()) {
                    creditAdded.failed(new ClientIllegalStateException("Cannot add credit while a drain is pending"));
                } else {
                    try {
                        protonReceiver.addCredit(credits);
                        creditAdded.complete(this);
                    } catch (Exception ex) {
                        creditAdded.failed(ClientExceptionSupport.createNonFatalOrPassthrough(ex));
                    }
                }
            }
        });

        return session.request(this, creditAdded);
    }

    @Override
    public Future<StreamReceiver> drain() throws ClientException {
        checkClosedOrFailed();
        final ClientFuture<StreamReceiver> drainComplete = session.getFutureFactory().createFuture();

        executor.execute(() -> {
            if (notClosedOrFailed(drainComplete)) {
                if (protonReceiver.isDraining()) {
                    drainComplete.failed(new ClientIllegalStateException("StreamReceiver is already draining"));
                    return;
                }

                try {
                    if (protonReceiver.drain()) {
                        drainingFuture = drainComplete;
                        drainingTimeout = session.scheduleRequestTimeout(drainingFuture, options.drainTimeout(),
                            () -> new ClientOperationTimedOutException("Timed out waiting for remote to respond to drain request"));
                    } else {
                        drainComplete.complete(this);
                    }
                } catch (Exception ex) {
                    drainComplete.failed(ClientExceptionSupport.createNonFatalOrPassthrough(ex));
                }
            }
        });

        return drainComplete;
    }

    @Override
    public long queuedDeliveries() throws ClientException {
        checkClosedOrFailed();
        final ClientFuture<Integer> request = session.getFutureFactory().createFuture();

        executor.execute(() -> {
            if (notClosedOrFailed(request)) {
                int queued = 0;

                // Scan for an unsettled delivery that isn't yet assigned to a client delivery
                // either it is a complete delivery or the initial stage of the next incoming
                for (IncomingDelivery unsettled : protonReceiver.unsettled()) {
                    if (unsettled.getLinkedResource() == null) {
                        queued++;
                    }
                }

                request.complete(queued);
            }
        });

        return session.request(this, request);
    }

    //----- Internal API for the ClientReceiver and other Client objects

    StreamReceiverOptions receiverOptions() {
        return options;
    }

    //----- Handlers for proton receiver events

    private void handleDeliveryRead(IncomingDelivery delivery) {
        LOG.trace("Delivery data was received: {}", delivery);
        if (delivery.getDefaultDeliveryState() == null) {
            delivery.setDefaultDeliveryState(Released.getInstance());
        }

        if (delivery.getLinkedResource() == null) {
            // New delivery that can be sent to a waiting receive caller
            if (!receiveRequests.isEmpty()) {
                Iterator<Entry<ClientFuture<StreamDelivery>, ScheduledFuture<?>>> entries =
                    receiveRequests.entrySet().iterator();

                Entry<ClientFuture<StreamDelivery>, ScheduledFuture<?>> entry = entries.next();
                if (entry.getValue() != null) {
                    entry.getValue().cancel(false);
                }

                try {
                    entry.getKey().complete(new ClientStreamDelivery(this, delivery));
                } finally {
                    entries.remove();
                    asyncReplenishCreditIfNeeded();
                }
            }
        }
    }

    private void handleDeliveryAborted(IncomingDelivery delivery) {
        LOG.trace("Delivery data was aborted: {}", delivery);
        delivery.settle();
        replenishCreditIfNeeded();
    }

    private void handleDeliveryStateRemotelyUpdated(IncomingDelivery delivery) {
        LOG.trace("Delivery remote state was updated: {}", delivery);
    }

    private void handleReceiverCreditUpdated(org.apache.qpid.protonj2.engine.Receiver receiver) {
        LOG.trace("Receiver credit update by remote: {}", receiver);

        if (drainingFuture != null) {
            if (receiver.getCredit() == 0) {
                drainingFuture.complete(this);
                if (drainingTimeout != null) {
                    drainingTimeout.cancel(false);
                    drainingTimeout = null;
                }
            }
        }
    }

    //----- Private implementation details

    void disposition(IncomingDelivery delivery, DeliveryState state, boolean settle) throws ClientException {
        checkClosedOrFailed();
        asyncApplyDisposition(delivery, state, settle);
    }

    private void asyncApplyDisposition(IncomingDelivery delivery, DeliveryState state, boolean settle) throws ClientException {
        executor.execute(() -> {
            session.getTransactionContext().disposition(delivery, state, settle);
            replenishCreditIfNeeded();
        });
    }

    private void asyncReplenishCreditIfNeeded() {
        int creditWindow = options.creditWindow();
        if (creditWindow > 0) {
            executor.execute(() -> replenishCreditIfNeeded());
        }
    }

    private void replenishCreditIfNeeded() {
        int creditWindow = options.creditWindow();
        if (creditWindow > 0) {
            int currentCredit = protonReceiver.getCredit();
            if (currentCredit <= creditWindow * 0.5) {
                //int potentialPrefetch = currentCredit + protonReceiver.unsettled().size();
                int potentialPrefetch = currentCredit +
                    (int)protonReceiver.unsettled().stream().filter((delivery) -> delivery.getLinkedResource() == null).count();

                if (potentialPrefetch <= creditWindow * 0.7) {
                    int additionalCredit = creditWindow - potentialPrefetch;

                    LOG.trace("Consumer granting additional credit: {}", additionalCredit);
                    try {
                        protonReceiver.addCredit(additionalCredit);
                    } catch (Exception ex) {
                        LOG.debug("Error caught during credit top-up", ex);
                    }
                }
            }
        }
    }

    @Override
    protected void linkSpecificCleanupHandler(ClientException failureCause) {
        // If the parent of this sender is a stream session than this sender owns it
        // and must close it when it closes itself to ensure that the resources are
        // cleaned up on the remote for the session.
        session.closeAsync();

        receiveRequests.forEach((future, timeout) -> {
            if (timeout != null) {
                timeout.cancel(false);
            }

            if (failureCause != null) {
                future.failed(failureCause);
            } else {
                future.failed(new ClientResourceRemotelyClosedException("The Stream Receiver has closed"));
            }
        });

        protonReceiver.unsettled().forEach((delivery) -> {
            if (delivery.getLinkedResource() != null) {
                try {
                    delivery.getLinkedResource(ClientStreamDelivery.class).handleReceiverClosed(this);
                } catch (Exception ex) {}
            }
        });

        if (drainingTimeout != null) {
            drainingFuture.failed(
                failureCause != null ? failureCause : new ClientResourceRemotelyClosedException("The Receiver has been closed"));
            drainingTimeout.cancel(false);
            drainingTimeout = null;
        }
    }

    @Override
    protected StreamReceiver self() {
        return this;
    }

    @Override
    protected Receiver protonLink() {
        return protonReceiver;
    }

    @Override
    protected void linkSpecificLocalOpenHandler() {
        protonReceiver.deliveryStateUpdatedHandler(this::handleDeliveryStateRemotelyUpdated)
                      .deliveryReadHandler(this::handleDeliveryRead)
                      .deliveryAbortedHandler(this::handleDeliveryAborted)
                      .creditStateUpdateHandler(this::handleReceiverCreditUpdated);
    }

    @Override
    protected void linkSpecificLocalCloseHandler() {
        // Nothing needed for local close handling
    }

    @Override
    protected void linkSpecificRemoteOpenHandler() {
        replenishCreditIfNeeded();
    }

    @Override
    protected void linkSpecificRemoteCloseHandler() {
        // Nothing needed for remote close handling
    }

    @Override
    protected void recreateLinkForReconnect() {
        int previousCredit = protonReceiver.getCredit() + protonReceiver.unsettled().size();

        if (drainingFuture != null) {
            drainingFuture.complete(this);
            if (drainingTimeout != null) {
                drainingTimeout.cancel(false);
                drainingTimeout = null;
            }
        }

        protonReceiver.localCloseHandler(null);
        protonReceiver.localDetachHandler(null);
        protonReceiver.close();
        protonReceiver = ClientReceiverBuilder.recreateReceiver(session, protonReceiver, options);
        protonReceiver.setLinkedResource(this);
        protonReceiver.addCredit(previousCredit);
    }
}
