package org.infinispan.server.hotrod.event

import java.lang.reflect.Method
import java.util.concurrent.{TimeUnit, ArrayBlockingQueue}
import org.infinispan.filter.{ConverterFactory, Converter}
import org.infinispan.manager.EmbeddedCacheManager
import org.infinispan.metadata.Metadata
import org.infinispan.server.hotrod.configuration.HotRodServerConfigurationBuilder
import org.infinispan.server.hotrod.test.HotRodTestingUtil._
import org.infinispan.server.hotrod.test._
import org.infinispan.server.hotrod.{Bytes, HotRodServer, HotRodSingleNodeTest}
import org.testng.annotations.Test

/**
 * @author Galder Zamarreño
 */
@Test(groups = Array("functional"), testName = "server.hotrod.event.HotRodCustomEventsTest")
class HotRodCustomEventsTest extends HotRodSingleNodeTest {

   override protected def createStartHotRodServer(cacheManager: EmbeddedCacheManager): HotRodServer = {
      val builder = new HotRodServerConfigurationBuilder
      // Storing unmarshalled byte arrays, so nullify default marshaller
      builder.marshallerClass(null)
      val server = HotRodTestingUtil.startHotRodServer(cacheManager, builder)
      server.addConverterFactory("static-converter-factory", new StaticConverterFactory)
      server.addConverterFactory("dynamic-converter-factory", new DynamicConverterFactory)
      server
   }

   def testCustomEvents(m: Method) {
      implicit val eventListener = new EventLogListener
      withClientListener(converterFactory = Some(("static-converter-factory", List.empty))) { () =>
         eventListener.expectNoEvents()
         val key = k(m)
         val keyLength = key.length.toByte
         val value = v(m)
         val valueLength = value.length.toByte
         client.put(key, 0, 0, value)
         eventListener.expectSingleCustomEvent(Array(keyLength) ++ key ++ Array(valueLength) ++ value)
         val value2 = v(m, "v2-")
         val value2Length = value2.length.toByte
         client.put(key, 0, 0, value2)
         eventListener.expectSingleCustomEvent(Array(keyLength) ++ key ++ Array(value2Length) ++ value2)
         client.remove(key)
         eventListener.expectSingleCustomEvent(Array(keyLength) ++ key)
      }
   }

   def testParameterBasedConversion(m: Method) {
      implicit val eventListener = new EventLogListener
      val customConvertKey = Array[Byte](4, 5, 6)
      val customConvertKeyLength = customConvertKey.length.toByte
      withClientListener(converterFactory = Some(("dynamic-converter-factory", List(Array[Byte](4, 5, 6))))) { () =>
         eventListener.expectNoEvents()
         val key = k(m)
         val keyLength = key.length.toByte
         val value = v(m)
         val valueLength = value.length.toByte
         client.put(key, 0, 0, value)
         eventListener.expectSingleCustomEvent(Array(keyLength) ++ key ++ Array(valueLength) ++ value)
         val value2 = v(m, "v2-")
         val value2Length = value2.length.toByte
         client.put(key, 0, 0, value2)
         eventListener.expectSingleCustomEvent(Array(keyLength) ++ key ++ Array(value2Length) ++ value2)
         client.remove(key)
         eventListener.expectSingleCustomEvent(Array(keyLength) ++ key)
         client.put(customConvertKey, 0, 0, value)
         eventListener.expectSingleCustomEvent(Array(customConvertKeyLength) ++ customConvertKey)
      }
   }

   def testConvertedEventsNoReplay(m: Method) {
      implicit val eventListener = new EventLogListener
      val key = Array[Byte](1)
      val value = Array[Byte](2)
      client.put(key, 0, 0, value)
      withClientListener(converterFactory = Some(("static-converter-factory", List.empty))) { () =>
         eventListener.expectNoEvents()
      }
   }

   def testConvertedEventsReplay(m: Method) {
      implicit val eventListener = new EventLogListener
      val key = Array[Byte](1)
      val keyLength = key.length.toByte
      val value = Array[Byte](2)
      val valueLength = value.length.toByte
      client.put(key, 0, 0, value)
      withClientListener(converterFactory = Some(("static-converter-factory", List.empty)), includeState = true) { () =>
         eventListener.expectSingleCustomEvent(Array(keyLength) ++ key ++ Array(valueLength) ++ value)
      }
   }

   class StaticConverterFactory extends ConverterFactory {
      override def getConverter[K, V, C](params: Array[AnyRef]): Converter[K, V, C] = {
         new Converter[Bytes, Bytes, Bytes] {
            override def convert(key: Bytes, value: Bytes, metadata: Metadata): Bytes = {
               val keyLength = key.length.toByte
               if (value == null) Array(keyLength) ++ key
               else Array(keyLength) ++ key ++ Array(value.length.toByte) ++ value
            }
         }.asInstanceOf[Converter[K, V, C]] // ugly but it works :|
      }
   }

   class DynamicConverterFactory extends ConverterFactory {
      override def getConverter[K, V, C](params: Array[AnyRef]): Converter[K, V, C] = {
         new Converter[Bytes, Bytes, Bytes] {
            override def convert(key: Bytes, value: Bytes, metadata: Metadata): Bytes = {
               val keyLength = key.length.toByte
               if (value == null || java.util.Arrays.equals(params.head.asInstanceOf[Bytes], key))
                  Array(keyLength) ++ key
               else
                  Array(keyLength) ++ key ++ Array(value.length.toByte) ++ value
            }
         }.asInstanceOf[Converter[K, V, C]] // ugly but it works :|
      }
   }

}
