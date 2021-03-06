/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.netconf.sal.connect.netconf;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import org.opendaylight.controller.md.sal.dom.api.DOMNotification;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcException;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcResult;
import org.opendaylight.controller.md.sal.dom.api.DOMRpcService;
import org.opendaylight.netconf.api.NetconfMessage;
import org.opendaylight.netconf.sal.connect.api.MessageTransformer;
import org.opendaylight.netconf.sal.connect.api.RemoteDevice;
import org.opendaylight.netconf.sal.connect.api.RemoteDeviceCommunicator;
import org.opendaylight.netconf.sal.connect.api.RemoteDeviceHandler;
import org.opendaylight.netconf.sal.connect.netconf.listener.NetconfDeviceCapabilities;
import org.opendaylight.netconf.sal.connect.netconf.listener.NetconfDeviceCommunicator;
import org.opendaylight.netconf.sal.connect.netconf.listener.NetconfSessionPreferences;
import org.opendaylight.netconf.sal.connect.netconf.sal.NetconfDeviceRpc;
import org.opendaylight.netconf.sal.connect.netconf.schema.NetconfRemoteSchemaYangSourceProvider;
import org.opendaylight.netconf.sal.connect.netconf.schema.mapping.NetconfMessageTransformer;
import org.opendaylight.netconf.sal.connect.netconf.util.NetconfMessageTransformUtil;
import org.opendaylight.netconf.sal.connect.util.RemoteDeviceId;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.netconf.notifications.rev120206.NetconfCapabilityChange;
import org.opendaylight.yang.gen.v1.urn.opendaylight.netconf.node.topology.rev150114.netconf.node.connection.status.unavailable.capabilities.UnavailableCapability;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.repo.api.MissingSchemaSourceException;
import org.opendaylight.yangtools.yang.model.repo.api.RevisionSourceIdentifier;
import org.opendaylight.yangtools.yang.model.repo.api.SchemaContextFactory;
import org.opendaylight.yangtools.yang.model.repo.api.SchemaResolutionException;
import org.opendaylight.yangtools.yang.model.repo.api.SchemaSourceRepresentation;
import org.opendaylight.yangtools.yang.model.repo.api.SourceIdentifier;
import org.opendaylight.yangtools.yang.model.repo.api.YangTextSchemaSource;
import org.opendaylight.yangtools.yang.model.repo.spi.PotentialSchemaSource;
import org.opendaylight.yangtools.yang.model.repo.spi.SchemaSourceRegistration;
import org.opendaylight.yangtools.yang.model.repo.spi.SchemaSourceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *  This is a mediator between NetconfDeviceCommunicator and NetconfDeviceSalFacade
 */
public class NetconfDevice implements RemoteDevice<NetconfSessionPreferences, NetconfMessage, NetconfDeviceCommunicator> {

    private static final Logger LOG = LoggerFactory.getLogger(NetconfDevice.class);

    public static final Function<QName, SourceIdentifier> QNAME_TO_SOURCE_ID_FUNCTION = new Function<QName, SourceIdentifier>() {
        @Override
        public SourceIdentifier apply(final QName input) {
            return RevisionSourceIdentifier
                    .create(input.getLocalName(), Optional.fromNullable(input.getFormattedRevision()));
        }
    };

    protected final RemoteDeviceId id;
    private final boolean reconnectOnSchemasChange;

    protected final SchemaContextFactory schemaContextFactory;
    private final RemoteDeviceHandler<NetconfSessionPreferences> salFacade;
    private final ListeningExecutorService processingExecutor;
    protected final SchemaSourceRegistry schemaRegistry;
    private final NetconfStateSchemas.NetconfStateSchemasResolver stateSchemasResolver;
    private final NotificationHandler notificationHandler;
    protected final List<SchemaSourceRegistration<? extends SchemaSourceRepresentation>> sourceRegistrations = Lists.newArrayList();

    // Message transformer is constructed once the schemas are available
    private MessageTransformer<NetconfMessage> messageTransformer;

    /**
     * Create rpc implementation capable of handling RPC for monitoring and notifications even before the schemas of remote device are downloaded
     */
    static NetconfDeviceRpc getRpcForInitialization(final NetconfDeviceCommunicator listener, final boolean notificationSupport) {
        NetconfMessageTransformer.BaseSchema baseSchema = notificationSupport ?
                NetconfMessageTransformer.BaseSchema.BASE_NETCONF_CTX_WITH_NOTIFICATIONS :
                NetconfMessageTransformer.BaseSchema.BASE_NETCONF_CTX;

        return new NetconfDeviceRpc(baseSchema.getSchemaContext(), listener, new NetconfMessageTransformer(baseSchema.getSchemaContext(), false, baseSchema));
    }

    protected NetconfDevice(final SchemaResourcesDTO schemaResourcesDTO, final RemoteDeviceId id, final RemoteDeviceHandler<NetconfSessionPreferences> salFacade,
                         final ExecutorService globalProcessingExecutor, final boolean reconnectOnSchemasChange) {
        this.id = id;
        this.reconnectOnSchemasChange = reconnectOnSchemasChange;
        this.schemaRegistry = schemaResourcesDTO.getSchemaRegistry();
        this.schemaContextFactory = schemaResourcesDTO.getSchemaContextFactory();
        this.salFacade = salFacade;
        this.stateSchemasResolver = schemaResourcesDTO.getStateSchemasResolver();
        this.processingExecutor = MoreExecutors.listeningDecorator(globalProcessingExecutor);
        this.notificationHandler = new NotificationHandler(salFacade, id);
    }

    @Override
    public void onRemoteSessionUp(final NetconfSessionPreferences remoteSessionCapabilities,
                                  final NetconfDeviceCommunicator listener) {
        // SchemaContext setup has to be performed in a dedicated thread since
        // we are in a netty thread in this method
        // Yang models are being downloaded in this method and it would cause a
        // deadlock if we used the netty thread
        // http://netty.io/wiki/thread-model.html
        LOG.debug("{}: Session to remote device established with {}", id, remoteSessionCapabilities);

        final NetconfDeviceRpc initRpc = getRpcForInitialization(listener, remoteSessionCapabilities.isNotificationsSupported());
        final DeviceSourcesResolver task = new DeviceSourcesResolver(remoteSessionCapabilities, id, stateSchemasResolver, initRpc);
        final ListenableFuture<DeviceSources> sourceResolverFuture = processingExecutor.submit(task);

        if (shouldListenOnSchemaChange(remoteSessionCapabilities)) {
            registerToBaseNetconfStream(initRpc, listener);
        }

        final FutureCallback<DeviceSources> resolvedSourceCallback = new FutureCallback<DeviceSources>() {
            @Override
            public void onSuccess(final DeviceSources result) {
                addProvidedSourcesToSchemaRegistry(initRpc, result);
                setUpSchema(result);
            }

            private void setUpSchema(final DeviceSources result) {
                processingExecutor.submit(new SchemaSetup(result, remoteSessionCapabilities, listener));
            }

            @Override
            public void onFailure(final Throwable t) {
                LOG.warn("{}: Unexpected error resolving device sources: {}", id, t);
                handleSalInitializationFailure(t, listener);
            }
        };

        Futures.addCallback(sourceResolverFuture, resolvedSourceCallback);
    }

    private void registerToBaseNetconfStream(final NetconfDeviceRpc deviceRpc, final NetconfDeviceCommunicator listener) {
       // TODO check whether the model describing create subscription is present in schema
        // Perhaps add a default schema context to support create-subscription if the model was not provided (same as what we do for base netconf operations in transformer)
        final CheckedFuture<DOMRpcResult, DOMRpcException> rpcResultListenableFuture =
                deviceRpc.invokeRpc(NetconfMessageTransformUtil.toPath(NetconfMessageTransformUtil.CREATE_SUBSCRIPTION_RPC_QNAME), NetconfMessageTransformUtil.CREATE_SUBSCRIPTION_RPC_CONTENT);

        final NotificationHandler.NotificationFilter filter = new NotificationHandler.NotificationFilter() {
            @Override
            public Optional<DOMNotification> filterNotification(final DOMNotification notification) {
                if (isCapabilityChanged(notification)) {
                    LOG.info("{}: Schemas change detected, reconnecting", id);
                    // Only disconnect is enough, the reconnecting nature of the connector will take care of reconnecting
                    listener.disconnect();
                    return Optional.absent();
                }
                return Optional.of(notification);
            }

            private boolean isCapabilityChanged(final DOMNotification notification) {
                return notification.getBody().getNodeType().equals(NetconfCapabilityChange.QNAME);
            }
        };

        Futures.addCallback(rpcResultListenableFuture, new FutureCallback<DOMRpcResult>() {
            @Override
            public void onSuccess(final DOMRpcResult domRpcResult) {
                notificationHandler.addNotificationFilter(filter);
            }

            @Override
            public void onFailure(final Throwable t) {
                LOG.warn("Unable to subscribe to base notification stream. Schemas will not be reloaded on the fly", t);
            }
        });
    }

    private boolean shouldListenOnSchemaChange(final NetconfSessionPreferences remoteSessionCapabilities) {
        return remoteSessionCapabilities.isNotificationsSupported() && reconnectOnSchemasChange;
    }

    protected void handleSalInitializationSuccess(final SchemaContext result, final NetconfSessionPreferences remoteSessionCapabilities, final DOMRpcService deviceRpc) {
        NetconfMessageTransformer.BaseSchema baseSchema =
                remoteSessionCapabilities.isNotificationsSupported() ?
                NetconfMessageTransformer.BaseSchema.BASE_NETCONF_CTX_WITH_NOTIFICATIONS :
                NetconfMessageTransformer.BaseSchema.BASE_NETCONF_CTX;
        messageTransformer = new NetconfMessageTransformer(result, true, baseSchema);

        updateTransformer(messageTransformer);
        // salFacade.onDeviceConnected has to be called before the notification handler is initialized
        salFacade.onDeviceConnected(result, remoteSessionCapabilities, deviceRpc);
        notificationHandler.onRemoteSchemaUp(messageTransformer);

        LOG.info("{}: Netconf connector initialized successfully", id);
    }

    protected void handleSalInitializationFailure(final Throwable t, final RemoteDeviceCommunicator<NetconfMessage> listener) {
        LOG.error("{}: Initialization in sal failed, disconnecting from device", id, t);
        listener.close();
        onRemoteSessionDown();
        resetMessageTransformer();
    }

    /**
     * Set the transformer to null as is in initial state
     */
    private void resetMessageTransformer() {
        updateTransformer(null);
    }

    private void updateTransformer(final MessageTransformer<NetconfMessage> transformer) {
        messageTransformer = transformer;
    }

    private void addProvidedSourcesToSchemaRegistry(final NetconfDeviceRpc deviceRpc, final DeviceSources deviceSources) {
        final NetconfRemoteSchemaYangSourceProvider yangProvider = new NetconfRemoteSchemaYangSourceProvider(id, deviceRpc);
        for (final SourceIdentifier sourceId : deviceSources.getProvidedSources()) {
            sourceRegistrations.add(schemaRegistry.registerSchemaSource(yangProvider,
                    PotentialSchemaSource.create(sourceId, YangTextSchemaSource.class, PotentialSchemaSource.Costs.REMOTE_IO.getValue())));
        }
    }

    @Override
    public void onRemoteSessionDown() {
        notificationHandler.onRemoteSchemaDown();

        salFacade.onDeviceDisconnected();
        for (final SchemaSourceRegistration<? extends SchemaSourceRepresentation> sourceRegistration : sourceRegistrations) {
            sourceRegistration.close();
        }
        resetMessageTransformer();
    }

    @Override
    public void onRemoteSessionFailed(final Throwable throwable) {
        salFacade.onDeviceFailed(throwable);
    }

    @Override
    public void onNotification(final NetconfMessage notification) {
        notificationHandler.handleNotification(notification);
    }

    /**
     * Just a transfer object containing schema related dependencies. Injected in constructor.
     */
    public static class SchemaResourcesDTO {
        private final SchemaSourceRegistry schemaRegistry;
        private final SchemaContextFactory schemaContextFactory;
        private final NetconfStateSchemas.NetconfStateSchemasResolver stateSchemasResolver;

        public SchemaResourcesDTO(final SchemaSourceRegistry schemaRegistry, final SchemaContextFactory schemaContextFactory, final NetconfStateSchemas.NetconfStateSchemasResolver stateSchemasResolver) {
            this.schemaRegistry = Preconditions.checkNotNull(schemaRegistry);
            this.schemaContextFactory = Preconditions.checkNotNull(schemaContextFactory);
            this.stateSchemasResolver = Preconditions.checkNotNull(stateSchemasResolver);
        }

        public SchemaSourceRegistry getSchemaRegistry() {
            return schemaRegistry;
        }

        public SchemaContextFactory getSchemaContextFactory() {
            return schemaContextFactory;
        }

        public NetconfStateSchemas.NetconfStateSchemasResolver getStateSchemasResolver() {
            return stateSchemasResolver;
        }
    }

    /**
     * Schema building callable.
     */
    private static class DeviceSourcesResolver implements Callable<DeviceSources> {

        private final NetconfDeviceRpc deviceRpc;
        private final NetconfSessionPreferences remoteSessionCapabilities;
        private final RemoteDeviceId id;
        private final NetconfStateSchemas.NetconfStateSchemasResolver stateSchemasResolver;

        DeviceSourcesResolver(final NetconfDeviceRpc deviceRpc, final NetconfSessionPreferences remoteSessionCapabilities,
                                     final RemoteDeviceId id, final NetconfStateSchemas.NetconfStateSchemasResolver stateSchemasResolver) {
            this.deviceRpc = deviceRpc;
            this.remoteSessionCapabilities = remoteSessionCapabilities;
            this.id = id;
            this.stateSchemasResolver = stateSchemasResolver;
        }

        public DeviceSourcesResolver(final NetconfSessionPreferences remoteSessionCapabilities, final RemoteDeviceId id, final NetconfStateSchemas.NetconfStateSchemasResolver stateSchemasResolver, final NetconfDeviceRpc rpcForMonitoring) {
            this(rpcForMonitoring, remoteSessionCapabilities, id, stateSchemasResolver);
        }

        @Override
        public DeviceSources call() throws Exception {
            final NetconfStateSchemas availableSchemas = stateSchemasResolver.resolve(deviceRpc, remoteSessionCapabilities, id);
            LOG.debug("{}: Schemas exposed by ietf-netconf-monitoring: {}", id, availableSchemas.getAvailableYangSchemasQNames());

            final Set<QName> requiredSources = Sets.newHashSet(remoteSessionCapabilities.getModuleBasedCaps());
            final Set<QName> providedSources = availableSchemas.getAvailableYangSchemasQNames();

            final Set<QName> requiredSourcesNotProvided = Sets.difference(requiredSources, providedSources);
            if (!requiredSourcesNotProvided.isEmpty()) {
                LOG.warn("{}: Netconf device does not provide all yang models reported in hello message capabilities, required but not provided: {}",
                        id, requiredSourcesNotProvided);
                LOG.warn("{}: Attempting to build schema context from required sources", id);
            }

            // Here all the sources reported in netconf monitoring are merged with those reported in hello.
            // It is necessary to perform this since submodules are not mentioned in hello but still required.
            // This clashes with the option of a user to specify supported yang models manually in configuration for netconf-connector
            // and as a result one is not able to fully override yang models of a device. It is only possible to add additional models.
            final Set<QName> providedSourcesNotRequired = Sets.difference(providedSources, requiredSources);
            if (!providedSourcesNotRequired.isEmpty()) {
                LOG.warn("{}: Netconf device provides additional yang models not reported in hello message capabilities: {}",
                        id, providedSourcesNotRequired);
                LOG.warn("{}: Adding provided but not required sources as required to prevent failures", id);
                LOG.debug("{}: Netconf device reported in hello: {}", id, requiredSources);
                requiredSources.addAll(providedSourcesNotRequired);
            }

            return new DeviceSources(requiredSources, providedSources);
        }
    }

    /**
     * Contains RequiredSources - sources from capabilities.
     */
    private static final class DeviceSources {
        private final Set<QName> requiredSources;
        private final Set<QName> providedSources;

        public DeviceSources(final Set<QName> requiredSources, final Set<QName> providedSources) {
            this.requiredSources = requiredSources;
            this.providedSources = providedSources;
        }

        public Set<QName> getRequiredSourcesQName() {
            return requiredSources;
        }

        public Set<QName> getProvidedSourcesQName() {
            return providedSources;
        }

        public Collection<SourceIdentifier> getRequiredSources() {
            return Collections2.transform(requiredSources, QNAME_TO_SOURCE_ID_FUNCTION);
        }

        public Collection<SourceIdentifier> getProvidedSources() {
            return Collections2.transform(providedSources, QNAME_TO_SOURCE_ID_FUNCTION);
        }

    }

    /**
     * Schema builder that tries to build schema context from provided sources or biggest subset of it.
     */
    private final class SchemaSetup implements Runnable {
        private final DeviceSources deviceSources;
        private final NetconfSessionPreferences remoteSessionCapabilities;
        private final RemoteDeviceCommunicator<NetconfMessage> listener;
        private final NetconfDeviceCapabilities capabilities;

        public SchemaSetup(final DeviceSources deviceSources, final NetconfSessionPreferences remoteSessionCapabilities, final RemoteDeviceCommunicator<NetconfMessage> listener) {
            this.deviceSources = deviceSources;
            this.remoteSessionCapabilities = remoteSessionCapabilities;
            this.listener = listener;
            this.capabilities = remoteSessionCapabilities.getNetconfDeviceCapabilities();
        }

        @Override
        public void run() {
            setUpSchema(deviceSources.getRequiredSources());
        }

        /**
         * Build schema context, in case of success or final failure notify device
         */
        private void setUpSchema(Collection<SourceIdentifier> requiredSources) {
            while (!requiredSources.isEmpty()) {
                LOG.trace("{}: Trying to build schema context from {}", id, requiredSources);
                try {
                    final CheckedFuture<SchemaContext, SchemaResolutionException> schemaBuilderFuture = schemaContextFactory.createSchemaContext(requiredSources);
                    final SchemaContext result = schemaBuilderFuture.checkedGet();
                    LOG.debug("{}: Schema context built successfully from {}", id, requiredSources);
                    final Collection<QName> filteredQNames = Sets.difference(deviceSources.getRequiredSourcesQName(), capabilities.getUnresolvedCapabilites().keySet());
                    capabilities.addCapabilities(filteredQNames);
                    capabilities.addNonModuleBasedCapabilities(remoteSessionCapabilities.getNonModuleCaps());
                    handleSalInitializationSuccess(result, remoteSessionCapabilities, getDeviceSpecificRpc(result));
                    return;
                } catch (Throwable t) {
                    if (t instanceof MissingSchemaSourceException){
                        requiredSources = handleMissingSchemaSourceException(requiredSources, (MissingSchemaSourceException) t);
                    } else if (t instanceof SchemaResolutionException) {
                        // schemaBuilderFuture.checkedGet() throws only SchemaResolutionException
                        // that might be wrapping a MissingSchemaSourceException so we need to look
                        // at the cause of the exception to make sure we don't misinterpret it.
                        if (t.getCause() instanceof MissingSchemaSourceException) {
                            requiredSources = handleMissingSchemaSourceException(requiredSources, (MissingSchemaSourceException) t.getCause());
                            continue;
                        }
                        requiredSources = handleSchemaResolutionException(requiredSources, (SchemaResolutionException) t);
                    } else {
                        // unknown error, fail
                        handleSalInitializationFailure(t, listener);
                        return;
                    }
                }
            }
            // No more sources, fail
            final IllegalStateException cause = new IllegalStateException(id + ": No more sources for schema context");
            handleSalInitializationFailure(cause, listener);
            salFacade.onDeviceFailed(cause);
        }

        private Collection<SourceIdentifier> handleMissingSchemaSourceException(Collection<SourceIdentifier> requiredSources, final MissingSchemaSourceException t) {
            // In case source missing, try without it
            final SourceIdentifier missingSource = t.getSourceId();
            LOG.warn("{}: Unable to build schema context, missing source {}, will reattempt without it", id, missingSource);
            LOG.debug("{}: Unable to build schema context, missing source {}, will reattempt without it", t);
            final Collection<QName> qNameOfMissingSource = getQNameFromSourceIdentifiers(Sets.newHashSet(missingSource));
            if (!qNameOfMissingSource.isEmpty()) {
                capabilities.addUnresolvedCapabilities(qNameOfMissingSource, UnavailableCapability.FailureReason.MissingSource);
            }
            return stripMissingSource(requiredSources, missingSource);
        }

        private Collection<SourceIdentifier> handleSchemaResolutionException(Collection<SourceIdentifier> requiredSources, final SchemaResolutionException resolutionException) {
            // In case resolution error, try only with resolved sources
            final Set<SourceIdentifier> unresolvedSources = resolutionException.getUnsatisfiedImports().keySet();
            capabilities.addUnresolvedCapabilities(getQNameFromSourceIdentifiers(unresolvedSources), UnavailableCapability.FailureReason.UnableToResolve);
            LOG.warn("{}: Unable to build schema context, unsatisfied imports {}, will reattempt with resolved only", id, resolutionException.getUnsatisfiedImports());
            LOG.debug("{}: Unable to build schema context, unsatisfied imports {}, will reattempt with resolved only", resolutionException);
            return resolutionException.getResolvedSources();
        }

        protected NetconfDeviceRpc getDeviceSpecificRpc(final SchemaContext result) {
            return new NetconfDeviceRpc(result, listener, new NetconfMessageTransformer(result, true));
        }

        private Collection<SourceIdentifier> stripMissingSource(final Collection<SourceIdentifier> requiredSources, final SourceIdentifier sIdToRemove) {
            final LinkedList<SourceIdentifier> sourceIdentifiers = Lists.newLinkedList(requiredSources);
            final boolean removed = sourceIdentifiers.remove(sIdToRemove);
            Preconditions.checkState(removed, "{}: Trying to remove {} from {} failed", id, sIdToRemove, requiredSources);
            return sourceIdentifiers;
        }

        private Collection<QName> getQNameFromSourceIdentifiers(final Collection<SourceIdentifier> identifiers) {
            final Collection<QName> qNames = Collections2.transform(identifiers, new Function<SourceIdentifier, QName>() {
                @Override
                public QName apply(final SourceIdentifier sourceIdentifier) {
                    return getQNameFromSourceIdentifier(sourceIdentifier);
                }
            });

            if (qNames.isEmpty()) {
                LOG.debug("{}: Unable to map any source identifiers to a capability reported by device : {}", id, identifiers);
            }
            return Collections2.filter(qNames, Predicates.notNull());
        }

        private QName getQNameFromSourceIdentifier(final SourceIdentifier identifier) {
            // Required sources are all required and provided merged in DeviceSourcesResolver
            for (final QName qname : deviceSources.getRequiredSourcesQName()) {
                if(qname.getLocalName().equals(identifier.getName()) == false) {
                    continue;
                }

                if(identifier.getRevision().equals(SourceIdentifier.NOT_PRESENT_FORMATTED_REVISION) &&
                        qname.getRevision() == null) {
                    return qname;
                }

                if (qname.getFormattedRevision().equals(identifier.getRevision())) {
                    return qname;
                }
            }
            LOG.warn("Unable to map identifier to a devices reported capability: {} Available: {}",identifier, deviceSources.getRequiredSourcesQName());
            // return null since we cannot find the QName, this capability will be removed from required sources and not reported as unresolved-capability
            return null;
        }
    }
}
