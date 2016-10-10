/*
 * Copyright (c) 2016 The original author or authors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.engagingspaces.graphql.servicediscovery.publisher;

import graphql.schema.GraphQLSchema;
import io.engagingspaces.graphql.events.SchemaPublishedHandler;
import io.engagingspaces.graphql.events.SchemaUnpublishedHandler;
import io.engagingspaces.graphql.schema.SchemaDefinition;
import io.engagingspaces.graphql.schema.SchemaMetadata;
import io.engagingspaces.graphql.servicediscovery.service.GraphQLService;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.servicediscovery.ServiceDiscovery;
import io.vertx.servicediscovery.ServiceDiscoveryOptions;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Interface that facilitates easy publishing and management of {@link SchemaDefinition}s on vert.x service discoveries.
 * <p>
 * Implementors only need to initialize the {@link SchemaPublisher#schemaRegistrar()} with a new instance, and are then
 * ready to publishAll publishing schema's to designated service discoveries.
 * <p>
 * Service discovery instances are automatically created and closed as needed.
 *
 * @author <a href="https://github.com/aschrijver/">Arnold Schrijver</a>
 */
public interface SchemaPublisher extends
        SchemaPublishedHandler<SchemaRegistration>, SchemaUnpublishedHandler<SchemaRegistration> {

    /**
     * Publishes the provided schema definitions to the specified service discovery.
     * <p>
     * Upon success a list of {@link SchemaRegistration}s is returned in the result handler.
     *
     * @param options       the service discovery options
     * @param resultHandler the result handler
     * @param schemas       the GraphQL schema's to publish
     */
    default void publishAll(ServiceDiscoveryOptions options,
                            Handler<AsyncResult<List<SchemaRegistration>>> resultHandler,
                            GraphQLSchema... schemas) {

        Objects.requireNonNull(resultHandler, "Publication result handler cannot be null");
        if (schemas == null || schemas.length == 0) {
            resultHandler.handle(Future.failedFuture("Nothing to publish. No schema definitions provided"));
            return;
        }
        List<Future> futures = new ArrayList<>();
        Arrays.asList(schemas).forEach(schema ->
                publish(options, schema, rh -> futures.add(
                        rh.succeeded() ? Future.succeededFuture(rh.result()) : Future.failedFuture(rh.cause()))));

        CompositeFuture.all(futures).setHandler(rh -> {
            if (rh.failed()) {
                resultHandler.handle(Future.failedFuture(rh.cause()));
                return;
            }
            CompositeFuture composite = rh.result();
            List<SchemaRegistration> published = composite.list();
            if (published.size() != schemas.length) {
                List<Throwable> errors = rh.result().<Future<Void>>list().stream()
                        .filter(Future::failed)
                        .map(Future::cause)
                        .collect(Collectors.toList());
                resultHandler.handle(Future.failedFuture(new PartialPublishException(errors)));
            } else {
                resultHandler.handle(Future.succeededFuture(published));
            }
        });
    }

    /**
     * Publishes the schema definition to the service discovery indicated by the provided schema publisher options.
     *
     * @param options       the service discovery options
     * @param schema        the GraphQL schema to publish
     * @param resultHandler the result handler
     */
    default void publish(ServiceDiscoveryOptions options, GraphQLSchema schema,
                         Handler<AsyncResult<SchemaRegistration>> resultHandler) {
        publish(options, schema, SchemaMetadata.create(), resultHandler);
    }

    /**
     * Publishes the schema definition and metadata to the service discovery indicated by
     * the provided schema publisher options.
     *
     * @param options       the service discovery options
     * @param schema        the GraphQL schema to publish
     * @param metadata      the metadata to pass to the published discovery record
     * @param resultHandler the result handler
     */
    default void publish(ServiceDiscoveryOptions options, GraphQLSchema schema, SchemaMetadata metadata,
                         Handler<AsyncResult<SchemaRegistration>> resultHandler) {
        if (schema == null) {
            resultHandler.handle(Future.failedFuture("Nothing to publish. No schema definition provided"));
            return;
        }
        Objects.requireNonNull(schema, "GraphQL schema cannot be null");
        Objects.requireNonNull(options, "Schema discovery options cannot be null");
        Objects.requireNonNull(resultHandler, "Publication result handler cannot be null");

        SchemaDefinition definition = SchemaDefinition.createInstance(schema, metadata);
        if (schemaRegistrar().findRegistration(options.getName(), definition.schemaName()).isPresent()) {
            resultHandler.handle(Future.failedFuture("Schema '" +
                    definition.schemaName() + "' was already published to: " + options.getName()));
            return;
        }
        metadata.put("publisherId", schemaRegistrar().getPublisherId());
        ServiceDiscovery discovery = schemaRegistrar().getOrCreateDiscovery(options);

        GraphQLService.publish(schemaRegistrar().getVertx(), discovery, definition, rh -> {
            if (rh.succeeded()) {
                SchemaRegistration registration = schemaRegistrar().register(rh.result(), options, this, this);
                resultHandler.handle(Future.succeededFuture(registration));
            } else {
                resultHandler.handle(Future.failedFuture(rh.cause()));
            }
        });
    }

    /**
     * Un-publishes the schema definition given its schema registration.
     *
     * @param registration  the schema registration
     * @param resultHandler the result handler
     */
    default void unpublish(SchemaRegistration registration, Handler<AsyncResult<Void>> resultHandler) {
        Objects.requireNonNull(resultHandler, "Un-publication result handler cannot be null");
        GraphQLService.unpublish(registration, rh -> {
            if (rh.succeeded()) {
                schemaRegistrar().unregister(registration);
                resultHandler.handle(Future.succeededFuture());
            } else {
                resultHandler.handle(Future.failedFuture(rh.cause()));
            }
        });
    }

    /**
     * Gets the schema definitions that are registered with this publisher.
     *
     * @return the set of schema registrations
     */
    default List<SchemaRegistration> registeredSchemas() {
        return Collections.unmodifiableList(schemaRegistrar().registrations());
    }

    /**
     * Un-publishes all registered schema and closes the schema publisher.
     *
     * @param publisher     the schema publisher
     * @param resultHandler the result handler
     */
    static void close(SchemaPublisher publisher, Handler<AsyncResult<Void>> resultHandler) {
        Objects.requireNonNull(publisher, "Schema publisher cannot be null");
        publisher.schemaRegistrar().close(publisher::unpublish, resultHandler);
    }

    /**
     * Gets the registrar that is used to manage publisher internal state.
     * <p>
     * Clients only need to create and return a valid instance when implementing a {@link SchemaPublisher}. The
     * registrar is controlled by the publisher interface. You only need to keep a reference to it for as long as it
     * is used.
     *
     * @return the schema registrar
     */
    SchemaRegistrar schemaRegistrar();

    /**
     * Gets the service discovery instance with the specified name, if the discoverer is managing it.
     *
     * @param discoveryName the name of the service discovery, or {@code null} to get the default service discovery
     * @return optional that contains the service discovery reference if found, otherwise empty
     */
    default Optional<ServiceDiscovery> getDiscovery(String discoveryName) {
        return Optional.ofNullable(schemaRegistrar().getDiscovery(discoveryName));
    }

    /**
     * Gets the service discovery names of the discovery instances this publisher is currently publishing to.
     *
     * @return the list of service discovery names
     */
    default List<String> managedDiscoveries() {
        return schemaRegistrar().serviceDiscoveryNames();
    }

    /**
     * Gets the name of the publisher.
     * <p>
     * The name is used as the {@code publisherId} of schema definitions that are registered with this publisher.
     * If not overridden in sub-classes the publisherId is initialized with a random {@link UUID}.
     *
     * @return the publisher name
     */
    default String name() {
        // Can be overridden in sub-classes
        return null;
    }
}
