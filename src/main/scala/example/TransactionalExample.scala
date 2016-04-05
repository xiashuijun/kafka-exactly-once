package example

import org.apache.kafka.clients.consumer.{ ConsumerRecord, KafkaConsumer }
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.TopicPartition

import scalikejdbc._
import com.typesafe.config.ConfigFactory

import org.apache.spark.{SparkContext, SparkConf, TaskContext}
import org.apache.spark.SparkContext._
import org.apache.spark.streaming._
import org.apache.spark.streaming.kafka.{DirectKafkaInputDStream, HasOffsetRanges, OffsetRange}

import scala.collection.JavaConverters._

/** exactly-once semantics from kafka, by storing offsets in the same transaction as the data */
object TransactionalExample {
  def main(args: Array[String]): Unit = {
    val conf = ConfigFactory.load
    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> conf.getString("kafka.brokers"),
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "auto.offset.reset" -> "none"
    )
    val jdbcDriver = conf.getString("jdbc.driver")
    val jdbcUrl = conf.getString("jdbc.url")
    val jdbcUser = conf.getString("jdbc.user")
    val jdbcPassword = conf.getString("jdbc.password")

    val ssc = setupSsc(kafkaParams, jdbcDriver, jdbcUrl, jdbcUser, jdbcPassword)()
    ssc.start()
    ssc.awaitTermination()

  }

  def setupSsc(
    kafkaParams: Map[String, Object],
    jdbcDriver: String,
    jdbcUrl: String,
    jdbcUser: String,
    jdbcPassword: String
  )(): StreamingContext = {
    val ssc = new StreamingContext(new SparkConf, Seconds(60))

    SetupJdbc(jdbcDriver, jdbcUrl, jdbcUser, jdbcPassword)

    // begin from the the offsets committed to the database
    val fromOffsets = DB.readOnly { implicit session =>
      sql"select topic, part, off from txn_offsets".
        map { resultSet =>
          new TopicPartition(resultSet.string(1), resultSet.int(2)) -> resultSet.long(3)
        }.list.apply().toMap
    }

    val stream = DirectKafkaInputDStream[String, String](
      ssc,
      DirectKafkaInputDStream.preferConsistent,
      kafkaParams.asJava,
      () => {
        // Set up the underlying consumer however you need to
        val consumer = new KafkaConsumer[String, String](kafkaParams.asJava)
        consumer.assign(fromOffsets.keys.toList.asJava)
        fromOffsets.foreach { case (topicPartition, offset) =>
            consumer.seek(topicPartition, offset)
        }
        consumer
      }
    )

    stream.foreachRDD { rdd =>
      // Cast the rdd to an interface that lets us get an array of OffsetRange
      val offsetRanges = rdd.asInstanceOf[HasOffsetRanges].offsetRanges

      rdd.foreachPartition { iter =>
        SetupJdbc(jdbcDriver, jdbcUrl, jdbcUser, jdbcPassword)

        // index to get the correct offset range for the rdd partition we're working on
        val osr: OffsetRange = offsetRanges(TaskContext.get.partitionId)

        // simplest possible "metric", namely a count of messages
        val metric = iter.size

        // localTx is transactional, if metric update or offset update fails, neither will be committed
        DB.localTx { implicit session =>
          // store metric data
          val metricRows = sql"""
update txn_data set metric = metric + ${metric}
  where topic = ${osr.topic}
""".update.apply()
          if (metricRows != 1) {
            throw new Exception(s"""
Got $metricRows rows affected instead of 1 when attempting to update metrics for
 ${osr.topic} ${osr.partition} ${osr.fromOffset} -> ${osr.untilOffset}
""")
          }

          // store offsets
          val offsetRows = sql"""
update txn_offsets set off = ${osr.untilOffset}
  where topic = ${osr.topic} and part = ${osr.partition} and off = ${osr.fromOffset}
""".update.apply()
          if (offsetRows != 1) {
            throw new Exception(s"""
Got $offsetRows rows affected instead of 1 when attempting to update offsets for
 ${osr.topic} ${osr.partition} ${osr.fromOffset} -> ${osr.untilOffset}
Was a partition repeated after a worker failure?
""")
          }
        }
      }
    }
    ssc
  }
}