package com.twitter.haplocheirus

import java.util.concurrent.{ExecutionException, Future, TimeUnit}
import java.util.{List => JList}
import com.twitter.gizzard.thrift.conversions.Sequences._
import com.twitter.gizzard.scheduler.ErrorHandlingJobQueue
import com.twitter.xrayspecs.TimeConversions._
import org.jredis.protocol.ResponseStatus
import org.jredis.ri.alphazero.{JRedisFutureSupport, JRedisPipeline}
import org.specs.Specification
import org.specs.mock.{ClassMocker, JMocker}


object PipelinedRedisClientSpec extends ConfiguredSpecification with JMocker with ClassMocker {
  "PipelinedRedisClient" should {
    val jredis = mock[JRedisPipeline]
    val queue = mock[ErrorHandlingJobQueue]
    val future = mock[JRedisFutureSupport.FutureStatus]
    val future2 = mock[Future[JList[Array[Byte]]]]
    val longFuture = mock[JRedisFutureSupport.FutureLong]
    var client: PipelinedRedisClient = null

    val timeline = "t1"
    val data = "rus".getBytes
    val data2 = "zim".getBytes
    val job = Jobs.Append(data, timeline)

    doBefore {
      client = new PipelinedRedisClient("localhost", 10, 1.second, 1.day) {
        override def makeRedisClient = jredis
        override protected def uniqueTimelineName(name: String) = name + "~1"
      }
    }

    "push" in {
      expect {
        one(jredis).lpushx(timeline, data) willReturn longFuture
        one(longFuture).get(1000, TimeUnit.MILLISECONDS) willReturn 23L
      }

      var count = 0L
      client.push(timeline, data, None) { n => count = n }
      client.flushPipeline()
      count mustEqual 23
    }

    "pop" in {
      expect {
        one(jredis).lrem(timeline, data, 0) willReturn longFuture
        one(longFuture).get(1000, TimeUnit.MILLISECONDS) willReturn 1L
      }

      client.pop(timeline, data, None)
      client.flushPipeline()
    }

    "pushAfter" in {
      expect {
        one(jredis).linsertAfter(timeline, data, data2) willReturn longFuture
        one(longFuture).get(1000, TimeUnit.MILLISECONDS) willReturn 23L
      }

      var count = 0L
      client.pushAfter(timeline, data, data2, None) { n => count = n }
      client.flushPipeline()
      count mustEqual 23
    }

    "get" in {
      val result = List("a".getBytes, "z".getBytes)

      expect {
        one(jredis).lrange(timeline, 5, 14) willReturn future2
        one(future2).get(1000, TimeUnit.MILLISECONDS) willReturn result.toJavaList
        one(jredis).expire("t1", 86400)
      }

      client.get(timeline, 5, 10).toList mustEqual result
    }

    "set" in {
      val entry1 = List(23L).pack
      val entry2 = List(20L).pack
      val entry3 = List(19L).pack

      expect {
        one(jredis).rpush(timeline + "~1", entry1) willReturn longFuture
        one(longFuture).get(1000, TimeUnit.MILLISECONDS) willReturn 1L
        one(jredis).rpushx(timeline + "~1", entry2) willReturn longFuture
        one(longFuture).get(1000, TimeUnit.MILLISECONDS) willReturn 1L
        one(jredis).rpushx(timeline + "~1", entry3) willReturn longFuture
        one(longFuture).get(1000, TimeUnit.MILLISECONDS) willReturn 1L
        one(jredis).rename(timeline + "~1", timeline) willReturn future
        one(future).get(1000, TimeUnit.MILLISECONDS) willReturn ResponseStatus.STATUS_OK
        one(jredis).expire(timeline, 86400) willReturn future
        one(future).get(1000, TimeUnit.MILLISECONDS) willReturn ResponseStatus.STATUS_OK
      }

      client.set(timeline, List(entry1, entry2, entry3))
    }

    "delete" in {
      expect {
        one(jredis).del(timeline) willReturn longFuture
        one(longFuture).get(1000, TimeUnit.MILLISECONDS) willReturn 0L
      }

      client.delete(timeline)
    }

    "laterWithErrorHandling" in {
      val onError = Some({ e: Throwable => queue.putError(job) })

      "success" in {
        client.laterWithErrorHandling(onError) { }
        client.flushPipeline()
        client.pipeline.size mustEqual 0
      }

      "exception" in {
        expect {
          one(queue).putError(job)
        }

        client.laterWithErrorHandling(onError) { throw new ExecutionException(new Exception("I died.")) }
        client.flushPipeline()
      }
    }
  }
}
