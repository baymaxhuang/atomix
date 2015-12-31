/*
 * Copyright 2015 the original author or authors.
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
 * limitations under the License
 */
package io.atomix.resource;

import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.transport.Transport;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.Listener;
import io.atomix.catalyst.util.concurrent.ThreadContext;
import io.atomix.copycat.client.Command;
import io.atomix.copycat.client.CopycatClient;
import io.atomix.copycat.client.Query;
import io.atomix.copycat.client.session.Session;
import io.atomix.manager.CloseResource;
import io.atomix.manager.DeleteResource;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Resource context.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class InstanceClient implements CopycatClient {
  private long resource;
  private final CopycatClient client;
  private final Transport transport;
  private final InstanceFactory factory;
  private InstanceSession session;
  private State state;
  private final Listener<State> changeListener;
  private final Map<String, Set<EventListener>> eventListeners = new ConcurrentHashMap<>();
  private final Map<String, Listener<InstanceEvent<?>>> listeners = new ConcurrentHashMap<>();
  private final Set<StateChangeListener> changeListeners = new CopyOnWriteArraySet<>();

  public InstanceClient(CopycatClient client, Transport transport, InstanceFactory factory) {
    this.client = Assert.notNull(client, "client");
    this.transport = Assert.notNull(transport, "transport");
    this.factory = Assert.notNull(factory, "factory");
    this.state = client.state();
    this.changeListener = client.onStateChange(this::onStateChange);
  }

  /**
   * Resets the instance resource ID.
   */
  InstanceClient reset(long resourceId) {
    this.resource = resourceId;
    this.session = new InstanceSession(resourceId, client.session(), client.context());;
    return this;
  }

  @Override
  public State state() {
    return client.state();
  }

  /**
   * Called when the parent client's state changes.
   */
  private void onStateChange(State state) {
    this.state = client.state();
    changeListeners.forEach(l -> l.accept(state));
  }

  @Override
  public Listener<State> onStateChange(Consumer<State> callback) {
    return client.onStateChange(callback);
  }

  @Override
  public ThreadContext context() {
    return client.context();
  }

  @Override
  public Transport transport() {
    return transport;
  }

  @Override
  public Session session() {
    return session;
  }

  @Override
  public Serializer serializer() {
    return client.serializer();
  }

  @Override
  public <T> CompletableFuture<T> submit(Command<T> command) {
    if (command instanceof ResourceStateMachine.DeleteCommand) {
      return client.submit(new InstanceCommand<>(resource, command)).thenCompose(v -> client.submit(new DeleteResource(resource))).thenApply(result -> null);
    }
    return client.submit(new InstanceCommand<>(resource, command));
  }

  @Override
  public <T> CompletableFuture<T> submit(Query<T> query) {
    return client.submit(new InstanceQuery<>(resource, query));
  }

  @Override
  public Listener<Void> onEvent(String event, Runnable callback) {
    return onEvent(event, v -> callback.run());
  }

  @Override
  @SuppressWarnings("unchecked")
  public synchronized <T> Listener<T> onEvent(String event, Consumer<T> listener) {
    Assert.notNull(event, "event");
    Assert.notNull(listener, "listener");

    Set<EventListener> listeners = eventListeners.get(event);
    if (listeners == null) {
      listeners = new HashSet<>();
      eventListeners.put(event, listeners);
      this.listeners.put(event, client.onEvent(event, message -> handleEvent(event, message)));
    }

    EventListener context = new EventListener(event, listener);
    listeners.add(context);
    return context;
  }

  /**
   * Handles receiving a resource message.
   */
  @SuppressWarnings("unchecked")
  private void handleEvent(String event, InstanceEvent<?> message) {
    if (message.resource() == resource) {
      Set<EventListener> listeners = eventListeners.get(event);
      if (listeners != null) {
        for (EventListener listener : listeners) {
          listener.accept(message.message());
        }
      }
    }
  }

  @Override
  public CompletableFuture<CopycatClient> open() {
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public boolean isOpen() {
    return client.isOpen();
  }

  @Override
  public CompletableFuture<CopycatClient> recover() {
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public CompletableFuture<Void> close() {
    return client.submit(new CloseResource(resource))
      .whenComplete((result, error) -> {
        factory.instances.remove(resource);
        changeListener.close();
        onStateChange(State.CLOSED);
      });
  }

  @Override
  public boolean isClosed() {
    return client.isClosed();
  }

  @Override
  public String toString() {
    return String.format("%s[resource=%d]", getClass().getSimpleName(), resource);
  }

  /**
   * Receive listener context.
   */
  private class EventListener<T> implements Listener<T> {
    private final String event;
    private final Consumer<T> listener;

    private EventListener(String event, Consumer<T> listener) {
      this.event = event;
      this.listener = listener;
    }

    @Override
    public void accept(T event) {
      listener.accept(event);
    }

    @Override
    public void close() {
      synchronized (InstanceClient.this) {
        Set<EventListener> listeners = eventListeners.get(event);
        if (listeners != null) {
          listeners.remove(this);
          if (listeners.isEmpty()) {
            eventListeners.remove(event);
            Listener listener = InstanceClient.this.listeners.remove(event);
            if (listener != null) {
              listener.close();
            }
          }
        }
      }
    }
  }

  /**
   * Resource state change listener.
   */
  private class StateChangeListener implements Listener<State> {
    private final Consumer<State> callback;

    private StateChangeListener(Consumer<State> callback) {
      this.callback = callback;
      changeListeners.add(this);
    }

    @Override
    public void accept(State state) {
      callback.accept(state);
    }

    @Override
    public void close() {
      changeListeners.remove(this);
    }
  }

}
