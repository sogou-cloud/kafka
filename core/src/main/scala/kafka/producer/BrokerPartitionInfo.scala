/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package kafka.producer

import collection.mutable.HashMap
import kafka.api.TopicMetadata
import kafka.common.KafkaException
import kafka.utils.Logging
import kafka.common.ErrorMapping
import kafka.client.ClientUtils


class BrokerPartitionInfo(producerConfig: ProducerConfig,
                          producerPool: ProducerPool,
                          topicPartitionInfo: HashMap[String, TopicMetadata])
        extends Logging {
  val brokerList = producerConfig.brokerList
  val brokers = ClientUtils.parseBrokerList(brokerList)

  /**
   * Return a sequence of (brokerId, numPartitions).
   * @param topic the topic for which this information is to be returned
   * @return a sequence of (brokerId, numPartitions). Returns a zero-length
   * sequence if no brokers are available.
   */
  def getBrokerPartitionInfo(topic: String): Seq[PartitionAndLeader] = {
    debug("Getting broker partition info for topic %s".format(topic))
    // check if the cache has metadata for this topic
    val topicMetadata = topicPartitionInfo.get(topic)
    val metadata: TopicMetadata =
      topicMetadata match {
        case Some(m) => m
        case None =>
          // refresh the topic metadata cache
          updateInfo(Set(topic))
          val topicMetadata = topicPartitionInfo.get(topic)
          topicMetadata match {
            case Some(m) => m
            case None => throw new KafkaException("Failed to fetch topic metadata for topic: " + topic)
          }
      }
    val partitionMetadata = metadata.partitionsMetadata
    partitionMetadata.map { m =>
      m.leader match {
        case Some(leader) =>
          debug("Topic %s partition %d has leader %d".format(topic, m.partitionId, leader.id))
          new PartitionAndLeader(topic, m.partitionId, Some(leader.id))
        case None =>
          debug("Topic %s partition %d does not have a leader yet".format(topic, m.partitionId))
          new PartitionAndLeader(topic, m.partitionId, None)
      }
    }.sortWith((s, t) => s.partitionId < t.partitionId)
  }

  /**
   * It updates the cache by issuing a get topic metadata request to a random broker.
   * @param topics the topics for which the metadata is to be fetched
   */
  def updateInfo(topics: Set[String]) {
    var topicsMetadata: Seq[TopicMetadata] = Nil
    val topicMetadataResponse = ClientUtils.fetchTopicMetadata(topics, brokers, producerConfig)
    topicsMetadata = topicMetadataResponse.topicsMetadata
    // throw partition specific exception
    topicsMetadata.foreach(tmd =>{
      trace("Metadata for topic %s is %s".format(tmd.topic, tmd))
      if(tmd.errorCode == ErrorMapping.NoError){
        topicPartitionInfo.put(tmd.topic, tmd)
      } else
        warn("Metadata for topic [%s] is erronous: [%s]".format(tmd.topic, tmd), ErrorMapping.exceptionFor(tmd.errorCode))
      tmd.partitionsMetadata.foreach(pmd =>{
        if (pmd.errorCode != ErrorMapping.NoError){
          debug("Metadata for topic partition [%s, %d] is errornous: [%s]".format(tmd.topic, pmd.partitionId, pmd), ErrorMapping.exceptionFor(pmd.errorCode))
        }
      })
    })
    producerPool.updateProducer(topicsMetadata)
  }
  
}

case class PartitionAndLeader(topic: String, partitionId: Int, leaderBrokerIdOpt: Option[Int])
