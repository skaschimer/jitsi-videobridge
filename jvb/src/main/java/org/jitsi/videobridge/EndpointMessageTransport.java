/*
 * Copyright @ 2017 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge;

import org.eclipse.jetty.websocket.api.*;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.datachannel.*;
import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi.videobridge.message.*;
import org.jitsi.videobridge.metrics.*;
import org.jitsi.videobridge.relay.*;
import org.jitsi.videobridge.websocket.*;
import org.json.simple.*;

import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import static org.jitsi.videobridge.VersionConfig.config;
import static org.jitsi.videobridge.util.MultiStreamCompatibilityKt.endpointIdToSourceName;

/**
 * Handles the functionality related to sending and receiving COLIBRI messages
 * for an {@link Endpoint}. Supports two underlying transport mechanisms --
 * WebRTC data channels and {@code WebSocket}s.
 *
 * @author Boris Grozev
 */
public class EndpointMessageTransport
    extends AbstractEndpointMessageTransport
    implements DataChannelStack.DataChannelMessageListener,
        ColibriWebSocket.EventHandler
{
    /**
     * The last accepted web-socket by this instance, if any.
     */
    private ColibriWebSocket webSocket;

    /**
     * User to synchronize access to {@link #webSocket}
     */
    private final Object webSocketSyncRoot = new Object();

    /**
     * Whether the last active transport channel (i.e. the last to receive a
     * message from the remote endpoint) was the web socket (if {@code true}),
     * or the WebRTC data channel (if {@code false}).
     */
    private boolean webSocketLastActive = false;

    private WeakReference<DataChannel> dataChannel = new WeakReference<>(null);

    private final EndpointMessageTransportEventHandler eventHandler;

    private final AtomicInteger numOutgoingMessagesDropped = new AtomicInteger(0);

    /**
     * The number of sent message by type.
     */
    private final Map<String, AtomicLong> sentMessagesCounts = new ConcurrentHashMap<>();

    @NotNull
    private final Endpoint endpoint;

    /**
     * Initializes a new {@link EndpointMessageTransport} instance.
     * @param endpoint the associated {@link Endpoint}.
     */
    EndpointMessageTransport(
        @NotNull Endpoint endpoint,
        EndpointMessageTransportEventHandler eventHandler,
        Logger parentLogger)
    {
        super(parentLogger);
        this.endpoint = endpoint;
        this.eventHandler = eventHandler;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void notifyTransportChannelConnected()
    {
        endpoint.endpointMessageTransportConnected();
        eventHandler.endpointMessageTransportConnected(endpoint);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BridgeChannelMessage clientHello(ClientHelloMessage message)
    {
        // ClientHello was introduced for functional testing purposes. It
        // triggers a ServerHello response from Videobridge. The exchange
        // reveals (to the client) that the transport channel between the
        // remote endpoint and the Videobridge is operational.
        // We take care to send the reply using the same transport channel on
        // which we received the request..
        return createServerHello();
    }

    @Override
    public BridgeChannelMessage videoType(VideoTypeMessage videoTypeMessage)
    {
        return sourceVideoType(
                new SourceVideoTypeMessage(
                        videoTypeMessage.getVideoType(),
                        endpointIdToSourceName(endpoint.getId()),
                        videoTypeMessage.getEndpointId())
        );
    }

    @Override
    public BridgeChannelMessage sourceVideoType(SourceVideoTypeMessage sourceVideoTypeMessage)
    {
        String sourceName = sourceVideoTypeMessage.getSourceName();

        if (getLogger().isDebugEnabled())
        {
            getLogger().debug("Received video type of " + sourceName +": " + sourceVideoTypeMessage.getVideoType());
        }

        endpoint.setVideoType(sourceName, sourceVideoTypeMessage.getVideoType());

        Conference conference = endpoint.getConference();

        if (conference.isExpired())
        {
            getLogger().warn("Unable to forward SourceVideoTypeMessage, conference is expired");
            return null;
        }

        sourceVideoTypeMessage.setEndpointId(endpoint.getId());

        /* Forward videoType messages to Relays. */
        conference.sendMessage(sourceVideoTypeMessage, Collections.emptyList(), true);

        return null;
    }

    @Override
    public BridgeChannelMessage receiverAudioSubscription(
            @NotNull ReceiverAudioSubscriptionMessage receiverAudioSubscriptionMessage
    )
    {
        if (getLogger().isDebugEnabled())
        {
            getLogger().debug("Received audio subscription: " + receiverAudioSubscriptionMessage);
        }

        endpoint.setAudioSubscription(receiverAudioSubscriptionMessage);

        return null;
    }

    @Override
    public void unhandledMessage(@NotNull BridgeChannelMessage message)
    {
        getLogger().warn("Received a message with an unexpected type: " + message.getClass().getSimpleName());
    }

    /**
     * Sends a string via a particular transport channel.
     * @param dst the transport channel.
     * @param message the message to send.
     */
    protected void sendMessage(Object dst, BridgeChannelMessage message)
    {
        super.sendMessage(dst, message); // Log message

        if (dst instanceof ColibriWebSocket)
        {
            sendMessage((ColibriWebSocket) dst, message);
        }
        else if (dst instanceof DataChannel)
        {
            sendMessage((DataChannel)dst, message);
        }
        else
        {
            throw new IllegalArgumentException("unknown transport:" + dst);
        }
    }

    /**
     * Sends a string via a particular {@link DataChannel}.
     * @param dst the data channel to send through.
     * @param message the message to send.
     */
    private void sendMessage(DataChannel dst, BridgeChannelMessage message)
    {
        dst.sendString(message.toJson());
        VideobridgeMetrics.dataChannelMessagesSent.inc();
    }

    /**
     * Sends a string via a particular {@link ColibriWebSocket} instance.
     * @param dst the {@link ColibriWebSocket} through which to send the message.
     * @param message the message to send.
     */
    private void sendMessage(ColibriWebSocket dst, BridgeChannelMessage message)
    {
        dst.sendString(message.toJson());
        VideobridgeMetrics.colibriWebSocketMessagesSent.inc();
    }

    @Override
    public void onDataChannelMessage(DataChannelMessage dataChannelMessage)
    {
        webSocketLastActive = false;
        VideobridgeMetrics.dataChannelMessagesReceived.inc();

        if (dataChannelMessage instanceof DataChannelStringMessage)
        {
            DataChannelStringMessage dataChannelStringMessage = (DataChannelStringMessage)dataChannelMessage;
            onMessage(dataChannel.get(), dataChannelStringMessage.data);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void sendMessage(@NotNull BridgeChannelMessage msg)
    {
        Object dst = getActiveTransportChannel();
        if (dst == null)
        {
            getLogger().debug("No available transport channel, can't send a message");
            numOutgoingMessagesDropped.incrementAndGet();
        }
        else
        {
            sentMessagesCounts.computeIfAbsent(
                    msg.getClass().getSimpleName(),
                    (k) -> new AtomicLong()).incrementAndGet();
            sendMessage(dst, msg);
        }
    }

    /**
     * @return the active transport channel for this
     * {@link EndpointMessageTransport} (either the {@link #webSocket}, or
     * the WebRTC data channel represented by a {@link DataChannel}).
     * </p>
     * The "active" channel is determined based on what channels are available,
     * and which one was the last to receive data. That is, if only one channel
     * is available, it will be returned. If two channels are available, the
     * last one to have received data will be returned. Otherwise, {@code null}
     * will be returned.
     */
    //TODO(brian): seems like it'd be nice to have the websocket and datachannel
    // share a common parent class (or, at least, have a class that is returned
    // here and provides a common API but can wrap either a websocket or
    // datachannel)
    private Object getActiveTransportChannel()
    {
        DataChannel dataChannel = this.dataChannel.get();
        ColibriWebSocket webSocket = this.webSocket;

        Object dst = null;
        if (webSocketLastActive)
        {
            dst = webSocket;
        }

        // Either the socket was not the last active channel,
        // or it has been closed.
        if (dst == null)
        {
            if (dataChannel != null && dataChannel.isReady())
            {
                dst = dataChannel;
            }
        }

        // Maybe the WebRTC data channel is the last active, but it is not
        // currently available. If so, and a web-socket is available -- use it.
        if (dst == null && webSocket != null)
        {
            dst = webSocket;
        }

        return dst;
    }

    @Override
    public boolean isConnected()
    {
        return getActiveTransportChannel() != null;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void webSocketConnected(ColibriWebSocket ws)
    {
        synchronized (webSocketSyncRoot)
        {
            // If we already have a web-socket, discard it and use the new one.
            if (webSocket != null)
            {
                Session session = webSocket.getSession();
                if (session != null)
                {
                    session.close(CloseStatus.NORMAL, "replaced");
                }
            }

            webSocket = ws;
            webSocketLastActive = true;
            sendMessage(ws, createServerHello());
        }

        try
        {
            notifyTransportChannelConnected();
        }
        catch (Exception e)
        {
            getLogger().warn("Caught an exception in notifyTransportConnected", e);
        }
    }

    private ServerHelloMessage createServerHello()
    {
        if (config.announceVersion())
        {
            return new ServerHelloMessage(endpoint.getConference().getVideobridge().getVersion().toString());
        }
        else
        {
            return new ServerHelloMessage();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void webSocketClosed(ColibriWebSocket ws, int statusCode, String reason)
    {
        synchronized (webSocketSyncRoot)
        {
            if (ws != null && ws.equals(webSocket))
            {
                webSocket = null;
                webSocketLastActive = false;
                getLogger().info(() -> "Websocket closed, statusCode " + statusCode + " ( " + reason + ").");
                // 1000 is normal, 1001 is e.g. a tab closing. 1005 is "No Status Rcvd" and we see the majority of
                // sockets close this way.
                if (statusCode == 1000 || statusCode == 1001 || statusCode == 1005)
                {
                    VideobridgeMetrics.colibriWebSocketCloseNormal.inc();
                }
                else
                {
                    VideobridgeMetrics.colibriWebSocketCloseAbnormal.inc();
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void webSocketError(ColibriWebSocket ws, Throwable cause)
    {
        getLogger().error("Colibri websocket error: " +  cause.getMessage());
        VideobridgeMetrics.colibriWebSocketErrors.inc();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        synchronized (webSocketSyncRoot)
        {
            if (webSocket != null)
            {
                //  1001 indicates that an endpoint is "going away", such as a server
                //  going down or a browser having navigated away from a page.
                webSocket.getSession().close(CloseStatus.SHUTDOWN, "endpoint closed");
                webSocket = null;
                getLogger().debug(() -> "Endpoint expired, closed colibri web-socket.");
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void webSocketTextReceived(ColibriWebSocket ws, String message)
    {
        if (ws == null || !ws.equals(webSocket))
        {
            getLogger().warn("Received text from an unknown web socket.");
            return;
        }

        VideobridgeMetrics.colibriWebSocketMessagesReceived.inc();

        webSocketLastActive = true;
        onMessage(ws, message);
    }

    /**
     * Sets the data channel for this endpoint.
     * @param dataChannel the {@link DataChannel} to use for this transport
     */
    void setDataChannel(DataChannel dataChannel)
    {
        DataChannel prevDataChannel = this.dataChannel.get();
        if (prevDataChannel == null)
        {
            this.dataChannel = new WeakReference<>(dataChannel);
            // We install the handler first, otherwise the 'ready' might fire after we check it but before we
            //  install the handler
            dataChannel.onDataChannelEvents(this::notifyTransportChannelConnected);
            if (dataChannel.isReady())
            {
                notifyTransportChannelConnected();
            }
            dataChannel.onDataChannelMessage(this);
        }
        else if (prevDataChannel == dataChannel)
        {
            //TODO: i think we should be able to ensure this doesn't happen,
            // so throwing for now.  if there's a good
            // reason for this, we can make this a no-op
            throw new Error("Re-setting the same data channel");
        }
        else
        {
            throw new Error("Overwriting a previous data channel!");
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public JSONObject getDebugState()
    {
        JSONObject debugState = super.getDebugState();
        debugState.put("numOutgoingMessagesDropped", numOutgoingMessagesDropped.get());

        JSONObject sentCounts = new JSONObject();
        sentCounts.putAll(sentMessagesCounts);
        debugState.put("sent_counts", sentCounts);

        return debugState;
    }

    @Nullable
    @Override
    public BridgeChannelMessage receiverVideoConstraints(@NotNull ReceiverVideoConstraintsMessage message)
    {
        endpoint.setBandwidthAllocationSettings(message);
        return null;
    }

    /**
     * Notifies this {@code Endpoint} that a {@link LastNMessage} has been
     * received.
     *
     * @param message the message that was received.
     */
    @Override
    public BridgeChannelMessage lastN(LastNMessage message)
    {
        endpoint.setLastN(message.getLastN());

        return null;
    }

    /**
     * Handles an opaque message from this {@code Endpoint} that should be forwarded to either: a) another client in
     * this conference (1:1 message) or b) all other clients in this conference (broadcast message).
     *
     * @param message the message that was received from the endpoint.
     */
    @Override
    public BridgeChannelMessage endpointMessage(EndpointMessage message)
    {
        if (endpoint.getVisitor())
        {
            getLogger().warn("Not forwarding endpoint message from visitor endpoint");
            return null;
        }

        // First insert/overwrite the "from" to prevent spoofing.
        String from = endpoint.getId();
        message.setFrom(from);

        Conference conference = endpoint.getConference();

        if (conference == null || conference.isExpired())
        {
            getLogger().warn("Unable to send EndpointMessage, conference is null or expired");
            return null;
        }

        if (message.isBroadcast())
        {
            // Broadcast message to all local endpoints and relays.
            List<Endpoint> targets = new LinkedList<>(conference.getLocalEndpoints());
            targets.remove(endpoint);
            conference.sendMessage(message, targets, /* sendToRelays */ true);
        }
        else
        {
            // 1:1 message
            String to = message.getTo();

            AbstractEndpoint targetEndpoint = conference.getEndpoint(to);
            if (targetEndpoint instanceof Endpoint)
            {
                ((Endpoint)targetEndpoint).sendMessage(message);
            }
            else if (targetEndpoint instanceof RelayedEndpoint)
            {
                ((RelayedEndpoint)targetEndpoint).getRelay().sendMessage(message);
            }
            else if (targetEndpoint != null)
            {
                conference.sendMessage(message, Collections.emptyList(), /* sendToRelays */ true);
            }
            else
            {
                getLogger().warn("Unable to find endpoint to send EndpointMessage to: " + to);
            }
        }

        return null;
    }

    /**
     * Handles an endpoint statistics message from this {@code Endpoint} that should be forwarded to
     * other endpoints as appropriate, and also to relays.
     *
     * @param message the message that was received from the endpoint.
     */
    @Override
    public BridgeChannelMessage endpointStats(@NotNull EndpointStats message)
    {
        if (endpoint.getVisitor())
        {
            getLogger().warn("Not forwarding endpoint stats from visitor endpoint");
            return null;
        }

        // First insert/overwrite the "from" to prevent spoofing.
        String from = endpoint.getId();
        message.setFrom(from);

        Conference conference = endpoint.getConference();

        if (conference.isExpired())
        {
            getLogger().warn("Unable to send EndpointStats, conference is null or expired");
            return null;
        }

        List<Endpoint> targets = conference.getLocalEndpoints().stream()
            .filter((ep) -> ep != endpoint && ep.wantsStatsFrom(endpoint))
            .collect(Collectors.toList());

        conference.sendMessage(message, targets, true);
        return null;
    }
}
