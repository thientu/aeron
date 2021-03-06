/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver;

import io.aeron.ChannelUri;
import io.aeron.CommonContext;
import io.aeron.driver.MediaDriver.Context;
import io.aeron.driver.buffer.RawLog;
import io.aeron.driver.buffer.RawLogFactory;
import io.aeron.driver.cmd.DriverConductorCmd;
import io.aeron.driver.exceptions.ControlProtocolException;
import io.aeron.driver.media.ReceiveChannelEndpoint;
import io.aeron.driver.media.SendChannelEndpoint;
import io.aeron.driver.media.UdpChannel;
import io.aeron.driver.status.*;
import io.aeron.status.ChannelEndpointStatus;
import org.agrona.*;
import org.agrona.concurrent.*;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.agrona.concurrent.status.*;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import static io.aeron.ErrorCode.*;
import static io.aeron.driver.Configuration.*;
import static io.aeron.driver.PublicationParams.*;
import static io.aeron.driver.status.SystemCounterDescriptor.*;
import static io.aeron.logbuffer.LogBufferDescriptor.*;
import static io.aeron.protocol.DataHeaderFlyweight.createDefaultHeader;
import static org.agrona.collections.ArrayListUtil.fastUnorderedRemove;

/**
 * Driver Conductor that takes commands from publishers and subscribers and orchestrates the media driver.
 */
public class DriverConductor implements Agent, Consumer<DriverConductorCmd>
{
    private final long timerIntervalNs;
    private final long imageLivenessTimeoutNs;
    private final long clientLivenessTimeoutNs;
    private final long publicationUnblockTimeoutNs;
    private final long statusMessageTimeoutNs;
    private long timeOfLastToDriverPositionChangeNs;
    private long timeOfLastTimerCheckNs;
    private long lastConsumerCommandPosition;
    private long clockUpdateDeadlineNs;
    private int nextSessionId = BitUtil.generateRandomisedId();

    private final Context context;
    private final RawLogFactory rawLogFactory;
    private final ReceiverProxy receiverProxy;
    private final SenderProxy senderProxy;
    private final ClientProxy clientProxy;
    private final RingBuffer toDriverCommands;
    private final ClientCommandAdapter clientCommandAdapter;
    private final ManyToOneConcurrentArrayQueue<DriverConductorCmd> driverCmdQueue;
    private final HashMap<String, SendChannelEndpoint> sendChannelEndpointByChannelMap = new HashMap<>();
    private final HashMap<String, ReceiveChannelEndpoint> receiveChannelEndpointByChannelMap = new HashMap<>();
    private final ArrayList<NetworkPublication> networkPublications = new ArrayList<>();
    private final ArrayList<IpcPublication> ipcPublications = new ArrayList<>();
    private final ArrayList<PublicationImage> publicationImages = new ArrayList<>();
    private final ArrayList<PublicationLink> publicationLinks = new ArrayList<>();
    private final ArrayList<SubscriptionLink> subscriptionLinks = new ArrayList<>();
    private final ArrayList<CounterLink> counterLinks = new ArrayList<>();
    private final ArrayList<AeronClient> clients = new ArrayList<>();
    private final EpochClock epochClock;
    private final NanoClock nanoClock;
    private final CachedEpochClock cachedEpochClock;
    private final CachedNanoClock cachedNanoClock;
    private final CountersManager countersManager;
    private final AtomicCounter clientKeepAlives;
    private final NetworkPublicationThreadLocals networkPublicationThreadLocals = new NetworkPublicationThreadLocals();
    private final MutableDirectBuffer tempBuffer;

    public DriverConductor(final Context ctx)
    {
        context = ctx;
        timerIntervalNs = ctx.timerIntervalNs();
        imageLivenessTimeoutNs = ctx.imageLivenessTimeoutNs();
        clientLivenessTimeoutNs = ctx.clientLivenessTimeoutNs();
        publicationUnblockTimeoutNs = ctx.publicationUnblockTimeoutNs();
        statusMessageTimeoutNs = ctx.statusMessageTimeoutNs();
        driverCmdQueue = ctx.driverCommandQueue();
        receiverProxy = ctx.receiverProxy();
        senderProxy = ctx.senderProxy();
        rawLogFactory = ctx.rawLogBuffersFactory();
        epochClock = ctx.epochClock();
        nanoClock = ctx.nanoClock();
        cachedEpochClock = ctx.cachedEpochClock();
        cachedNanoClock = ctx.cachedNanoClock();
        toDriverCommands = ctx.toDriverCommands();
        clientProxy = ctx.clientProxy();
        tempBuffer = ctx.tempBuffer();

        countersManager = context.countersManager();
        clientKeepAlives = context.systemCounters().get(CLIENT_KEEP_ALIVES);

        clientCommandAdapter = new ClientCommandAdapter(
            context.systemCounters().get(ERRORS),
            ctx.errorHandler(),
            toDriverCommands,
            clientProxy,
            this);

        final long nowNs = nanoClock.nanoTime();
        timeOfLastTimerCheckNs = nowNs;
        timeOfLastToDriverPositionChangeNs = nowNs;
        lastConsumerCommandPosition = toDriverCommands.consumerPosition();
    }

    public void onClose()
    {
        networkPublications.forEach(NetworkPublication::close);
        publicationImages.forEach(PublicationImage::close);
        ipcPublications.forEach(IpcPublication::close);
    }

    public String roleName()
    {
        return "driver-conductor";
    }

    public void accept(final DriverConductorCmd cmd)
    {
        cmd.execute(this);
    }

    public int doWork()
    {
        int workCount = 0;

        workCount += clientCommandAdapter.receive();
        workCount += driverCmdQueue.drain(this, Configuration.COMMAND_DRAIN_LIMIT);

        final long nowNs = nanoClock.nanoTime();
        updateClocks(nowNs);
        workCount += processTimers(nowNs);

        final ArrayList<PublicationImage> publicationImages = this.publicationImages;
        for (int i = 0, size = publicationImages.size(); i < size; i++)
        {
            publicationImages.get(i).trackRebuild(nowNs, statusMessageTimeoutNs);
        }

        final ArrayList<NetworkPublication> networkPublications = this.networkPublications;
        for (int i = 0, size = networkPublications.size(); i < size; i++)
        {
            workCount += networkPublications.get(i).updatePublisherLimit();
        }

        final ArrayList<IpcPublication> ipcPublications = this.ipcPublications;
        for (int i = 0, size = ipcPublications.size(); i < size; i++)
        {
            workCount += ipcPublications.get(i).updatePublishersLimit();
        }

        return workCount;
    }

    public void onCreatePublicationImage(
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final int activeTermId,
        final int initialTermOffset,
        final int termBufferLength,
        final int senderMtuLength,
        final InetSocketAddress controlAddress,
        final InetSocketAddress sourceAddress,
        final ReceiveChannelEndpoint channelEndpoint)
    {
        Configuration.validateMtuLength(senderMtuLength);
        Configuration.validateInitialWindowLength(context.initialWindowLength(), senderMtuLength);

        final UdpChannel udpChannel = channelEndpoint.udpChannel();
        final String channel = udpChannel.originalUriString();
        final long registrationId = toDriverCommands.nextCorrelationId();

        final long joinPosition = computePosition(
            activeTermId, initialTermOffset, Integer.numberOfTrailingZeros(termBufferLength), initialTermId);

        final List<SubscriberPosition> subscriberPositions = createSubscriberPositions(
            sessionId, streamId, channelEndpoint, joinPosition);

        if (subscriberPositions.size() > 0)
        {
            final RawLog rawLog = newPublicationImageLog(
                sessionId, streamId, initialTermId, termBufferLength, senderMtuLength, udpChannel, registrationId);

            final CongestionControl congestionControl = context.congestionControlSupplier().newInstance(
                registrationId,
                udpChannel,
                streamId,
                sessionId,
                termBufferLength,
                senderMtuLength,
                cachedNanoClock,
                context,
                countersManager);

            final PublicationImage image = new PublicationImage(
                registrationId,
                imageLivenessTimeoutNs,
                channelEndpoint,
                controlAddress,
                sessionId,
                streamId,
                initialTermId,
                activeTermId,
                initialTermOffset,
                rawLog,
                udpChannel.isMulticast() ? NAK_MULTICAST_DELAY_GENERATOR : NAK_UNICAST_DELAY_GENERATOR,
                positionArray(subscriberPositions),
                ReceiverHwm.allocate(tempBuffer, countersManager, registrationId, sessionId, streamId, channel),
                ReceiverPos.allocate(tempBuffer, countersManager, registrationId, sessionId, streamId, channel),
                nanoClock,
                cachedNanoClock,
                cachedEpochClock,
                context.systemCounters(),
                sourceAddress,
                congestionControl,
                context.lossReport(),
                subscriberPositions.get(0).subscription().isReliable());

            publicationImages.add(image);
            receiverProxy.newPublicationImage(channelEndpoint, image);

            for (int i = 0, size = subscriberPositions.size(); i < size; i++)
            {
                final SubscriberPosition position = subscriberPositions.get(i);

                position.addLink(image);

                clientProxy.onAvailableImage(
                    registrationId,
                    streamId,
                    sessionId,
                    position.subscription().registrationId(),
                    position.positionCounterId(),
                    rawLog.fileName(),
                    generateSourceIdentity(sourceAddress));
            }
        }
    }

    public void onChannelEndpointError(final long statusIndicatorId, final Exception error)
    {
        final String errorMessage = error.getClass().getSimpleName() + " : " + error.getMessage();
        clientProxy.onError(statusIndicatorId, CHANNEL_ENDPOINT_ERROR, errorMessage);
    }

    SendChannelEndpoint senderChannelEndpoint(final UdpChannel channel)
    {
        return sendChannelEndpointByChannelMap.get(channel.canonicalForm());
    }

    ReceiveChannelEndpoint receiverChannelEndpoint(final UdpChannel channel)
    {
        return receiveChannelEndpointByChannelMap.get(channel.canonicalForm());
    }

    IpcPublication getSharedIpcPublication(final long streamId)
    {
        return findSharedIpcPublication(ipcPublications, streamId);
    }

    IpcPublication getIpcPublication(final long registrationId)
    {
        for (int i = 0, size = ipcPublications.size(); i < size; i++)
        {
            final IpcPublication publication = ipcPublications.get(i);
            if (publication.registrationId() == registrationId)
            {
                return publication;
            }
        }

        return null;
    }

    void onAddNetworkPublication(
        final String channel,
        final int streamId,
        final long correlationId,
        final long clientId,
        final boolean isExclusive)
    {
        final UdpChannel udpChannel = UdpChannel.parse(channel);
        final ChannelUri channelUri = udpChannel.channelUri();
        final PublicationParams params = getPublicationParams(context, channelUri, isExclusive, false);
        validateMtuForMaxMessage(params, isExclusive);

        final SendChannelEndpoint channelEndpoint = getOrCreateSendChannelEndpoint(udpChannel);

        NetworkPublication publication = null;
        if (!isExclusive)
        {
            publication = findPublication(networkPublications, streamId, channelEndpoint);
        }
        else if (params.hasSessionId)
        {
            confirmNetworkSessionIdNotInUse(networkPublications, params.sessionId);
        }

        if (null == publication)
        {
            publication = newNetworkPublication(
                correlationId, streamId, channel, udpChannel, channelEndpoint, params, isExclusive);
        }
        else
        {
            confirmMatch(channelUri, params, publication.rawLog(), publication.sessionId());
        }

        publicationLinks.add(new PublicationLink(correlationId, publication, getOrAddClient(clientId)));

        clientProxy.onPublicationReady(
            correlationId,
            publication.registrationId(),
            streamId,
            publication.sessionId(),
            publication.rawLog().fileName(),
            publication.publisherLimitId(),
            channelEndpoint.statusIndicatorCounterId(),
            isExclusive);
    }

    void cleanupSpies(final NetworkPublication publication)
    {
        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink link = subscriptionLinks.get(i);

            if (link.isLinked(publication))
            {
                clientProxy.onUnavailableImage(
                    publication.registrationId(), link.registrationId(), publication.streamId(), publication.channel());
                subscriptionLinks.get(i).unlink(publication);
            }
        }
    }

    void cleanupPublication(final NetworkPublication publication)
    {
        senderProxy.removeNetworkPublication(publication);

        final SendChannelEndpoint channelEndpoint = publication.channelEndpoint();
        if (channelEndpoint.shouldBeClosed())
        {
            channelEndpoint.closeStatusIndicator();
            sendChannelEndpointByChannelMap.remove(channelEndpoint.udpChannel().canonicalForm());
            senderProxy.closeSendChannelEndpoint(channelEndpoint);
        }
    }

    void cleanupSubscriptionLink(final SubscriptionLink subscription)
    {
        final ReceiveChannelEndpoint channelEndpoint = subscription.channelEndpoint();

        if (null != channelEndpoint)
        {
            if (0 == channelEndpoint.decRefToStream(subscription.streamId()))
            {
                receiverProxy.removeSubscription(channelEndpoint, subscription.streamId());
            }

            if (channelEndpoint.shouldBeClosed())
            {
                channelEndpoint.closeStatusIndicator();
                receiveChannelEndpointByChannelMap.remove(channelEndpoint.udpChannel().canonicalForm());
                receiverProxy.closeReceiveChannelEndpoint(channelEndpoint);
            }
        }
    }

    void transitionToLinger(final PublicationImage image)
    {
        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink link = subscriptionLinks.get(i);

            if (link.isLinked(image))
            {
                clientProxy.onUnavailableImage(
                    image.correlationId(), link.registrationId(), image.streamId(), image.channel());
            }
        }

        receiverProxy.removeCoolDown(image.channelEndpoint(), image.sessionId(), image.streamId());
    }

    void transitionToLinger(final IpcPublication publication)
    {
        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink link = subscriptionLinks.get(i);

            if (link.isLinked(publication))
            {
                clientProxy.onUnavailableImage(
                    publication.registrationId(),
                    link.registrationId(),
                    publication.streamId(),
                    CommonContext.IPC_CHANNEL);
            }
        }
    }

    void cleanupImage(final PublicationImage image)
    {
        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            subscriptionLinks.get(i).unlink(image);
        }
    }

    void cleanupIpcPublication(final IpcPublication publication)
    {
        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            subscriptionLinks.get(i).unlink(publication);
        }
    }

    void onAddIpcPublication(
        final String channel,
        final int streamId,
        final long correlationId,
        final long clientId,
        final boolean isExclusive)
    {
        final IpcPublication ipcPublication = getOrAddIpcPublication(correlationId, streamId, channel, isExclusive);
        publicationLinks.add(new PublicationLink(correlationId, ipcPublication, getOrAddClient(clientId)));

        clientProxy.onPublicationReady(
            correlationId,
            ipcPublication.registrationId(),
            streamId,
            ipcPublication.sessionId(),
            ipcPublication.rawLog().fileName(),
            ipcPublication.publisherLimitId(),
            ChannelEndpointStatus.NO_ID_ALLOCATED,
            isExclusive);

        linkIpcSubscriptions(ipcPublication);
    }

    void onRemovePublication(final long registrationId, final long correlationId)
    {
        PublicationLink publicationLink = null;
        final ArrayList<PublicationLink> publicationLinks = this.publicationLinks;
        for (int i = 0, size = publicationLinks.size(), lastIndex = size - 1; i < size; i++)
        {
            final PublicationLink publication = publicationLinks.get(i);
            if (registrationId == publication.registrationId())
            {
                publicationLink = publication;
                fastUnorderedRemove(publicationLinks, i, lastIndex);
                break;
            }
        }

        if (null == publicationLink)
        {
            throw new ControlProtocolException(UNKNOWN_PUBLICATION, "Unknown publication: " + registrationId);
        }

        publicationLink.close();

        clientProxy.operationSucceeded(correlationId);
    }

    void onAddDestination(final long registrationId, final String destinationChannel, final long correlationId)
    {
        SendChannelEndpoint sendChannelEndpoint = null;

        for (int i = 0, size = networkPublications.size(); i < size; i++)
        {
            final NetworkPublication publication = networkPublications.get(i);

            if (registrationId == publication.registrationId())
            {
                sendChannelEndpoint = publication.channelEndpoint();
                break;
            }
        }

        if (null == sendChannelEndpoint)
        {
            throw new ControlProtocolException(UNKNOWN_PUBLICATION, "Unknown publication: " + registrationId);
        }

        sendChannelEndpoint.validateAllowsManualControl();

        final ChannelUri channelUri = ChannelUri.parse(destinationChannel);
        final InetSocketAddress dstAddress = UdpChannel.destinationAddress(channelUri);
        senderProxy.addDestination(sendChannelEndpoint, dstAddress);
        clientProxy.operationSucceeded(correlationId);
    }

    void onRemoveDestination(final long registrationId, final String destinationChannel, final long correlationId)
    {
        SendChannelEndpoint sendChannelEndpoint = null;

        for (int i = 0, size = networkPublications.size(); i < size; i++)
        {
            final NetworkPublication publication = networkPublications.get(i);

            if (registrationId == publication.registrationId())
            {
                sendChannelEndpoint = publication.channelEndpoint();
                break;
            }
        }

        if (null == sendChannelEndpoint)
        {
            throw new ControlProtocolException(UNKNOWN_PUBLICATION, "Unknown publication: " + registrationId);
        }

        sendChannelEndpoint.validateAllowsManualControl();

        final ChannelUri channelUri = ChannelUri.parse(destinationChannel);
        final InetSocketAddress dstAddress = UdpChannel.destinationAddress(channelUri);
        senderProxy.removeDestination(sendChannelEndpoint, dstAddress);
        clientProxy.operationSucceeded(correlationId);
    }

    void onAddNetworkSubscription(
        final String channel, final int streamId, final long registrationId, final long clientId)
    {
        final UdpChannel udpChannel = UdpChannel.parse(channel);
        final SubscriptionParams params = SubscriptionParams.getSubscriptionParams(udpChannel.channelUri());

        checkForClashingSubscription(params, udpChannel, streamId);

        final ReceiveChannelEndpoint channelEndpoint = getOrCreateReceiveChannelEndpoint(udpChannel);
        final int refCount = channelEndpoint.incRefToStream(streamId);
        if (1 == refCount)
        {
            receiverProxy.addSubscription(channelEndpoint, streamId);
        }

        final AeronClient client = getOrAddClient(clientId);
        final SubscriptionLink subscription = new NetworkSubscriptionLink(
            registrationId, channelEndpoint, streamId, channel, client, clientLivenessTimeoutNs, params);

        subscriptionLinks.add(subscription);
        clientProxy.onSubscriptionReady(registrationId, channelEndpoint.statusIndicatorCounterId());

        linkMatchingImages(channelEndpoint, subscription);
    }

    void onAddIpcSubscription(final String channel, final int streamId, final long registrationId, final long clientId)
    {
        final IpcSubscriptionLink subscription = new IpcSubscriptionLink(
            registrationId, streamId, channel, getOrAddClient(clientId), clientLivenessTimeoutNs);

        subscriptionLinks.add(subscription);
        clientProxy.onSubscriptionReady(registrationId, ChannelEndpointStatus.NO_ID_ALLOCATED);

        for (int i = 0, size = ipcPublications.size(); i < size; i++)
        {
            final IpcPublication publication = ipcPublications.get(i);
            if (publication.streamId() == streamId && IpcPublication.State.ACTIVE == publication.state())
            {
                linkIpcSubscription(subscription, publication);
            }
        }
    }

    void onAddSpySubscription(final String channel, final int streamId, final long registrationId, final long clientId)
    {
        final UdpChannel udpChannel = UdpChannel.parse(channel);
        final AeronClient client = getOrAddClient(clientId);
        final SpySubscriptionLink subscriptionLink = new SpySubscriptionLink(
            registrationId, udpChannel, streamId, client, context.clientLivenessTimeoutNs());

        subscriptionLinks.add(subscriptionLink);
        clientProxy.onSubscriptionReady(registrationId, ChannelEndpointStatus.NO_ID_ALLOCATED);

        final SendChannelEndpoint channelEndpoint = sendChannelEndpointByChannelMap.get(udpChannel.canonicalForm());

        for (int i = 0, size = networkPublications.size(); i < size; i++)
        {
            final NetworkPublication publication = networkPublications.get(i);

            if (streamId == publication.streamId() &&
                channelEndpoint == publication.channelEndpoint() &&
                NetworkPublication.State.ACTIVE == publication.state())
            {
                linkSpy(publication, subscriptionLink);
            }
        }
    }

    void onRemoveSubscription(final long registrationId, final long correlationId)
    {
        final SubscriptionLink subscription = removeSubscriptionLink(subscriptionLinks, registrationId);
        if (null == subscription)
        {
            throw new ControlProtocolException(UNKNOWN_SUBSCRIPTION, "Unknown Subscription: " + registrationId);
        }

        subscription.close();
        final ReceiveChannelEndpoint channelEndpoint = subscription.channelEndpoint();

        if (null != channelEndpoint)
        {
            final int refCount = channelEndpoint.decRefToStream(subscription.streamId());
            if (0 == refCount)
            {
                receiverProxy.removeSubscription(channelEndpoint, subscription.streamId());
            }

            if (channelEndpoint.shouldBeClosed())
            {
                channelEndpoint.closeStatusIndicator();
                receiveChannelEndpointByChannelMap.remove(channelEndpoint.udpChannel().canonicalForm());
                receiverProxy.closeReceiveChannelEndpoint(channelEndpoint);
            }
        }

        clientProxy.operationSucceeded(correlationId);
    }

    void onClientKeepalive(final long clientId)
    {
        clientKeepAlives.incrementOrdered();

        final AeronClient client = findClient(clients, clientId);
        if (null != client)
        {
            client.timeOfLastKeepalive(cachedNanoClock.nanoTime());
        }
    }

    void onAddCounter(
        final int typeId,
        final DirectBuffer keyBuffer,
        final int keyOffset,
        final int keyLength,
        final DirectBuffer labelBuffer,
        final int labelOffset,
        final int labelLength,
        final long correlationId,
        final long clientId)
    {
        final AeronClient client = getOrAddClient(clientId);
        final AtomicCounter counter = countersManager.newCounter(
            typeId, keyBuffer, keyOffset, keyLength, labelBuffer, labelOffset, labelLength);

        counterLinks.add(new CounterLink(counter, correlationId, client));

        clientProxy.onCounterReady(correlationId, counter.id());
    }

    void onRemoveCounter(final long registrationId, final long correlationId)
    {
        CounterLink counterLink = null;
        final ArrayList<CounterLink> counterLinks = this.counterLinks;
        for (int i = 0, size = counterLinks.size(), lastIndex = size - 1; i < size; i++)
        {
            final CounterLink link = counterLinks.get(i);
            if (registrationId == link.registrationId())
            {
                counterLink = link;
                fastUnorderedRemove(counterLinks, i, lastIndex);
                break;
            }
        }

        if (null == counterLink)
        {
            throw new ControlProtocolException(UNKNOWN_COUNTER, "Unknown counter: " + registrationId);
        }

        // acknowledge remove counter request
        clientProxy.operationSucceeded(correlationId);

        // inform all clients of counter being removed.
        clientProxy.onUnavailableCounter(counterLink.registrationId(), counterLink.counterId());

        counterLink.close();
    }

    void onClientClose(final long clientId, final long correlationId)
    {
        final AeronClient client = findClient(clients, clientId);
        if (null != client)
        {
            client.timeOfLastKeepalive(0);

            clientProxy.operationSucceeded(correlationId);
        }
    }

    private void heartbeatAndCheckTimers(final long nowNs)
    {
        final long nowMs = cachedEpochClock.time();
        toDriverCommands.consumerHeartbeatTime(nowMs);

        checkManagedResources(clients, nowNs, nowMs);
        checkManagedResources(publicationLinks, nowNs, nowMs);
        checkManagedResources(networkPublications, nowNs, nowMs);
        checkManagedResources(subscriptionLinks, nowNs, nowMs);
        checkManagedResources(publicationImages, nowNs, nowMs);
        checkManagedResources(ipcPublications, nowNs, nowMs);
        checkManagedResources(counterLinks, nowNs, nowMs);
    }

    private void checkForBlockedToDriverCommands(final long nowNs)
    {
        final long consumerPosition = toDriverCommands.consumerPosition();

        if (consumerPosition == lastConsumerCommandPosition)
        {
            if (toDriverCommands.producerPosition() > consumerPosition &&
                nowNs > (timeOfLastToDriverPositionChangeNs + clientLivenessTimeoutNs))
            {
                if (toDriverCommands.unblock())
                {
                    context.systemCounters().get(UNBLOCKED_COMMANDS).incrementOrdered();
                }
            }
        }
        else
        {
            timeOfLastToDriverPositionChangeNs = nowNs;
            lastConsumerCommandPosition = consumerPosition;
        }
    }

    private List<SubscriberPosition> createSubscriberPositions(
        final int sessionId,
        final int streamId,
        final ReceiveChannelEndpoint channelEndpoint,
        final long joinPosition)
    {
        final ArrayList<SubscriberPosition> subscriberPositions = new ArrayList<>();

        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink subscription = subscriptionLinks.get(i);
            if (subscription.matches(channelEndpoint, streamId))
            {
                final Position position = SubscriberPos.allocate(
                    tempBuffer,
                    countersManager,
                    subscription.registrationId(),
                    sessionId,
                    streamId,
                    subscription.channel(),
                    joinPosition);

                position.setOrdered(joinPosition);
                subscriberPositions.add(new SubscriberPosition(subscription, position));
            }
        }

        return subscriberPositions;
    }

    private static NetworkPublication findPublication(
        final ArrayList<NetworkPublication> publications,
        final int streamId,
        final SendChannelEndpoint channelEndpoint)
    {
        for (int i = 0, size = publications.size(); i < size; i++)
        {
            final NetworkPublication publication = publications.get(i);

            if (streamId == publication.streamId() &&
                channelEndpoint == publication.channelEndpoint() &&
                NetworkPublication.State.ACTIVE == publication.state() &&
                !publication.isExclusive())
            {
                return publication;
            }
        }

        return null;
    }

    private NetworkPublication newNetworkPublication(
        final long registrationId,
        final int streamId,
        final String channel,
        final UdpChannel udpChannel,
        final SendChannelEndpoint channelEndpoint,
        final PublicationParams params,
        final boolean isExclusive)
    {
        final int sessionId = params.hasSessionId ? params.sessionId : nextSessionId++;
        final UnsafeBufferPosition senderPosition = SenderPos.allocate(
            tempBuffer, countersManager, registrationId, sessionId, streamId, channel);
        final UnsafeBufferPosition senderLimit = SenderLimit.allocate(
            tempBuffer, countersManager, registrationId, sessionId, streamId, channel);

        final int initialTermId = params.isReplay ? params.initialTermId : BitUtil.generateRandomisedId();
        if (params.isReplay)
        {
            final int bits = Integer.numberOfTrailingZeros(params.termLength);
            final long position = computePosition(params.termId, params.termOffset, bits, initialTermId);
            senderLimit.setOrdered(position);
            senderPosition.setOrdered(position);
        }

        final RetransmitHandler retransmitHandler = new RetransmitHandler(
            cachedNanoClock,
            context.systemCounters(),
            RETRANSMIT_UNICAST_DELAY_GENERATOR,
            RETRANSMIT_UNICAST_LINGER_GENERATOR);

        final FlowControl flowControl = udpChannel.isMulticast() || udpChannel.hasExplicitControl() ?
            context.multicastFlowControlSupplier().newInstance(udpChannel, streamId, registrationId) :
            context.unicastFlowControlSupplier().newInstance(udpChannel, streamId, registrationId);

        final NetworkPublication publication = new NetworkPublication(
            registrationId,
            channelEndpoint,
            cachedNanoClock,
            newNetworkPublicationLog(sessionId, streamId, initialTermId, udpChannel, registrationId, params),
            PublisherLimit.allocate(tempBuffer, countersManager, registrationId, sessionId, streamId, channel),
            senderPosition,
            senderLimit,
            sessionId,
            streamId,
            initialTermId,
            params.mtuLength,
            context.systemCounters(),
            flowControl,
            retransmitHandler,
            networkPublicationThreadLocals,
            publicationUnblockTimeoutNs,
            context.publicationConnectionTimeoutNs(),
            isExclusive,
            context.spiesSimulateConnection());

        channelEndpoint.incRef();
        networkPublications.add(publication);
        senderProxy.newNetworkPublication(publication);
        linkSpies(subscriptionLinks, publication);

        return publication;
    }

    private RawLog newNetworkPublicationLog(
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final UdpChannel udpChannel,
        final long registrationId,
        final PublicationParams params)
    {
        final RawLog rawLog = rawLogFactory.newNetworkPublication(
            udpChannel.canonicalForm(), sessionId, streamId, registrationId, params.termLength);

        initPublicationMetadata(sessionId, streamId, initialTermId, registrationId, params, rawLog);

        return rawLog;
    }

    private RawLog newIpcPublicationLog(
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final long registrationId,
        final PublicationParams params)
    {
        final RawLog rawLog = rawLogFactory.newIpcPublication(sessionId, streamId, registrationId, params.termLength);

        initPublicationMetadata(sessionId, streamId, initialTermId, registrationId, params, rawLog);

        return rawLog;
    }

    private void initPublicationMetadata(
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final long registrationId,
        final PublicationParams params,
        final RawLog rawLog)
    {
        final UnsafeBuffer logMetaData = rawLog.metaData();
        storeDefaultFrameHeader(logMetaData, createDefaultHeader(sessionId, streamId, initialTermId));

        initialTermId(logMetaData, initialTermId);
        mtuLength(logMetaData, params.mtuLength);
        termLength(logMetaData, rawLog.termLength());
        pageSize(logMetaData, context.filePageSize());
        correlationId(logMetaData, registrationId);
        endOfStreamPosition(logMetaData, Long.MAX_VALUE);

        initialisePositionCounters(initialTermId, params, logMetaData);
    }

    private static void initialisePositionCounters(
        final int initialTermId, final PublicationParams params, final UnsafeBuffer logMetaData)
    {
        if (params.isReplay)
        {
            final int termCount = params.termId - initialTermId;
            int activeIndex = indexByTerm(initialTermId, params.termId);

            rawTail(logMetaData, activeIndex, packTail(params.termId, params.termOffset));
            for (int i = 1; i < PARTITION_COUNT; i++)
            {
                final int expectedTermId = (initialTermId + i) - PARTITION_COUNT;
                activeIndex = nextPartitionIndex(activeIndex);
                initialiseTailWithTermId(logMetaData, activeIndex, expectedTermId);
            }

            activeTermCount(logMetaData, termCount);
        }
        else
        {
            initialiseTailWithTermId(logMetaData, 0, initialTermId);
            for (int i = 1; i < PARTITION_COUNT; i++)
            {
                final int expectedTermId = (initialTermId + i) - PARTITION_COUNT;
                initialiseTailWithTermId(logMetaData, i, expectedTermId);
            }
        }
    }

    private RawLog newPublicationImageLog(
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final int termBufferLength,
        final int senderMtuLength,
        final UdpChannel udpChannel,
        final long correlationId)
    {
        final RawLog rawLog = rawLogFactory.newNetworkedImage(
            udpChannel.canonicalForm(), sessionId, streamId, correlationId, termBufferLength);

        final UnsafeBuffer logMetaData = rawLog.metaData();
        storeDefaultFrameHeader(logMetaData, createDefaultHeader(sessionId, streamId, initialTermId));
        initialTermId(logMetaData, initialTermId);
        mtuLength(logMetaData, senderMtuLength);
        termLength(logMetaData, termBufferLength);
        pageSize(logMetaData, context.filePageSize());
        correlationId(logMetaData, correlationId);
        endOfStreamPosition(logMetaData, Long.MAX_VALUE);

        return rawLog;
    }

    private SendChannelEndpoint getOrCreateSendChannelEndpoint(final UdpChannel udpChannel)
    {
        SendChannelEndpoint channelEndpoint = sendChannelEndpointByChannelMap.get(udpChannel.canonicalForm());
        if (null == channelEndpoint)
        {
            channelEndpoint = context.sendChannelEndpointSupplier().newInstance(
                udpChannel,
                SendChannelStatus.allocate(tempBuffer, countersManager, udpChannel.originalUriString()),
                context);

            sendChannelEndpointByChannelMap.put(udpChannel.canonicalForm(), channelEndpoint);
            senderProxy.registerSendChannelEndpoint(channelEndpoint);
        }

        return channelEndpoint;
    }

    private void checkForClashingSubscription(
        final SubscriptionParams params, final UdpChannel udpChannel, final int streamId)
    {
        final ReceiveChannelEndpoint channelEndpoint = receiveChannelEndpointByChannelMap.get(
            udpChannel.canonicalForm());
        if (null != channelEndpoint)
        {
            final ArrayList<SubscriptionLink> existingLinks = subscriptionLinks;
            for (int i = 0, size = existingLinks.size(); i < size; i++)
            {
                final SubscriptionLink subscription = existingLinks.get(i);
                if (subscription.matches(channelEndpoint, streamId) && params.isReliable != subscription.isReliable())
                {
                    throw new IllegalStateException(
                        "Option conflicts with existing subscriptions: reliable=" + params.isReliable);
                }
            }
        }
    }

    private void linkMatchingImages(final ReceiveChannelEndpoint channelEndpoint, final SubscriptionLink subscription)
    {
        final long registrationId = subscription.registrationId();
        final int streamId = subscription.streamId();
        final String channel = subscription.channel();

        for (int i = 0, size = publicationImages.size(); i < size; i++)
        {
            final PublicationImage image = publicationImages.get(i);
            if (image.matches(channelEndpoint, streamId) && image.isAcceptingSubscriptions())
            {
                final long rebuildPosition = image.rebuildPosition();
                final int sessionId = image.sessionId();
                final Position position = SubscriberPos.allocate(
                    tempBuffer, countersManager, registrationId, sessionId, streamId, channel, rebuildPosition);

                position.setOrdered(rebuildPosition);

                image.addSubscriber(position);
                subscription.link(image, position);

                clientProxy.onAvailableImage(
                    image.correlationId(),
                    streamId,
                    sessionId,
                    registrationId,
                    position.id(),
                    image.rawLog().fileName(),
                    generateSourceIdentity(image.sourceAddress()));
            }
        }
    }

    private void linkIpcSubscriptions(final IpcPublication publication)
    {
        final int streamId = publication.streamId();
        final ArrayList<SubscriptionLink> subscriptionLinks = this.subscriptionLinks;

        for (int i = 0, size = subscriptionLinks.size(); i < size; i++)
        {
            final SubscriptionLink subscription = subscriptionLinks.get(i);
            if (subscription.matches(streamId) && !subscription.isLinked(publication))
            {
                linkIpcSubscription((IpcSubscriptionLink)subscription, publication);
            }
        }
    }

    private static ReadablePosition[] positionArray(final List<SubscriberPosition> subscriberPositions)
    {
        final int size = subscriberPositions.size();
        final ReadablePosition[] positions = new ReadablePosition[subscriberPositions.size()];

        for (int i = 0; i < size; i++)
        {
            positions[i] = subscriberPositions.get(i).position();
        }

        return positions;
    }

    private void linkIpcSubscription(final IpcSubscriptionLink subscription, final IpcPublication publication)
    {
        final long joinPosition = publication.joinPosition();
        final long registrationId = subscription.registrationId();
        final int sessionId = publication.sessionId();
        final int streamId = subscription.streamId();
        final String channel = subscription.channel();

        final Position position = SubscriberPos.allocate(
            tempBuffer, countersManager, registrationId, sessionId, streamId, channel, joinPosition);

        position.setOrdered(joinPosition);
        publication.addSubscriber(position);
        subscription.link(publication, position);

        clientProxy.onAvailableImage(
            publication.registrationId(),
            streamId,
            sessionId,
            registrationId,
            position.id(),
            publication.rawLog().fileName(),
            channel);
    }

    private void linkSpy(final NetworkPublication publication, final SubscriptionLink subscription)
    {
        final long joinPosition = publication.consumerPosition();
        final long subscriberRegistrationId = subscription.registrationId();
        final int streamId = publication.streamId();
        final int sessionId = publication.sessionId();
        final String channel = subscription.channel();

        final Position position = SubscriberPos.allocate(
            tempBuffer, countersManager, subscriberRegistrationId, sessionId, streamId, channel, joinPosition);

        position.setOrdered(joinPosition);
        publication.addSubscriber(position);
        subscription.link(publication, position);

        clientProxy.onAvailableImage(
            publication.registrationId(),
            streamId,
            sessionId,
            subscriberRegistrationId,
            position.id(),
            publication.rawLog().fileName(),
            CommonContext.IPC_CHANNEL);
    }

    private ReceiveChannelEndpoint getOrCreateReceiveChannelEndpoint(final UdpChannel udpChannel)
    {
        ReceiveChannelEndpoint channelEndpoint = receiveChannelEndpointByChannelMap.get(udpChannel.canonicalForm());
        if (null == channelEndpoint)
        {
            channelEndpoint = context.receiveChannelEndpointSupplier().newInstance(
                udpChannel,
                new DataPacketDispatcher(context.driverConductorProxy(), receiverProxy.receiver()),
                ReceiveChannelStatus.allocate(tempBuffer, countersManager, udpChannel.originalUriString()),
                context);

            receiveChannelEndpointByChannelMap.put(udpChannel.canonicalForm(), channelEndpoint);
            receiverProxy.registerReceiveChannelEndpoint(channelEndpoint);
        }

        return channelEndpoint;
    }

    private AeronClient getOrAddClient(final long clientId)
    {
        AeronClient client = findClient(clients, clientId);
        if (null == client)
        {
            client = new AeronClient(clientId, clientLivenessTimeoutNs, cachedNanoClock.nanoTime());
            clients.add(client);
        }

        return client;
    }

    private IpcPublication getOrAddIpcPublication(
        final long correlationId, final int streamId, final String channel, final boolean isExclusive)
    {
        IpcPublication publication = null;
        final ChannelUri channelUri = ChannelUri.parse(channel);
        final PublicationParams params = getPublicationParams(context, channelUri, isExclusive, true);

        if (!isExclusive)
        {
            publication = findSharedIpcPublication(ipcPublications, streamId);
        }
        else if (params.hasSessionId)
        {
            confirmIpcSessionIdNotInUse(ipcPublications, params.sessionId);
        }

        if (null == publication)
        {
            validateMtuForMaxMessage(params, isExclusive);
            publication = addIpcPublication(correlationId, streamId, channel, isExclusive, params);
        }
        else
        {
            confirmMatch(channelUri, params, publication.rawLog(), publication.sessionId());
        }

        return publication;
    }

    private IpcPublication addIpcPublication(
        final long registrationId,
        final int streamId,
        final String channel,
        final boolean isExclusive,
        final PublicationParams params)
    {
        final int sessionId = params.hasSessionId ? params.sessionId : nextSessionId++;
        final int initialTermId = params.isReplay ? params.initialTermId : BitUtil.generateRandomisedId();
        final RawLog rawLog = newIpcPublicationLog(sessionId, streamId, initialTermId, registrationId, params);

        final IpcPublication publication = new IpcPublication(
            registrationId,
            sessionId,
            streamId,
            PublisherLimit.allocate(tempBuffer, countersManager, registrationId, sessionId, streamId, channel),
            rawLog,
            publicationUnblockTimeoutNs,
            cachedNanoClock.nanoTime(),
            context.systemCounters(),
            isExclusive);

        ipcPublications.add(publication);

        return publication;
    }

    private static AeronClient findClient(final ArrayList<AeronClient> clients, final long clientId)
    {
        AeronClient aeronClient = null;

        for (int i = 0, size = clients.size(); i < size; i++)
        {
            final AeronClient client = clients.get(i);
            if (client.clientId() == clientId)
            {
                aeronClient = client;
                break;
            }
        }

        return aeronClient;
    }

    private static SubscriptionLink removeSubscriptionLink(
        final ArrayList<SubscriptionLink> subscriptionLinks, final long registrationId)
    {
        SubscriptionLink subscriptionLink = null;

        for (int i = 0, size = subscriptionLinks.size(), lastIndex = size - 1; i < size; i++)
        {
            final SubscriptionLink subscription = subscriptionLinks.get(i);
            if (subscription.registrationId() == registrationId)
            {
                subscriptionLink = subscription;
                fastUnorderedRemove(subscriptionLinks, i, lastIndex);
                break;
            }
        }

        return subscriptionLink;
    }

    private static IpcPublication findSharedIpcPublication(
        final ArrayList<IpcPublication> ipcPublications, final long streamId)
    {
        IpcPublication ipcPublication = null;

        for (int i = 0, size = ipcPublications.size(); i < size; i++)
        {
            final IpcPublication publication = ipcPublications.get(i);
            if (publication.streamId() == streamId &&
                !publication.isExclusive() &&
                IpcPublication.State.ACTIVE == publication.state())
            {
                ipcPublication = publication;
                break;
            }
        }

        return ipcPublication;
    }

    private static void confirmIpcSessionIdNotInUse(
        final ArrayList<IpcPublication> ipcPublications, final long sessionId)
    {
        for (int i = 0, size = ipcPublications.size(); i < size; i++)
        {
            final IpcPublication publication = ipcPublications.get(i);
            if (publication.sessionId() == sessionId &&
                publication.isExclusive() &&
                IpcPublication.State.ACTIVE == publication.state())
            {
                throw new IllegalStateException(
                    "Existing exclusive IPC publication has same session id: id=" + sessionId);
            }
        }
    }

    private static void confirmNetworkSessionIdNotInUse(
        final ArrayList<NetworkPublication> networkPublications, final long sessionId)
    {
        for (int i = 0, size = networkPublications.size(); i < size; i++)
        {
            final NetworkPublication publication = networkPublications.get(i);
            if (publication.sessionId() == sessionId &&
                publication.isExclusive() &&
                NetworkPublication.State.ACTIVE == publication.state())
            {
                throw new IllegalStateException(
                    "Existing exclusive network publication has same session id: id=" + sessionId);
            }
        }
    }

    private <T extends DriverManagedResource> void checkManagedResources(
        final ArrayList<T> list, final long nowNs, final long nowMs)
    {
        for (int lastIndex = list.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final DriverManagedResource resource = list.get(i);

            resource.onTimeEvent(nowNs, nowMs, this);

            if (resource.hasReachedEndOfLife())
            {
                fastUnorderedRemove(list, i, lastIndex);
                lastIndex--;
                resource.delete();
            }
        }
    }

    private void linkSpies(final ArrayList<SubscriptionLink> links, final NetworkPublication publication)
    {
        for (int i = 0, size = links.size(); i < size; i++)
        {
            final SubscriptionLink subscription = links.get(i);
            if (subscription.matches(publication) && !subscription.isLinked(publication))
            {
                linkSpy(publication, subscription);
            }
        }
    }

    private void updateClocks(final long nowNs)
    {
        if (nowNs >= clockUpdateDeadlineNs)
        {
            clockUpdateDeadlineNs = nowNs + 1_000_000;
            cachedNanoClock.update(nowNs);
            cachedEpochClock.update(epochClock.time());
        }
    }

    private int processTimers(final long nowNs)
    {
        int workCount = 0;

        if (nowNs > (timeOfLastTimerCheckNs + timerIntervalNs))
        {
            heartbeatAndCheckTimers(nowNs);
            checkForBlockedToDriverCommands(nowNs);
            timeOfLastTimerCheckNs = nowNs;
            workCount = 1;
        }

        return workCount;
    }

    private static String generateSourceIdentity(final InetSocketAddress address)
    {
        return address.getHostString() + ':' + address.getPort();
    }
}
