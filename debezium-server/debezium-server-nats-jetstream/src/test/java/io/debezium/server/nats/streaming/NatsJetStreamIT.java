/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.server.nats.streaming;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.enterprise.event.Observes;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.Subscription;
import org.awaitility.Awaitility;
import org.fest.assertions.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import io.debezium.server.events.ConnectorCompletedEvent;
import io.debezium.server.events.ConnectorStartedEvent;
import io.debezium.testing.testcontainers.PostgresTestResourceLifecycleManager;
import io.debezium.util.Testing;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration test that verifies basic reading from PostgreSQL database and writing to NATS Streaming subject.
 *
 * @author Thiago Avancini
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResourceLifecycleManager.class)
@QuarkusTestResource(NatsJetStreamTestResourceLifecycleManager.class)
class NatsJetStreamIT {
    private static final int MESSAGE_COUNT = 4;
    private static final String SUBJECT_NAME = "testc.inventory.customers";

    protected  static  Connection nc;
    protected static JetStream js;
    protected static Subscription subscription;

    {
        Testing.Files.delete(NatsJetStreamTestConfigSource.OFFSET_STORE_PATH);
        Testing.Files.createTestingFile(NatsJetStreamTestConfigSource.OFFSET_STORE_PATH);
    }

    private static final List<Message> messages = Collections.synchronizedList(new ArrayList<>());

    void setupDependencies(@Observes ConnectorStartedEvent event) {
        Testing.Print.enable();

        // Setup NATS Streaming connection
        Options jsOptions = new Options.Builder()
                .connectionName(NatsJetStreamTestResourceLifecycleManager.getNatsStreamingContainerUrl())
                .build();
        try {
            Connection nc = Nats.connect(NatsJetStreamTestResourceLifecycleManager.getNatsStreamingContainerUrl());
            js = nc.jetStream();
        }
        catch (Exception e) {
            Testing.print("Could not connect to NATS Streaming");
        }

        // Setup message handler
        try {
            subscription = js.subscribe(SUBJECT_NAME, nc.createDispatcher(), new MessageHandler() {
                public void onMessage(Message m) {
                    messages.add(m);
                }
            }, true);
        }
        catch (Exception e) {
            Testing.print("Could not register message handler");
        }
    }

    void connectorCompleted(@Observes ConnectorCompletedEvent event) throws Exception {
        if (!event.isSuccess()) {
            throw (Exception) event.getError().get();
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    @Test
    void testNatsStreaming() throws Exception {
        Awaitility.await().atMost(Duration.ofSeconds(NatsJetStreamTestConfigSource.waitForSeconds())).until(() -> messages.size() >= MESSAGE_COUNT);
        Assertions.assertThat(messages.size() >= MESSAGE_COUNT).isTrue();
    }
}
