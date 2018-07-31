/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.connectivity.messaging;

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;
import static org.eclipse.ditto.services.connectivity.messaging.BaseClientState.CONNECTED;
import static org.eclipse.ditto.services.connectivity.messaging.BaseClientState.CONNECTING;
import static org.eclipse.ditto.services.connectivity.messaging.BaseClientState.DISCONNECTED;
import static org.eclipse.ditto.services.connectivity.messaging.BaseClientState.DISCONNECTING;
import static org.eclipse.ditto.services.connectivity.messaging.BaseClientState.TESTING;
import static org.eclipse.ditto.services.connectivity.messaging.BaseClientState.UNKNOWN;
import static org.eclipse.ditto.services.connectivity.messaging.DittoHeadersFilter.Mode.EXCLUDE;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;

import org.eclipse.ditto.model.base.auth.AuthorizationContext;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.headers.WithDittoHeaders;
import org.eclipse.ditto.model.connectivity.AddressMetric;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionMetrics;
import org.eclipse.ditto.model.connectivity.ConnectionStatus;
import org.eclipse.ditto.model.connectivity.ConnectivityModelFactory;
import org.eclipse.ditto.model.connectivity.MappingContext;
import org.eclipse.ditto.model.connectivity.Source;
import org.eclipse.ditto.model.connectivity.SourceMetrics;
import org.eclipse.ditto.model.connectivity.Target;
import org.eclipse.ditto.model.connectivity.TargetMetrics;
import org.eclipse.ditto.services.connectivity.messaging.internal.ClientConnected;
import org.eclipse.ditto.services.connectivity.messaging.internal.ClientDisconnected;
import org.eclipse.ditto.services.connectivity.messaging.internal.ConnectionFailure;
import org.eclipse.ditto.services.connectivity.messaging.internal.RetrieveAddressMetric;
import org.eclipse.ditto.services.connectivity.util.ConfigKeys;
import org.eclipse.ditto.services.utils.akka.LogUtil;
import org.eclipse.ditto.signals.base.Signal;
import org.eclipse.ditto.signals.commands.base.Command;
import org.eclipse.ditto.signals.commands.connectivity.ConnectivityCommand;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionFailedException;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionSignalIllegalException;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionUnavailableException;
import org.eclipse.ditto.signals.commands.connectivity.modify.CloseConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.CreateConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.DeleteConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.ModifyConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.OpenConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.TestConnection;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionMetrics;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionMetricsResponse;

import com.typesafe.config.Config;

import akka.actor.AbstractFSM;
import akka.actor.ActorRef;
import akka.actor.FSM;
import akka.actor.Props;
import akka.actor.Status;
import akka.event.DiagnosticLoggingAdapter;
import akka.japi.Pair;
import akka.japi.pf.FSMStateFunctionBuilder;
import akka.pattern.PatternsCS;
import akka.routing.DefaultResizer;
import akka.routing.RoundRobinPool;
import akka.util.Timeout;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

/**
 * Base class for ClientActors which implement the connection handling for various connectivity protocols.
 * <p>
 * The actor expects to receive a {@link CreateConnection} command after it was started. If this command is not received
 * within timeout (can be the case when this actor is remotely deployed after the command was sent) the actor requests
 * the required information from ConnectionActor.
 * </p>
 */
public abstract class BaseClientActor extends AbstractFSM<BaseClientState, BaseClientData> {

    protected static final int CONNECTING_TIMEOUT = 10;
    protected static final int RETRIEVE_METRICS_TIMEOUT = 2;

    private static final int SOCKET_CHECK_TIMEOUT_MS = 2000;

    protected final DiagnosticLoggingAdapter log = LogUtil.obtain(this);
    private final List<String> headerBlacklist;
    private final ActorRef conciergeForwarder;

    @Nullable private ActorRef messageMappingProcessorActor;

    private long consumedMessageCounter = 0L;
    private long publishedMessageCounter = 0L;

    protected BaseClientActor(final Connection connection, final ConnectionStatus desiredConnectionStatus,
            final ActorRef conciergeForwarder) {
        checkNotNull(connection, "connection");

        final Config config = getContext().getSystem().settings().config();
        final java.time.Duration javaInitTimeout = config.getDuration(ConfigKeys.Client.INIT_TIMEOUT);
        headerBlacklist = config.getStringList(ConfigKeys.Message.HEADER_BLACKLIST);
        this.conciergeForwarder = conciergeForwarder;

        final BaseClientData startingData = new BaseClientData(connection.getId(), connection, ConnectionStatus.UNKNOWN,
                desiredConnectionStatus, "initialized", Instant.now(), null, null);

        final FiniteDuration initTimeout = Duration.create(javaInitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        final FiniteDuration connectingTimeout = Duration.create(CONNECTING_TIMEOUT, TimeUnit.SECONDS);

        startWith(UNKNOWN, startingData, initTimeout);

        // stable states
        when(UNKNOWN, inUnknownState());
        when(CONNECTED, inConnectedState());
        when(DISCONNECTED, inDisconnectedState());

        // volatile states that time out
        when(CONNECTING, connectingTimeout, inConnectingState());
        when(DISCONNECTING, connectingTimeout, inDisconnectingState());
        when(TESTING, connectingTimeout, inTestingState());

        onTransition(this::onTransition);

        whenUnhandled(commonHandler(connection.getId()).anyEvent(this::onUnknownEvent));

        initialize();
    }

    private FSMStateFunctionBuilder<BaseClientState, BaseClientData> inUnknownState() {
        final List<Object> closeOrDeleteConnection = Arrays.asList(CloseConnection.class, DeleteConnection.class);

        return matchEvent(CreateConnection.class, BaseClientData.class, this::createConnection)
                .event(OpenConnection.class, BaseClientData.class, this::openConnection)
                .event(closeOrDeleteConnection, BaseClientData.class, this::closeConnection)
                .event(TestConnection.class, BaseClientData.class, this::testConnection)
                .eventEquals(StateTimeout(), BaseClientData.class, (state, data) -> {
                    if (data.getDesiredConnectionStatus() == ConnectionStatus.OPEN) {
                        log.info("Did not receive connect command within init-timeout, connecting");
                        final OpenConnection openConnection = OpenConnection.of(connectionId(), DittoHeaders.empty());
                        getSelf().tell(openConnection, getSelf());
                    } else {
                        log.info("Did not receive connect command within init-timeout, discopnnecting");

                    }
                    return stay(); // handle self-told commands later
                });
    }

    private FSMStateFunctionBuilder<BaseClientState, BaseClientData> inDisconnectedState() {
        return matchEvent(OpenConnection.class, BaseClientData.class, this::openConnection)
                .event(CreateConnection.class, BaseClientData.class, this::createConnection)
                .event(TestConnection.class, BaseClientData.class, this::testConnection)
                .event(DeleteConnection.class, BaseClientData.class, this::replySuccess);
    }

    private FSMStateFunctionBuilder<BaseClientState, BaseClientData> inConnectingState() {
        return matchEventEquals(StateTimeout(), BaseClientData.class, this::connectionTimedOut)
                .event(ConnectionFailure.class, BaseClientData.class, this::handleConnectionFailure)
                .event(ClientConnected.class, BaseClientData.class, this::handleClientConnected);
    }

    private FSMStateFunctionBuilder<BaseClientState, BaseClientData> inConnectedState() {
        final List<Object> closeOrDeleteConnection = Arrays.asList(CloseConnection.class, DeleteConnection.class);
        return matchEvent(closeOrDeleteConnection, BaseClientData.class, this::closeConnection);
    }

    private FSMStateFunctionBuilder<BaseClientState, BaseClientData> inDisconnectingState() {
        return matchEventEquals(StateTimeout(), BaseClientData.class, this::connectionTimedOut)
                .event(ConnectionFailure.class, BaseClientData.class, this::handleConnectionFailure)
                .event(ClientDisconnected.class, BaseClientData.class, this::handleClientDisconnected);
    }

    private FSMStateFunctionBuilder<BaseClientState, BaseClientData> inTestingState() {
        return matchEvent(Status.Status.class, BaseClientData.class,
                (status, data) -> {
                    final Status.Status answerToPublish;
                    if (status instanceof Status.Failure) {
                        final Status.Failure failure = (Status.Failure) status;
                        log().error(failure.cause(), "test failed");
                        if (!(failure.cause() instanceof DittoRuntimeException)) {
                            final DittoRuntimeException error = ConnectionFailedException.newBuilder(connectionId())
                                    .dittoHeaders(data.getLastCommandHeaders())
                                    .build();
                            answerToPublish = new Status.Failure(error);
                        } else {
                            answerToPublish = status;
                        }
                    } else {
                        answerToPublish = status;
                    }
                    data.getOrigin().ifPresent(sender -> sender.tell(answerToPublish, getSelf()));
                    return stop();
                })
                .eventEquals(StateTimeout(), BaseClientData.class, (stats, data) -> {
                    log().error("test timed out.");
                    data.getOrigin().ifPresent(sender -> {
                        final DittoRuntimeException error = ConnectionUnavailableException.newBuilder(connectionId())
                                .dittoHeaders(data.getLastCommandHeaders())
                                .build();
                        sender.tell(new Status.Failure(error), getSelf());
                    });
                    return stop();
                });
    }

    private DittoRuntimeException unhandledExceptionForSignalInState(final Object signal,
            final BaseClientState state) {
        final DittoHeaders headers = signal instanceof WithDittoHeaders
                ? ((WithDittoHeaders) signal).getDittoHeaders()
                : DittoHeaders.empty();
        switch (state) {
            case CONNECTING:
            case DISCONNECTING:
                return ConnectionSignalIllegalException.newBuilder(connectionId())
                        .operationName(state.name().toLowerCase())
                        .timeout(CONNECTING_TIMEOUT)
                        .dittoHeaders(headers)
                        .build();
            default:
                final String signalType = signal instanceof Signal
                        ? ((Signal) signal).getType()
                        : "unknown"; // no need to disclose Java class of signal to clients
                return ConnectionSignalIllegalException.newBuilder(connectionId())
                        .illegalSignalForState(signalType, state.name().toLowerCase())
                        .dittoHeaders(headers)
                        .build();
        }
    }

    private State<BaseClientState, BaseClientData> onUnknownEvent(final Object event,
            final BaseClientData state) {

        log.warning("received unknown/unsupported message {} in state {} - status: {}",
                event, stateName(),
                state.getConnectionStatus() + ": " +
                        state.getConnectionStatusDetails().orElse(""));

        final ActorRef sender = getSender();
        if (!Objects.equals(sender, getSelf()) && !Objects.equals(sender, getContext().system().deadLetters())) {
            sender.tell(unhandledExceptionForSignalInState(event, stateName()), getSelf());
        }

        return stay();
    }

    private FSM.State<BaseClientState, BaseClientData> closeConnection(final Object closeOrDeleteConnection,
            final BaseClientData data) {

        final ActorRef sender = getSender();
        doDisconnectClient(data.getConnection(), sender);
        return goTo(DISCONNECTING).using(data
                .setOrigin(sender)
                .setDesiredConnectionStatus(ConnectionStatus.CLOSED)
                .setConnectionStatusDetails("closing or deleting connection at " + Instant.now()));
    }

    private FSM.State<BaseClientState, BaseClientData> openConnection(final Object openOrCreateConnection,
            final BaseClientData data) {

        final DittoHeaders dittoHeaders = openOrCreateConnection instanceof WithDittoHeaders
                ? ((WithDittoHeaders) openOrCreateConnection).getDittoHeaders()
                : DittoHeaders.empty();

        final ActorRef sender = getSender();
        final Connection connection = data.getConnection();
        if (canConnectViaSocket(connection)) {
            doConnectClient(connection, sender);
            return goTo(CONNECTING).using(data.setOrigin(sender));
        } else {
            final DittoRuntimeException error = newConnectionFailedException(data.getConnection(), dittoHeaders);
            sender.tell(new Status.Failure(error), getSelf());
            return goTo(UNKNOWN);
        }
    }

    private FSM.State<BaseClientState, BaseClientData> createConnection(final CreateConnection createConnection,
            final BaseClientData data) {

        final Connection connection = createConnection.getConnection();
        final DittoHeaders dittoHeaders = createConnection.getDittoHeaders();

        if (connection.getConnectionStatus() == ConnectionStatus.OPEN) {
            final ConnectivityCommand nextStep = OpenConnection.of(connection.getId(), dittoHeaders);
            getSelf().tell(nextStep, getSender());
        }

        return stay().using(data
                .setConnection(connection)
                .setDesiredConnectionStatus(connection.getConnectionStatus())
                .setConnectionStatusDetails("creating connection at " + Instant.now())
                .setOrigin(getSender())
                .setLastCommandHeaders(dittoHeaders));
    }

    private FSM.State<BaseClientState, BaseClientData> testConnection(final TestConnection testConnection,
            final BaseClientData data) {

        final ActorRef self = getSelf();
        final ActorRef sender = getSender();
        final Connection connection = testConnection.getConnection();

        if (!canConnectViaSocket(connection)) {
            final ConnectionFailedException connectionFailedException =
                    newConnectionFailedException(connection, testConnection.getDittoHeaders());
            final Status.Status failure = new Status.Failure(connectionFailedException);
            getSelf().tell(failure, self);
        } else {
            final CompletionStage<Status.Status> connectionStatusStage =
                    doTestConnection(connection);
            final CompletionStage<Status.Status> mappingStatusStage =
                    testMessageMappingProcessor(connection.getMappingContext().orElse(null));

            connectionStatusStage.toCompletableFuture()
                    .thenCombine(mappingStatusStage, (connectionStatus, mappingStatus) -> {
                        if (connectionStatus instanceof Status.Success &&
                                mappingStatus instanceof Status.Success) {
                            return new Status.Success("successfully connected + initialized mapper");
                        } else if (connectionStatus instanceof Status.Failure) {
                            return connectionStatus;
                        } else {
                            return mappingStatus;
                        }
                    })
                    .thenAccept(testStatus -> self.tell(testStatus, self))
                    .exceptionally(error -> {
                        self.tell(new Status.Failure(error), self);
                        return null;
                    });
        }

        return goTo(TESTING)
                .using(data.setConnection(connection)
                        .setOrigin(sender)
                        .setLastCommandHeaders(testConnection.getDittoHeaders())
                        .setConnectionStatusDetails("Testing connection since " + Instant.now()));
    }

    private static ConnectionFailedException newConnectionFailedException(final Connection connection,
            final DittoHeaders dittoHeaders) {

        return ConnectionFailedException
                .newBuilder(connection.getId())
                .dittoHeaders(dittoHeaders)
                .description("Could not establish a connection on '" +
                        connection.getHostname() + ":" + connection.getPort() + "'. Make sure the " +
                        "endpoint is reachable and that no firewall prevents the connection.")
                .build();
    }

    private FSM.State<BaseClientState, BaseClientData> connectionTimedOut(final Object event,
            final BaseClientData data) {

        data.getOrigin().ifPresent(sender -> {
            final DittoRuntimeException error = ConnectionFailedException.newBuilder(connectionId())
                    .dittoHeaders(data.getLastCommandHeaders())
                    .build();
            sender.tell(new Status.Failure(error), getSelf());
        });
        return goTo(UNKNOWN).using(data
                .setConnectionStatus(ConnectionStatus.FAILED)
                .setConnectionStatusDetails("Connection timed out at " + Instant.now() + " while " + stateName()));
    }

    private FSM.State<BaseClientState, BaseClientData> retrieveConnectionMetrics(
            final RetrieveConnectionMetrics command,
            final BaseClientData data) {

        final ConnectionMetrics connectionMetrics = ConnectivityModelFactory.newConnectionMetrics(
                getCurrentConnectionStatus(), getCurrentConnectionStatusDetails().orElse(null),
                getInConnectionStatusSince(), stateName().name(),
                getCurrentSourcesMetrics(), getCurrentTargetsMetrics());
        final DittoHeaders dittoHeaders = command.getDittoHeaders().toBuilder()
                .source(org.eclipse.ditto.services.utils.config.ConfigUtil.calculateInstanceUniqueSuffix())
                .build();

        final Object response = RetrieveConnectionMetricsResponse.of(connectionId(), connectionMetrics, dittoHeaders);
        getSender().tell(response, getSelf());

        return stay();
    }

    private FSM.State<BaseClientState, BaseClientData> translateModifyToCreateConnection(
            final ModifyConnection command,
            final BaseClientData data) {

        final CreateConnection createConnection =
                CreateConnection.of(command.getConnection(), command.getDittoHeaders());
        getSelf().tell(createConnection, getSender());
        return stay();
    }

    private FSM.State<BaseClientState, BaseClientData> replySuccess(final Object event, final BaseClientData data) {
        getSender().tell(new Status.Success(stateName()), getSelf());
        return stay();
    }

    /**
     * Creates the handler for messages common to all states.
     * <p>
     * Overwrite and extend by additional matchers.
     * </p>
     *
     * @param connectionId the connection ID
     * @return an FSM function builder
     */
    protected FSMStateFunctionBuilder<BaseClientState, BaseClientData> commonHandler(final String connectionId) {
        return matchEvent(RetrieveConnectionMetrics.class, BaseClientData.class, this::retrieveConnectionMetrics)
                .event(ModifyConnection.class, BaseClientData.class, this::translateModifyToCreateConnection)
                .event(OutboundSignal.WithExternalMessage.class, BaseClientData.class, (outboundSignal, data) -> {
                    handleExternalMessage(outboundSignal);
                    return stay();
                })
                .event(OutboundSignal.class, BaseClientData.class, (signal, data) -> {
                    handleOutboundSignal(signal);
                    return stay();
                });
    }

    private boolean canConnectViaSocket(final Connection connection) {
        return checkHostAndPortForAvailability(connection.getHostname(), connection.getPort());
    }

    private boolean checkHostAndPortForAvailability(final String host, final int port) {
        try (final Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), SOCKET_CHECK_TIMEOUT_MS);
            return true;
        } catch (final IOException ex) {
            LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId());
            log.warning("Socket could not be opened for <{}:{}>", host, port);
        }
        return false;
    }

    private State<BaseClientState, BaseClientData> handleClientConnected(final ClientConnected clientConnected,
            final BaseClientData data) {

        LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId());
        startMessageMappingProcessor(data.getConnection().getMappingContext().orElse(null));
        onClientConnected(clientConnected, data);
        return goTo(CONNECTED)
                .using(data.setConnectionStatus(ConnectionStatus.OPEN)
                        .setConnectionStatusDetails("Connected at " + Instant.now()))
                .replying(new Status.Success(CONNECTED));
    }

    private State<BaseClientState, BaseClientData> handleClientDisconnected(final ClientDisconnected event,
            final BaseClientData data) {

        LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId());
        if (this.isConsuming()) {
            stopMessageMappingProcessorActor();
        }
        onClientDisconnected(event, data);
        return goTo(DISCONNECTED)
                .using(data.setConnectionStatus(ConnectionStatus.CLOSED)
                        .setConnectionStatusDetails("Disconnected at " + Instant.now()))
                .replying(new Status.Success(DISCONNECTED));
    }

    private State<BaseClientState, BaseClientData> handleConnectionFailure(final ConnectionFailure event,
            final BaseClientData data) {

        LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId());
        onConnectionFailure(event, data);
        return goTo(UNKNOWN).using(data
                .setConnectionStatus(ConnectionStatus.FAILED)
                .setConnectionStatusDetails(event.getFailureDescription())
                .setOrigin(getSender())
        );
    }

    /**
     * Invoked on each transition {@code from} a {@link BaseClientState} {@code to} another.
     * <p>
     * May be extended to react on special transitions.
     * </p>
     *
     * @param from the previous State
     * @param to the next State
     */
    private void onTransition(final BaseClientState from, final BaseClientState to) {
        LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId());
        log.info("Transition: {} -> {}", from, to);
    }

    /**
     * Handles {@link TestConnection} commands by returning a CompletionState of
     * {@link akka.actor.Status.Status Status} which may be {@link akka.actor.Status.Success Success} or
     * {@link akka.actor.Status.Failure Failure}.
     *
     * @param connection the Connection to test
     * @return the CompletionStage with the test result
     */
    protected abstract CompletionStage<Status.Status> doTestConnection(final Connection connection);

    /**
     * Called when this {@code Client} connected successfully.
     *
     * @param clientConnected the ClientConnected message which may be subclassed and thus adding more information
     * @param data the data of the current State
     */
    protected abstract void onClientConnected(final ClientConnected clientConnected, final BaseClientData data);

    /**
     * Called when this {@code Client} disconnected.
     *
     * @param clientDisconnected the ClientDisconnected message which may be subclassed and thus adding more information
     * @param data the data of the current State
     */
    protected abstract void onClientDisconnected(final ClientDisconnected clientDisconnected,
            final BaseClientData data);

    /**
     * @return the optional Actor to use for Publishing commandResponses/events
     */
    protected abstract Optional<ActorRef> getPublisherActor();

    /**
     * Called when this {@code Client} encountered a Failure.
     *
     * @param connectionFailure the ConnectionFailure message which may be subclassed and thus adding more information
     * @param data the data of the current State
     */
    protected void onConnectionFailure(final ConnectionFailure connectionFailure, final BaseClientData data) {
        connectionFailure.getOrigin().ifPresent(o -> o.tell(connectionFailure.getFailure(), getSelf()));
    }

    /**
     * Invoked when this {@code Client} should connect.
     *
     * @param connection the Connection to use for connecting
     * @param origin the ActorRef which caused the ConnectClient command
     */
    protected abstract void doConnectClient(final Connection connection, @Nullable final ActorRef origin);

    /**
     * Invoked when this {@code Client} should disconnect.
     *
     * @param connection the Connection to use for disconnecting
     * @param origin the ActorRef which caused the DisconnectClient command
     */
    protected abstract void doDisconnectClient(final Connection connection, @Nullable final ActorRef origin);

    /**
     * Retrieves the connection status of the passed {@link Source}.
     *
     * @param source the Source to retrieve the connection status for
     * @return the result as Map containing an entry for each source
     */
    protected abstract Map<String, AddressMetric> getSourceConnectionStatus(final Source source);

    /**
     * Retrieves the connection status of the passed {@link Target}.
     *
     * @param target the Target to retrieve the connection status for
     * @return the result as Map containing an entry for each target
     */
    protected abstract Map<String, AddressMetric> getTargetConnectionStatus(final Target target);

    /**
     * Retrieves the {@link AddressMetric} of a single address which is handled by a child actor with the passed
     * {@code childActorName}.
     *
     * @param addressIdentifier the identifier used as first entry in the Pair of CompletableFuture
     * @param childActorName the actor name of the child to ask for the AddressMetric
     * @return a CompletableFuture with the addressIdentifier and the retrieved AddressMetric
     */
    protected final CompletableFuture<Pair<String, AddressMetric>> retrieveAddressMetric(
            final String addressIdentifier, final String childActorName) {

        final Optional<ActorRef> childActor = getContext().findChild(childActorName);
        if (childActor.isPresent()) {
            final ActorRef actorRef = childActor.get();
            return PatternsCS.ask(actorRef, RetrieveAddressMetric.getInstance(),
                    Timeout.apply(RETRIEVE_METRICS_TIMEOUT, TimeUnit.SECONDS))
                    .handle((response, throwable) -> {
                        if (response != null) {
                            return Pair.create(addressIdentifier, (AddressMetric) response);
                        } else {
                            return Pair.create(addressIdentifier,
                                    ConnectivityModelFactory.newAddressMetric(
                                            ConnectionStatus.FAILED,
                                            throwable.getClass().getSimpleName() + ": " +
                                                    throwable.getMessage(),
                                            -1, Instant.now()));
                        }
                    }).toCompletableFuture();
        } else {
            log.warning("Consumer actor child <{}> was not found in child actors <{}>", childActorName,
                    StreamSupport
                            .stream(getContext().getChildren().spliterator(), false)
                            .map(ref -> ref.path().name())
                            .collect(Collectors.toList()));
            return CompletableFuture.completedFuture(Pair.create(addressIdentifier,
                    ConnectivityModelFactory.newAddressMetric(
                            ConnectionStatus.FAILED,
                            "child <" + childActorName + "> not found",
                            -1, Instant.now())));
        }
    }

    /**
     * Increments the consumed message counter by 1.
     */
    protected final void incrementConsumedMessageCounter() {
        consumedMessageCounter++;
    }

    /**
     * Increments the published message counter by 1.
     */
    protected final void incrementPublishedMessageCounter() {
        publishedMessageCounter++;
    }

    private void handleOutboundSignal(final OutboundSignal signal) {
        enhanceLogUtil(signal.getSource());
        if (messageMappingProcessorActor != null) {
            messageMappingProcessorActor.tell(signal, getSelf());
        } else {
            log.info("Cannot handle <{}> signal, no MessageMappingProcessor available.", signal.getSource().getType());
        }
    }

    private void enhanceLogUtil(final WithDittoHeaders<?> signal) {
        LogUtil.enhanceLogWithCorrelationId(log, signal);
        LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId());
    }

    private void handleExternalMessage(final OutboundSignal.WithExternalMessage mappedOutboundSignal) {
        getPublisherActor().ifPresent(publisher -> {
            incrementPublishedMessageCounter();
            publisher.forward(mappedOutboundSignal, getContext());
        });
    }

    private ConnectionStatus getCurrentConnectionStatus() {
        return stateData().getConnectionStatus();
    }

    private Instant getInConnectionStatusSince() {
        return stateData().getInConnectionStatusSince();
    }

    private Optional<String> getCurrentConnectionStatusDetails() {
        return stateData().getConnectionStatusDetails();
    }

    private List<SourceMetrics> getCurrentSourcesMetrics() {
        return getSourcesOrEmptySet()
                .stream()
                .map(source -> ConnectivityModelFactory.newSourceMetrics(
                        getSourceConnectionStatus(source),
                        consumedMessageCounter)
                )
                .collect(Collectors.toList());
    }

    private List<TargetMetrics> getCurrentTargetsMetrics() {
        return getTargetsOrEmptySet()
                .stream()
                .map(target -> ConnectivityModelFactory.newTargetMetrics(
                        getTargetConnectionStatus(target),
                        publishedMessageCounter)
                )
                .collect(Collectors.toList());
    }

    private CompletionStage<Status.Status> testMessageMappingProcessor(@Nullable final MappingContext mappingContext) {
        try {
            // this one throws DittoRuntimeExceptions when the mapper could not be configured
            MessageMappingProcessor.of(connectionId(), mappingContext, getContext().getSystem(), log);
            return CompletableFuture.completedFuture(new Status.Success("mapping"));
        } catch (final DittoRuntimeException dre) {
            log.info("Got DittoRuntimeException during initialization of MessageMappingProcessor: {} {} - desc: {}",
                    dre.getClass().getSimpleName(), dre.getMessage(), dre.getDescription().orElse(""));
            getSender().tell(dre, getSelf());
            return CompletableFuture.completedFuture(new Status.Failure(dre));
        }
    }

    /**
     * Starts the {@link MessageMappingProcessorActor} responsible for payload transformation/mapping as child actor
     * behind a (cluster node local) RoundRobin pool and a dynamic resizer.
     *
     * @param mappingContext the MappingContext containing information about how to map external messages
     */
    private void startMessageMappingProcessor(@Nullable final MappingContext mappingContext) {
        if (!getMessageMappingProcessorActor().isPresent()) {
            final Connection connection = connection();

            final MessageMappingProcessor processor;
            try {
                // this one throws DittoRuntimeExceptions when the mapper could not be configured
                processor = MessageMappingProcessor.of(connectionId(), mappingContext, getContext().getSystem(), log);
            } catch (final DittoRuntimeException dre) {
                log.info(
                        "Got DittoRuntimeException during initialization of MessageMappingProcessor: {} {} - desc: {}",
                        dre.getClass().getSimpleName(), dre.getMessage(), dre.getDescription().orElse(""));
                getSender().tell(dre, getSelf());
                return;
            }

            log.info("Configured for processing messages with the following MessageMapperRegistry: <{}>",
                    processor.getRegistry());

            log.debug("Starting MessageMappingProcessorActor with pool size of <{}>.",
                    connection.getProcessorPoolSize());
            final Props props =
                    MessageMappingProcessorActor.props(getSelf(),
                            conciergeForwarder,
                            new DittoHeadersFilter(EXCLUDE, headerBlacklist),
                            processor, connectionId());

            final DefaultResizer resizer = new DefaultResizer(1, connection.getProcessorPoolSize());
            messageMappingProcessorActor = getContext().actorOf(new RoundRobinPool(1)
                    .withDispatcher("message-mapping-processor-dispatcher")
                    .withResizer(resizer)
                    .props(props), MessageMappingProcessorActor.ACTOR_NAME);
        } else {
            log.info("MessageMappingProcessor already instantiated, don't initialize again..");
        }
    }

    /**
     * @return the optional MessageMappingProcessorActor
     */
    protected final Optional<ActorRef> getMessageMappingProcessorActor() {
        return Optional.ofNullable(messageMappingProcessorActor);
    }

    private void stopMessageMappingProcessorActor() {
        if (messageMappingProcessorActor != null) {
            log.debug("Stopping MessageMappingProcessorActor.");
            getContext().stop(messageMappingProcessorActor);
            messageMappingProcessorActor = null;
        }
    }

    private void cannotHandle(final Command<?> command, @Nullable final Connection connection) {
        enhanceLogUtil(command);
        log.info("Command <{}> cannot be handled in current state <{}>.", command.getType(), stateName());
        final String message =
                MessageFormat.format("Cannot execute command <{0}> in current state <{1}>.", command.getType(),
                        stateName());
        final String connectionId = connection != null ? connection.getId() : "?";
        final ConnectionFailedException failedException =
                ConnectionFailedException.newBuilder(connectionId).message(message).build();
        getSender().tell(new Status.Failure(failedException), getSelf());
    }

    /**
     * Escapes the passed actorName in a actorName valid way.
     *
     * @param name the actorName to escape
     * @return the escaped name
     */
    protected static String escapeActorName(final String name) {
        return name.replace('/', '_');
    }

    /**
     * Transforms a List of CompletableFutures to a CompletableFuture of a List.
     *
     * @param futures the List of futures
     * @param <T> the type of the CompletableFuture and the List elements
     * @return the CompletableFuture of a List
     */
    protected static <T> CompletableFuture<List<T>> collectAsList(final List<CompletableFuture<T>> futures) {
        return collect(futures, Collectors.toList());
    }

    private static <T, A, R> CompletableFuture<R> collect(final List<CompletableFuture<T>> futures,
            final Collector<T, A, R> collector) {

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(collector));
    }

    /**
     * Starts a child actor.
     *
     * @param name the Actor's name
     * @param props the Props
     * @return the created ActorRef
     */
    protected final ActorRef startChildActor(final String name, final Props props) {
        log.debug("Starting child actor '{}'", name);
        final String nameEscaped = escapeActorName(name);
        return getContext().actorOf(props, nameEscaped);
    }

    /**
     * Stops a child actor.
     *
     * @param name the Actor's name
     */
    protected final void stopChildActor(final String name) {
        final String nameEscaped = escapeActorName(name);
        final Optional<ActorRef> child = getContext().findChild(nameEscaped);
        if (child.isPresent()) {
            log.debug("Stopping child actor '{}'", nameEscaped);
            getContext().stop(child.get());
        } else {
            log.debug("Cannot stop child actor '{}' because it does not exist.", nameEscaped);
        }
    }

    /**
     * Stops a child actor.
     *
     * @param actor the ActorRef
     */
    protected final void stopChildActor(final ActorRef actor) {
        log.debug("Stopping child actor '{}'", actor.path());
        getContext().stop(actor);
    }

    /**
     * @return whether this client is consuming at all
     */
    protected final boolean isConsuming() {
        return !connection().getSources().isEmpty();
    }

    /**
     * @return whether this client is publishing at all
     */
    protected final boolean isPublishing() {
        return !connection().getTargets().isEmpty();
    }

    /**
     * @return the currently managed Connection
     */
    protected final Connection connection() {
        return stateData().getConnection();
    }

    /**
     * @return the Connection Id
     */
    protected final String connectionId() {
        return stateData().getConnectionId();
    }

    /**
     * @return the sources configured for this connection or an empty set if no sources were configured.
     */
    protected final List<Source> getSourcesOrEmptySet() {
        return connection().getSources();
    }

    /**
     * @return the targets configured for this connection or an empty set if no targets were configured.
     */
    protected final Set<Target> getTargetsOrEmptySet() {
        return connection().getTargets();
    }

    protected final AuthorizationContext resolveAuthorizationContext(final Source source) {
        if (source.getAuthorizationContext().isEmpty()) {
            return connection().getAuthorizationContext();
        } else {
            return source.getAuthorizationContext();
        }
    }

}
