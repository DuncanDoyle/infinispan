package org.infinispan.notifications.cachelistener.cluster;

import org.infinispan.Cache;
import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.context.InvocationContext;
import org.infinispan.distribution.MagicKey;
import org.infinispan.filter.CollectionKeyFilter;
import org.infinispan.filter.Converter;
import org.infinispan.filter.KeyFilterAsKeyValueFilter;
import org.infinispan.filter.KeyValueFilter;
import org.infinispan.manager.CacheContainer;
import org.infinispan.metadata.Metadata;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.CacheNotifier;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryModified;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryRemoved;
import org.infinispan.notifications.cachelistener.event.CacheEntryEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryModifiedEvent;
import org.infinispan.notifications.cachelistener.event.Event;
import org.infinispan.notifications.cachemanagerlistener.CacheManagerNotifier;
import org.infinispan.remoting.transport.Address;
import org.infinispan.statetransfer.StateProvider;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.CheckPoint;
import org.infinispan.transaction.TransactionMode;
import org.mockito.AdditionalAnswers;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.annotations.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.eq;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

/**
 * Base class to be used for cluster listener tests for both tx and nontx caches
 *
 * @author wburns
 * @since 7.0
 */
public abstract class AbstractClusterListenerUtilTest extends MultipleCacheManagersTest {
   protected final static String CACHE_NAME = "cluster-listener";
   protected final static String FIRST_VALUE = "first-value";
   protected final static String SECOND_VALUE = "second-value";

   protected ConfigurationBuilder builderUsed;
   protected final boolean tx;
   protected final CacheMode cacheMode;

   protected AbstractClusterListenerUtilTest(boolean tx, CacheMode cacheMode) {
      // Have to have cleanup after each method since listeners need to be cleaned up
      cleanup = CleanupPhase.AFTER_METHOD;
      this.tx = tx;
      this.cacheMode = cacheMode;
   }

   @Override
   protected void createCacheManagers() throws Throwable {
      builderUsed = new ConfigurationBuilder();
      builderUsed.clustering().cacheMode(cacheMode);
      if (tx) {
         builderUsed.transaction().transactionMode(TransactionMode.TRANSACTIONAL);
      }
      createClusteredCaches(3, CACHE_NAME, builderUsed);
   }

   @Listener(clustered = true)
   protected class ClusterListener {
      List<CacheEntryEvent> events = Collections.synchronizedList(new ArrayList<CacheEntryEvent>());

      @CacheEntryCreated
      @CacheEntryModified
      @CacheEntryRemoved
      public void onCacheEvent(CacheEntryEvent event) {
         log.debugf("Adding new cluster event %s", event);
         events.add(event);
      }

      protected boolean hasIncludeState() {
         return false;
      }
   }

   @Listener(clustered = true, includeCurrentState = true)
   protected class ClusterListenerWithIncludeCurrentState extends ClusterListener {
      protected boolean hasIncludeState() {
         return true;
      }
   }

   protected static class LifespanFilter<K, V> implements KeyValueFilter<K, V>, Serializable {
      public LifespanFilter(long lifespan) {
         this.lifespan = lifespan;
      }

      private final long lifespan;

      @Override
      public boolean accept(K key, V value, Metadata metadata) {
         if (metadata == null) {
            return false;
         }
         // Only accept entities with a lifespan longer than ours
         return metadata.lifespan() > lifespan;
      }
   }

   protected static class LifespanConverter implements Converter<Object, String, Object>, Serializable {
      public LifespanConverter(boolean returnOriginalValueOrNull, long lifespanThreshold) {
         this.returnOriginalValueOrNull = returnOriginalValueOrNull;
         this.lifespanThreshold = lifespanThreshold;
      }

      private final boolean returnOriginalValueOrNull;
      private final long lifespanThreshold;

      @Override
      public Object convert(Object key, String value, Metadata metadata) {
         if (metadata != null) {
            long metaLifespan = metadata.lifespan();
            if (metaLifespan > lifespanThreshold) {
               return metaLifespan;
            }
         }
         if (returnOriginalValueOrNull) {
            return value;
         }
         return null;
      }
   }

   protected static class StringTruncator implements Converter<Object, String, String>, Serializable {
      private final int beginning;
      private final int length;

      public StringTruncator(int beginning, int length) {
         this.beginning = beginning;
         this.length = length;
      }

      @Override
      public String convert(Object key, String value, Metadata metadata) {
         if (value != null && value.length() > beginning + length) {
            return value.substring(beginning, beginning + length);
         } else {
            return value;
         }
      }
   }

   protected void verifySimpleInsertion(Cache<Object, String> cache, Object key, String value, Long lifespan,
                                      ClusterListener listener, Object expectedValue) {
      if (lifespan != null) {
         cache.put(key, FIRST_VALUE, lifespan, TimeUnit.MILLISECONDS);
      } else {
         cache.put(key, FIRST_VALUE);
      }
      verifySimpleInsertionEvents(listener, key, expectedValue);
   }

   protected void verifySimpleInsertionEvents(ClusterListener listener, Object key, Object expectedValue) {
      assertEquals(listener.events.size(), 1);
      CacheEntryEvent event = listener.events.get(0);

      assertEquals(Event.Type.CACHE_ENTRY_CREATED, event.getType());
      assertEquals(key, event.getKey());
      assertEquals(expectedValue, event.getValue());
   }

   protected void waitUntilListenerInstalled(final Cache<?, ?> cache, final CheckPoint checkPoint) {
      CacheNotifier cn = TestingUtil.extractComponent(cache, CacheNotifier.class);
      final Answer<Object> forwardedAnswer = AdditionalAnswers.delegatesTo(cn);
      CacheNotifier mockNotifier = mock(CacheNotifier.class, withSettings().defaultAnswer(forwardedAnswer));
      doAnswer(new Answer() {
         @Override
         public Object answer(InvocationOnMock invocation) throws Throwable {
            // Wait for main thread to sync up
            checkPoint.trigger("pre_add_listener_invoked_" + cache);
            // Now wait until main thread lets us through
            checkPoint.awaitStrict("pre_add_listener_release_" + cache, 10, TimeUnit.SECONDS);

            try {
               return forwardedAnswer.answer(invocation);
            } finally {
               // Wait for main thread to sync up
               checkPoint.trigger("post_add_listener_invoked_" + cache);
               // Now wait until main thread lets us through
               checkPoint.awaitStrict("post_add_listener_release_" + cache, 10, TimeUnit.SECONDS);
            }
         }
      }).when(mockNotifier).addListener(anyObject(), any(KeyValueFilter.class), any(Converter.class));
      TestingUtil.replaceComponent(cache, CacheNotifier.class, mockNotifier, true);
   }

   protected void waitUntilNotificationRaised(final Cache<?, ?> cache, final CheckPoint checkPoint) {
      CacheNotifier cn = TestingUtil.extractComponent(cache, CacheNotifier.class);
      final Answer<Object> forwardedAnswer = AdditionalAnswers.delegatesTo(cn);
      CacheNotifier mockNotifier = mock(CacheNotifier.class, withSettings().defaultAnswer(forwardedAnswer));
      Answer answer = new Answer() {
         @Override
         public Object answer(InvocationOnMock invocation) throws Throwable {
            // Wait for main thread to sync up
            checkPoint.trigger("pre_raise_notification_invoked");
            // Now wait until main thread lets us through
            checkPoint.awaitStrict("pre_raise_notification_release", 10, TimeUnit.SECONDS);

            try {
               return forwardedAnswer.answer(invocation);
            } finally {
               // Wait for main thread to sync up
               checkPoint.trigger("post_raise_notification_invoked");
               // Now wait until main thread lets us through
               checkPoint.awaitStrict("post_raise_notification_release", 10, TimeUnit.SECONDS);
            }
         }
      };
      doAnswer(answer).when(mockNotifier).notifyCacheEntryCreated(any(), any(), eq(false), any(InvocationContext.class),
                                                                  any(FlagAffectedCommand.class));
      doAnswer(answer).when(mockNotifier).notifyCacheEntryModified(any(), any(), anyBoolean(), eq(false),
                                                                   any(InvocationContext.class),
                                                                   any(FlagAffectedCommand.class));
      doAnswer(answer).when(mockNotifier).notifyCacheEntryRemoved(any(), any(), any(), eq(false),
                                                                  any(InvocationContext.class),
                                                                  any(FlagAffectedCommand.class));
      TestingUtil.replaceComponent(cache, CacheNotifier.class, mockNotifier, true);
   }

   protected void waitUntilViewChangeOccurs(final CacheContainer cacheContainer, final String uniqueId, final CheckPoint checkPoint) {
      CacheManagerNotifier cmn = TestingUtil.extractGlobalComponent(cacheContainer, CacheManagerNotifier.class);
      final Answer<Object> forwardedAnswer = AdditionalAnswers.delegatesTo(cmn);
      CacheManagerNotifier mockNotifier = mock(CacheManagerNotifier.class, withSettings().defaultAnswer(forwardedAnswer));
      doAnswer(new Answer() {
         @Override
         public Object answer(InvocationOnMock invocation) throws Throwable {
            // Wait for main thread to sync up
            checkPoint.trigger("pre_view_listener_invoked_" + uniqueId);
            // Now wait until main thread lets us through
            checkPoint.awaitStrict("pre_view_listener_release_" + uniqueId, 10, TimeUnit.SECONDS);

            try {
               return forwardedAnswer.answer(invocation);
            } finally {
               checkPoint.trigger("post_view_listener_invoked_" + uniqueId);
            }
         }
      }).when(mockNotifier).notifyViewChange(anyList(), anyList(), any(Address.class), anyInt());
      TestingUtil.replaceComponent(cacheContainer, CacheManagerNotifier.class, mockNotifier, true);
   }
}
