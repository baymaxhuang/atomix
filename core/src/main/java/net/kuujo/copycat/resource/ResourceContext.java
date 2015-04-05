/*
 * Copyright 2014 the original author or authors.
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
package net.kuujo.copycat.resource;

import net.kuujo.copycat.ConfigurationException;
import net.kuujo.copycat.cluster.Cluster;
import net.kuujo.copycat.cluster.ClusterConfig;
import net.kuujo.copycat.cluster.internal.ManagedCluster;
import net.kuujo.copycat.io.Buffer;
import net.kuujo.copycat.io.serializer.CopycatSerializer;
import net.kuujo.copycat.raft.CommitHandler;
import net.kuujo.copycat.raft.Consistency;
import net.kuujo.copycat.raft.RaftConfig;
import net.kuujo.copycat.raft.RaftContext;
import net.kuujo.copycat.raft.protocol.DeleteRequest;
import net.kuujo.copycat.raft.protocol.ReadRequest;
import net.kuujo.copycat.raft.protocol.Response;
import net.kuujo.copycat.raft.protocol.WriteRequest;
import net.kuujo.copycat.util.Managed;
import net.kuujo.copycat.util.concurrent.Futures;
import net.kuujo.copycat.util.concurrent.NamedThreadFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Default resource context.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class ResourceContext implements Managed<ResourceContext> {
  private final String name;
  private final ResourceConfig<?> config;
  private final CopycatSerializer serializer;
  private final ScheduledExecutorService scheduler;
  private final Executor executor;
  private final ManagedCluster cluster;
  private final RaftContext context;
  private CompletableFuture<ResourceContext> openFuture;
  private CompletableFuture<Void> closeFuture;
  private volatile boolean open;

  public ResourceContext(ResourceConfig<?> config, ClusterConfig cluster) {
    this(config, cluster, Executors.newSingleThreadExecutor(new NamedThreadFactory(config.getName())));
  }

  public ResourceContext(ResourceConfig<?> config, ClusterConfig cluster, Executor executor) {
    this(config, cluster, Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("copycat-" + config.getName() + "-%d")), executor);
  }

  public ResourceContext(ResourceConfig<?> config, ClusterConfig cluster, ScheduledExecutorService scheduler, Executor executor) {
    if (config == null)
      throw new NullPointerException("config cannot be null");
    if (cluster == null)
      throw new NullPointerException("cluster cannot be null");
    if (scheduler == null)
      throw new NullPointerException("scheduler cannot be null");
    if (executor == null)
      throw new NullPointerException("executor cannot be null");
    this.config = config.resolve();
    this.name = this.config.getName();
    this.serializer = this.config.getSerializer();
    this.scheduler = scheduler;
    this.executor = executor;
    this.context = new RaftContext(createRaftConfig(config, cluster), scheduler);
    this.cluster = new ManagedCluster(cluster, this);
  }

  /**
   * Creates a Raft configuration from a resource and cluster configuration.
   */
  private static RaftConfig createRaftConfig(ResourceConfig<?> config, ClusterConfig cluster) {
    RaftConfig raft = new RaftConfig()
      .withId(cluster.getLocalMember().getId())
      .withHeartbeatInterval(config.getHeartbeatInterval())
      .withElectionTimeout(config.getElectionTimeout())
      .withLog(config.getLog());

    if (config.getReplicas().isEmpty()) {
      cluster.getMembers().forEach(m -> raft.addMember(m.getId()));
    } else {
      for (int id : config.getReplicas()) {
        if (!cluster.hasMember(id)) {
          throw new ConfigurationException(String.format("Invalid cluster member: %s", id));
        }
        raft.addMember(id);
      }
    }
    return raft;
  }

  /**
   * Returns the resource name.
   *
   * @return The resource name.
   */
  public String name() {
    return name;
  }

  /**
   * Returns the resource configuration.
   *
   * @return The resource configuration.
   */
  @SuppressWarnings("unchecked")
  public <T extends ResourceConfig<?>> T config() {
    return (T) config;
  }

  /**
   * Returns the resource status.
   *
   * @return The resource status.
   */
  public ResourceState state() {
    return context.isRecovering() ? ResourceState.RECOVER : ResourceState.HEALTHY;
  }

  /**
   * Returns the Raft context.
   *
   * @return The Raft context.
   */
  public RaftContext raft() {
    return context;
  }

  /**
   * Returns the Copycat cluster.
   *
   * @return The Copycat cluster.
   */
  public Cluster cluster() {
    return cluster;
  }

  /**
   * Returns the internal scheduler.
   *
   * @return The internal scheduler.
   */
  public ScheduledExecutorService scheduler() {
    return scheduler;
  }

  /**
   * Returns the context serializer.
   *
   * @return The context serializer.
   */
  public CopycatSerializer serializer() {
    return serializer;
  }

  /**
   * Returns the context executor.
   *
   * @return The context executor.
   */
  public Executor executor() {
    return executor;
  }

  /**
   * Registers an entry commit handler on the context.
   *
   * @param handler The entry commit handler.
   * @return The Copycat context.
   */
  public synchronized ResourceContext commitHandler(CommitHandler handler) {
    context.commitHandler(handler);
    return this;
  }

  /**
   * Submits a synchronous entry to the context.
   *
   * @param key The entry key.
   * @param entry The entry to query.
   * @return A completable future to be completed once the cluster has been synchronized.
   */
  public synchronized CompletableFuture<Buffer> read(Buffer key, Buffer entry, Consistency consistency) {
    if (!open)
      return Futures.exceptionalFuture(new IllegalStateException("Context not open"));

    CompletableFuture<Buffer> future = new CompletableFuture<>();
    ReadRequest request = ReadRequest.builder()
      .withId(context.getLocalMember().id())
      .withKey(key)
      .withEntry(entry)
      .withConsistency(consistency)
      .build();
    context.read(request).whenComplete((response, error) -> {
      if (error == null) {
        if (response.status() == Response.Status.OK) {
          future.complete(response.result());
        } else {
          future.completeExceptionally(response.error().createException());
        }
      } else {
        future.completeExceptionally(error);
      }
    });
    return future;
  }

  /**
   * Submits a persistent entry to the context.
   *
   * @param key The entry key.
   * @param entry The entry to commit.
   * @return A completable future to be completed once the entry has been committed.
   */
  public synchronized CompletableFuture<Buffer> write(Buffer key, Buffer entry) {
    if (!open)
      return Futures.exceptionalFuture(new IllegalStateException("Context not open"));

    CompletableFuture<Buffer> future = new CompletableFuture<>();
    WriteRequest request = WriteRequest.builder()
      .withId(context.getLocalMember().id())
      .withKey(key)
      .withEntry(entry)
      .build();
    context.write(request).whenComplete((response, error) -> {
      if (error == null) {
        if (response.status() == Response.Status.OK) {
          future.complete(response.result());
        } else {
          future.completeExceptionally(response.error().createException());
        }
      } else {
        future.completeExceptionally(error);
      }
    });
    return future;
  }

  /**
   * Submits a delete entry to the context.
   *
   * @param key The key to delete.
   * @return A completable future to be completed once the entry has been committed.
   */
  public synchronized CompletableFuture<Buffer> delete(Buffer key) {
    if (!open)
      return Futures.exceptionalFuture(new IllegalStateException("Context not open"));

    CompletableFuture<Buffer> future = new CompletableFuture<>();
    DeleteRequest request = DeleteRequest.builder()
      .withId(context.getLocalMember().id())
      .withKey(key)
      .build();
    context.delete(request).whenComplete((response, error) -> {
      if (error == null) {
        if (response.status() == Response.Status.OK) {
          future.complete(response.result());
        } else {
          future.completeExceptionally(response.error().createException());
        }
      } else {
        future.completeExceptionally(error);
      }
    });
    return future;
  }

  @Override
  public synchronized CompletableFuture<ResourceContext> open() {
    if (open)
      return CompletableFuture.completedFuture(this);

    if (openFuture == null) {
      openFuture = new CompletableFuture<>();
      scheduler.execute(() -> {
        cluster.open().thenCompose(v -> context.open()).whenComplete((result, error) -> {
          CompletableFuture<ResourceContext> openFuture = this.openFuture;
          if (openFuture != null) {
            synchronized (this) {
              this.openFuture = null;
              if (error == null) {
                open = true;
                openFuture.complete(this);
              } else {
                openFuture.completeExceptionally(error);
              }
            }
          }
        });
      });
    }
    return openFuture;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public synchronized CompletableFuture<Void> close() {
    if (!open)
      return CompletableFuture.completedFuture(null);

    if (closeFuture == null) {
      closeFuture = new CompletableFuture<>();
      scheduler.execute(() -> {
        context.close().thenCompose(v -> cluster.close()).whenComplete((result, error) -> {
          CompletableFuture<Void> closeFuture = this.closeFuture;
          if (closeFuture != null) {
            synchronized (this) {
              this.closeFuture = null;
              if (error == null) {
                open = false;
                scheduler.shutdown();
                closeFuture.complete(null);
              } else {
                closeFuture.completeExceptionally(error);
              }
            }
          }
        });
      });
    }
    return closeFuture;
  }

  @Override
  public boolean isClosed() {
    return !open;
  }

}
