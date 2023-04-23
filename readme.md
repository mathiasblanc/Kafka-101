# Kafka 101
Decoupling of data streams & systems.

- Distributed, resilient architecture, fault tolerant
- Horizontal scalability
- High performance (latency of less than 10ms) - real time

Use cases : 
- Messaging system
- Activity Tracking
- Gather metrics from many different locations
- Application logs gathering
- Stream processing
- Decoupling of system dependencies
- Micro-services pub/sub

# Theory

## Topics, partitions and offset
### Important notes
- Once the data is written to a partition, it cannot be changed (immutability)
- Data is kept only for a limited time (default is one week - configurable)
- Offset only have a meaning for a specific partition
    - E.g. offset 3 in partition 0 doesn't represent the same data as offset 3 in partition 1
    - Offsets are not re-used even if previous messages have been deleted
- Order is guaranteed only within a partition (not across partitions)
- Data is assigned randomly to a partition unless a key is provided
- You can have as many partitions per topic as you want

## Producers
- Producers write data to topics
- Producers know in advance to which partition to write to and which kafka broker has it
    - **Producers are the ones who choose where the message is going to end up based on the key's hash**
- In case of kafka broker failures, producers will automatically recover
- Producers can choose to send a key with the message (string, number, nibanry, etc...)
- If key = null, data is sent round robin (partition 0, then 1, then 2...)
- If key != null, then all messages for the key will always go to the same partition (hashing)
    - Data with the same key will always be in the same partition. E.g. Data with key **truck_id_123** will always be in partition 0 and data with key **truck_id_789** will always be in partition 1
- A key is typically sent if you need message order for a specific field

## Message anatomy

| Key - binary  | Value - binary   |
| :-----------: | :--------------: |
| (can be null) | (can be null)    |

| Compression type                |
| :----------------------------- |
| (none, gzip, snappy, lz4, zstd) | 

| Headers (optional) |
| :---------------- |
| key : value        | 

| Partition + Offset                |
| :------------------------------- |
| to which the message will be sent | 

| Timestamp          |
| :---------------- |
| system or user set | 

### Message serializer
- Kafka only accepts bytes as an input from producers and sends bytes out as an output to consumers
- Message serialization is used on the value and the key
- Common serializers : 
    - String (incl. JSON)
    - Int, float
    - Avro
- The serialization / deserialization type must not change during a topic lifecycle (create a new topic instead)

## Consumers
- Consumer read data from a topic (identified by name) - pull model
    - **It's not the broker that pushes but really the consumer that requests data**
- Consumers automatically know which broker to read from
- Data is read in order from low to high offset within each partitions
- Consumers have to know the data format in used on the topic for deserialization

### Consumer offsets
- Kafka stores the offsets at which a consumer group has been reading
- The offsets commited are in Kafka **topic** named **__consumer_offsets** (double _ means it is an internal kafka topic)
- When a consumer in a group has processed data received from Kafka, it should be **periodically** comminting the offsets (the Kafka broker will write to **__consumer_offsets**, not the group itself)
- This tells the broker until which offset the consumer was able to consume the data
- If a consumer dies, it will be able to read back from where it left off, thanks to the committed consumer offsets

### Delivery semantics for consumers
- By default, Java consumers will automatically commit offsets (at least once)
- There are 3 delivery semantics if you choose to commit manually
#### At least once (usually prefered)
- Offsets are committed after the message is processed
- If the processing goes wrong, the message will be read again
- This can result in duplicate processing of messages. Make sure your processing is **idempotent**
#### At most once
- Offsets are committed as soon as messages are received
- If the processing goes wrong, some messages will be lost (the won't be read again)
#### Exactly once
- For Kafka -> Kafka workflows: use the Transactional API (easy with Kafka Streams API)
- For Kafka -> External System workflows: use an **idempotent** consumer

## Consumer groups
- Several consumers may read a single topic
- Each consumer within a group reads from exclusive partitions
    - Consumer 1 might read partition 0 and 1
    - Consumer 2 might read partition 2 and 3
    - And consumer 3 might read only partition 4
- The group is reading a topic as a whole
- If there are too many consumers, some consumers may be inactive
- Its possible to have multiple consumer groups on the same topic
- A group is identified by the property *group.id*

## Kafka Brokers
- A Kafka cluster is composed of multiple brokers (servers)
    - In Kafka, they are called *Brokers* because the receive and send data
- Each broker is identified with its ID (integer)
- Each broker contains certain topic partitions
- After connecting to any broker (called a boostrap broker), you will be connected to the entire cluster (Kafka clients have smart mechanics for that)
    - That means you don't need to know in advance all the brokers in the cluster, you just need to know how to connect to one broker and then you client will automatically connect to the rest
- A good number to get started is 3 brokers, but some big clusters have over 100 brokers
- Partitions are distributed across all brokers (horizontal scalling)

### Kafka broker discovery
- Every Kafka broker is also called *bootstrap server*
- That means that **you only need to connect to one broker**, and the Kafka clients will know how to be connected to the entire cluster (smart clients)
- Each broker knows about all broker, topics and partitions (metadata)
1. The Kafka client connects to a broker and sends metadata
2. The broker returns the list of all brokers in the cluster
3. The client can connect to the needed brokers

## Topic replication factor
- Topics should have a replication factor > 1 (usually between 2 and 3)
- This way if a broker is down, another broker can serve the data

### Concept of leader for a partition
- At any time, only **ONE** broker can be a leader for a given partition
- Producers can only send data to the broker that is leader of a partition
- The other brokers will replacte the data
- Therefore, each partition has one leader and multiple ISR (in-sync replica)

#### Default producer and consumer behavior with leaders
- Kafka producers can only write to the leader broker for a partition
- Kafka consumers by default will read from the leader broker for a partition
- That means that replica brokers will not be read unless the leader is down
    - If the leader is down, then a replica will become the new leader

#### Kafka consumers replica fetching (Kafka v2.4+)
- Since Kafka 2.3, it is possible to configure consumers to read from the closest replica
- This may help improve latency, and also decrease network costs if using the cloud

## Producer Acknowledgements (acks)
- Producers can choose to receive acknowledgment of data writes: 
    - **acks=0**: producer won't wait for acknowledgment (possible data loss)
        - Producers consider messages as *written successfully* the moment the message was sent without waiting for the broker to accept it at all
        - If the broker goes offline, or an exception happens, we won't know and **will lose data**
        - Useful for data where it's okay to pententially lose messages, such as metrics collection
        - Produces the highest throughput setting because the network overhead is minimized
    - **acks=1**: producer will wait for leader acknowledgment (limited data loss)
        - Producers consider messages as *written successfully* when the message was acknowledged by only the leader
        - Default for Kafka v1.0 to v2.8
        - Leader response is requested, but replication is not a guarantee as it happens in the background
        - If the leader broker goes offline unexpectedly but replicas haven't replicated the date yet, **we have a data loss**
        - If an ack is not received, the producer may retry the request
    - **acks=all (acks=-1)**: leader + replicas acknowledgment (no data loss)
        - Producers consider messages as *written successfully* when the message is accepted by all in-sync replicas (ISR)
        - Default for Kafka 3.0+

### Producer `acks=all` & `min.insync.replicas`
- The leader replica for a partition checks to see if there are enough in-sync replicas for safely writting the message (controlled by the broker setting `min.insync.replicas`)
    - `min.insync.replicas=1` : only the broker **leader** needs to successfully ack
    - `min.insync.replicas=2` : at least the broker leader and one replica need to ack
- If you have a cluster with 3 brokers (1 leader and two ISR) and the broker setting `min.insync.replicas=2` then : 
    - Producers will only get a successfull ack if one ISR acked to the leader and then the leader will ack the producers
    - If all ISR are down and only the leader is up, then the leader will respond with an `NOT_ENOUGH_REPLICAS` error
- To get the safest data guarantee, it's recommanded to have a replication factor of 3 and min `min.insync.replicas=2`

## Kafka topic availability
- **Availability: (considering RF=3)**
    - `acks=0` & `acks=1`: as long as one partition is up and is an ISR, the topic will be available for writes
    - `acks=all`: 
        - `min.insync.replicas=1` (default): the topic must have at least 1 partition up as an ISR (this includes the leader) and so we can tolerate two brokers being down
        - `min.insync.replicas=2`: the topic must have at least 2 ISR up, and therefore we can tolerate at most one broker being down (in he case of a replication factor of 3), and we have the guarantee that for every write, the data will be at least written twice
        - `min.insync.replicas=3`: this wouldn't make much sense for a corresponding replication factor of 3 and we couldn't tolerate any broker going down
        - in summary, when `acks=all` with a `replication.factor=N` and `min.insync.replicas=M` we can tolerate `N-M` brokers going down for topic availability purposes

- `acks=all` and `min.insync.replicas=2` is the most popular option for data durability and availability and allows you to withstand at most the loss of **one* Kafka broker

## Producer retries
- In case of transient faiures, developers are expected to handle exceptions, otherwise the data will be lost
- Example of transient failue: 
    - `NOT_ENOUGH_REPLICAS` (due to `min.insync.replicas` settings)
- There is **retries** setting
    - defaults to 0 for kafka <= 2.0
    - defaults to 2147483647 for Kafka >= 2.1
- The `retry.backoff.ms` setting is by default 100 ms

### Producer timeouts
- If retries > 0, for example retries=2147483647, retries are bounded by a timeout
- Since Kafka 2.1, you can set `delivery.timeout.ms=120000` *== 2 min*
- Record will be failed if they can't be acknowledged withing `delivery.timeout.ms`

### Idempotent producer
- The producer can introduce duplicate messages in Kafka due to network errors
    1. The producer sends a message to Kafka
    2. Kafka commits the message and sends the acks
    3. The acks never reaches the producer because of network error
    4. Since the producer did not get the ack it will retry
    5. Kafka sees the duplicate as a new message, commits it and sends the ack
- In Kafka >= 0.11, you can define a *idempotent producer* which won't introduce duplicates on network error
    - Given the same scenario as before with ack never reaching the producer
    - Upon not receiving the ack, the producer will still retry but this time will be able to **detect that the message is a duplicate, won't commit twice** but will still send the ack to the producer so that it won't retry once again
- The are the default since Kafka 3.0, it is recommanded to use them
- The come with:
    - `retries=Integer.MAX_VALUE`
    - `max.in.flight.request=5` 
    - `acks=all`
- The settings are applied automatically after your producer has started if not manually set
- Just set: `produerProps.put("enable.idempotence", true);`

## Safe Kafka producer settings
- Since **Kafka 3.0** the producer is *safe* by default, otherwise upgrade your clients or set the following settings
- `acks=all` ensures data is properly replicated before an ack is received
- `min.insync.replicas=2` (broker/topic level) ensures two brokers in ISR at least have the data after an ack
- `enable.idempotence=true` duplicates are not introduced due to network retries
- `retries=MAX_INT` (producer level) retry until *delivery.timeout.ms* is reached
- `delivery.timeout.ms=120000` fail after retrying for 2 minutes
- `max.in.flight.requests.per.connection=5` ensures maximum performance while keeping message ordering

## Kafka topic durability
- For a topic replication factor of 3, topic data durability can withsand 2 brokers loss
- As a rule, for a replication factor of N, you can permanently lose up to N-1 brokers and still recover your data

## Zookeeper
- Zookeeper manages brokers (keeps a list of them)
- Zookeeper helps in performing leader election for partitions
- Zookeeper sends notifications to Kafka in case of changes (e.g. new topic, broker dies, broker comes up, deleted topics, etc...)
- **Kafka 2.x cannot work without Zookeeper**
- **Kafka 3.x can work withouth Zookeeper (KIP-500) - using Kafka Raft instead**
- **Kafka 4.x will not have Zookeeper**
- Zookeeper by design operates with an odd number of servers (1, 3, 5, 7, never more than 7 usually)
- Zookeeper has a leader (writes), the rest of the servers are followers (reads)
- Zookeeper does NOT store consumer offsets with Kafka > v0.10
- **Never use Zookeeper as a configuration in your Kafka clients and other programs that connect to Kafka**

## Kafka KRaft
- In 2020, the Apache Kafka project start to work to remove the Zookeeper dependency from it (KIP-500)
- Zookeeper shows caling issues when Kafka clusters have > 100'000 partitions
- By removing Zookeeper, Apache Kafka can 
    - Scale to millions of partitions and becomes easier to maintain and set-up
    - Improve stability, makes it easier to monitor, support and administer
    - Single security model for the whole system
    - Single process to start with Kafka
    - Faster controller shutdown and recovery time
- Kafka 3.x now implements the Raft protocol (KRaft) in order to replace Zookeeper
    - Production ready since Kafka 3.3.1 (KIP-833)
    - Kafka 4.0 will be released only with KRaft (no Zookeeper, Quorum controller instead)


# Kafka CLI
Copy config file from *Conduktor -> My Playground* and paste it in a file named *playground.config*.

## kafka-topics

### Create

```
kafka-topics.sh --command-config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --create --topic first_topic
```
This creates a topic with a default number of partitions, 3 in this case.

You can explicitly define the number of partitions by adding `--partitions 5` : 
```
kafka-topics.sh --command-config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --create --topic second_topic --partitions 5
```

The replication factor can be set via the option `--replication-factor n` where `n` is the number of replicas.

### List 
```
kafka-topics.sh --command-config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --list
```

### Describe

```
kafka-topics.sh --command-config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic first_topic --describe
```

### Delete
```
kafka-topics.sh --command-config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic second_topic --delete
```

## kafka-console-producer
- By default, producing to a topic that does not exist will end up in Kafka outputing a warning and creating the topic. It is recommanded to disable this mechanism in production and create topics in advance.
- When a topic is auto-created, the default will have 1 partition and a replication factor of 1.

### Produce
```
kafka-console-producer.sh --producer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic first_topic
>Hello world
>My name is Smjoervi
>I love butter
```

### Produce with properties
```
kafka-console-producer.sh --producer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic first_topic --producer-property acks=all
>next messages
>will be acked
>by all brokers
```

### Produce with key
```
kafka-console-producer.sh --producer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic first_topic --property parse.key=true --property key.separator=:
>example key:example value
>name:smjoervi
```

## kafka-console-consumer
To test that messages are really spread across multiple partitions, you can use the producer property `--producer-property partitioner.class=org.apache.kafka.clients.producer.RoundRobinPartitioner`. This property should not be used in production as it is really inefficiant but it allows to demonstrate that messages are sent to each partition in a round robin manner.

### Consume
```
kafka-console-consumer.sh --consumer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic second_topic
```

Add `--from-beginning` to consume all messages from beginning : 

```
kafka-console-consumer.sh --consumer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic first_topic --from-beginning
```

Display key, values and timestamp : 
```
kafka-console-consumer.sh --consumer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic second_topic --formatter kafka.tools.DefaultMessageFormatter --property print.timestamp=true --property print.key=true --property print.value=true --property print.partition=true --from-beginning
```
Will show : 
```
CreateTime:1681720469752        Partition:1     null    hello world
CreateTime:1681720482143        Partition:2     null    my name is smjoervi
CreateTime:1681720499187        Partition:0     null    it's working
CreateTime:1681720905175        Partition:1     null    bla
CreateTime:1681720907991        Partition:2     null    bbli
CreateTime:1681720909079        Partition:0     null    blo
```

### Consumers in group
1. Start by creating a fresh topic with 3 partitions : 
```
kafka-topics.sh --command-config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic third_topic --create --partitions 3
```

2. Start a consumer by specifying the group id *my-first-application* : 
```
kafka-console-consumer.sh --consumer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic third_topic --group my-first-application
```

3. Start a producer using the *round robin partitioner* and produce data: 
```
kafka-console-producer.sh --producer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --producer-property partitioner.class=org.apache.kafka.clients.producer.RoundRobinPartitioner --topic third_topic 
```

4. Start two more consumers in the same group id *my-first-application* : 
```
kafka-console-consumer.sh --consumer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic third_topic --group my-first-application
```

5. Observe that messages are spread across all consumers in the group : 

Producer 1
 ```
➜  kafka kafka-console-producer.sh --producer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --producer-property partitioner.class=org.apache.kafka.clients.producer.RoundRobinPartitioner --topic third_topic 
t>test
>hello
>world
>another one
>and again
>bli
>bla   
>blo
>one
>two 
>trhee
>
```

Consumer 1
```
➜  kafka kafka-console-consumer.sh --consumer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic third_topic --group my-first-application
ttest
another one
bla
two
```
Consumer 2
```
➜  kafka kafka-console-consumer.sh --consumer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic third_topic --group my-first-application

hello
world
and again
bli
blo
one
```

Consumer 3
```
➜  kafka kafka-console-consumer.sh --consumer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic third_topic --group my-first-application

trhee
```

6. Now shut down all consumers and produce a few messages. Restart only one consumer and observe that all messages will be consumed by this consumer in order to catch up on the lag it had.

Producer 1
```
>aa
>bb
>cc
>dd
>ee
>ff
>gg
```
Consumer 1
```
➜  kafka kafka-console-consumer.sh --consumer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic third_topic --group my-first-application
cc
aa
dd
gg
ff
bb
ee
```
Note that the order is not preserved.

7. If you start a new consumer from a different group specifying the *--from-beginning* property, **ALL** messages will be read : 
```
kafka kafka-console-consumer.sh --consumer.config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --topic third_topic --group my-second-application --from-beginning

hello
ttest
another one
bla
two
b

...

dd
gg
world
bli
one

... 

cc
ff
and again

...

8
e
bb
ee
```

## kafka-consumer-groups
List all consumer groups : 
```
➜  kafka kafka-consumer-groups.sh --command-config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --list
my-first-application
my-second-application
```

Describe a consumer group : 
```
➜  kafka kafka-consumer-groups.sh --command-config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --describe --group my-second-application

GROUP                 TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                           HOST            CLIENT-ID
my-second-application third_topic     0          25              25              0               console-consumer-0f0fa2ad-013c-4e0a-aa2b-b21bb53e021b /35.195.32.213  console-consumer
my-second-application third_topic     1          18              18              0               console-consumer-0f0fa2ad-013c-4e0a-aa2b-b21bb53e021b /35.195.32.213  console-consumer
my-second-application third_topic     2          16              16              0               console-consumer-5bedf29f-d760-42ce-9323-7d323b408301 /35.195.32.213  console-consumer
```

## Resetting offsets
Use the `--dry-run` property to have a preview of what is going to be done withouth actually changing anything.

```
➜  kafka kafka-consumer-groups.sh --command-config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --group my-first-application --reset-offsets --to-earliest --topic second_topic --dry-run

GROUP                          TOPIC                          PARTITION  NEW-OFFSET     
my-first-application           second_topic                   0          0              
my-first-application           second_topic                   1          0              
my-first-application           second_topic                   2          0       
```

Reset the offset using the `--execute` property : 
```
➜  kafka kafka-consumer-groups.sh --command-config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --group my-first-application --reset-offsets --to-earliest --topic third_topic --execute
```

Observe the topic lag : 
```
➜  kafka kafka-consumer-groups.sh --command-config playground.config --bootstrap-server cluster.playground.cdkt.io:9092 --describe --group my-first-application

Consumer group 'my-first-application' has no active members.

GROUP                TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID     HOST            CLIENT-ID
my-first-application third_topic     0          0               25              25              -               -               -
my-first-application third_topic     1          0               18              18              -               -               -
my-first-application third_topic     2          0               16              16              -               -               -
```

Now if you start a consumer on this group, all messages will be consumed from the beginning and describing the group will show a log of 0.

- **Offsets cannot be reset while consumers in the group are still active.** Make sure all consumers of the group are down before resetting the offset.

# Consumer groups and partition rebalance
- Moving partitions between consumers is called a rebalance
- Reassignment of paritions happen when a consumer leaves or joins a group
- It also happens if an administrator adds new partitions into a topic

## Eager rebalance
- All consumers **stop**, give up their membership of partitions (this is call the *stop the world event*)
- The rejoin the consumer group and get a new partition assignment
- During a short period of time, the entire consumer group stops processing
- Consumers don't necessarily get back the same partitions as the used to have

## Cooperative rebalance (incremental rebalance)
- Reassigning a small subset of partitions from one consumer to another
- Other consumers that don't have reasdigned partitions can still process uninterrupted
- Can go through several iteraations to find a "stable" assignment (hence "incemental")
- Avoids *stop-the-world* events where all consumers stop processing data

### How to use cooperative rebalance
- **Kafka Consumer:** `partition.assignment.strategy`
    - `RangeAssignor`: assign partition on a per-topic basis (can lead to imbalance)
    - `RoundRobin`: assign partitions across all topics in round-robin fashion, optimal balance
    - `StickyAssignor` : balanced like RoundRobin, and the minimises partition movements when consumer join / leave the group in order to minimize movements
    - `CooperativeStickyAssignor`: rebalance strategy is identical to StickyAssignor but supports cooperative rebalances and therefore consumers can keep on sonsuming from the topic
    - `The default assignor is [RangeAssignor, CooperativeStickyAssignor]` which will use the RangeAssignor by default, but allows upgrading to the CooperativeStickyAssignor with just a single rolling bounce that removes the RangeAssignor from the list.

- `RangeAssignor`, `RoundRobin` and `StickyAssignor` are eager strategies, that mean that when you use them there will be a *stop-the-world* event. If the consumer group is big, then it can take a while to reassign all the partitions.

## Static group membership
- By default, when a consumer leaves a group, its partitions are revoked and re-assigned
- If it joins back, it will have a new *member ID* and new partitions assigned
- If you specify `group.instance.id` it makes the consumer a static member
- Upon leaving, the consumer has up to `session.timeout.ms` to join back and get back its partitions (else they will be re-assigned), withouth triggering a rebalance
- This is helpful when consumers maintain local state and cache (to avoid re-building the cache)

# Kafka Consumer - Auto offset commit behavior
- In the Java Consumer API, offsets are regularly committed
- Enable at-least once reading scenario by default (under conditions)
- Offsets are committed when you call `.poll()` and `auto.commit.interval.ms` has elapsed
- Example: `auto.commit.interval.ms=5000` and `enable.auto.commit=true` will commit
- Make sure messages are all successfully processed before you call `poll()`again
    - If you don't, you will not be in at-least-once reading scenario
    - In that (rare) case, you must disable `enable.auto.commit` and and most likely do most of the processing to a separate thread, and then from time to time call `.commitSync()` or `.commitAsync()` with the correct offsets passed manually.

# Advanced producer configuration

## Kafka message compression
- Advantages
    - Much smaller producer request size (up to 4x)
    - Faster network transfers
    - Better throughput 
    - Better disk utilisation in Kafka (stored messages on disk are smaller)
- Disavantages (very minor)
    - Producers must commit some CPU cycles to compression
    - Consumers must commit some CPU cycles to decompression
- Can be set either on producer or broker level
- Compression is more effective the bigger the batch of message being sent to Kafka
- Producer usually send text based data (e.g. JSON), in this case, it is important to apply compression to the producer
- `compression.type`can be `none` (default), `gzip`, `lz4`, `snappy`, `zstd` (Kafka 2.1)
    - consider testing `snappy` or `lz4` for optimal speed / compression ratio (but test others too)
- Consider tweaking `linger.ms` and `batch.size` to have bigger batches and therefore more compression and higher throughput
- Use compression in production

### Message compression at the broker / topic level
- `compression.type=producer` (default) the broker takes the compressed batch from the producer client and writes it directly to the topic's log file without recompressing the data
- `compression.type=none` all batches are decompressed by the broker
- `compression.type=lz4` (for example)
    - If it's matching the producer setting, data is sotred on disk as is
    - If it's a different compression setting, batches are decompressed by the broker and then recompressed using the compression algorithm specified
- **Warning** : if you enable broker-side compression it will consume extra CPU cycles
- It is recommanded to have all producers compress data on their end and leave `compression.type=producer` on the broker

## `linger.ms` & `batch.size`
- By default, Kafka producers try to send records as soon as possible
    - It will have up to `max.in.flight.requests.per.connection=5`, meaning up to 5 message batches being in flight (being sent between the producer and the broker) at most (so until the ack is received)
    - After this, if more messages must be sent while others are in flight, Kafka is mart and will start batching them before the next batch send
- This smart batching helps increase throughput while maintaining very low latency
    - Added benefit : batches have higher compression ratio so better efficiency
- Two settings to influence the batching mechanism
    - `ling.ms` : (default 0) how long to wait until we send a batch. Adding a small number, for example 5 ms, helps add more messages in the batch at the expense of latency
    - `batch.size` : if a batch is filled before `linger.ms`, increase the batch size

### `batch.size` (default 16KB)
- Maximum number of bytes that will be included in a batch
- Increasing a batch size to something like 32KB or 64KB can help increasing the compression, throughput and efficiency of requests
- Any message bigger than the batch size will not be batched
- A batch is allocated per partition, so make sure that you don't set it to a number that's too high, otherwise you'll waste memory
- You can monitor the average batch size metric using *Kafka Producer Metrics*

### High troughput producer
- Increase `linger.ms` and the producer will wait a few milliseconds for the batches to fill up before sending them
- If you are sending full batches and have memory to spare, you can increase `batch.size` and send larger batches
- Introduce some producer-level compression for more efficiency in sends

```
properties.setProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
properties.setProperty(ProducerConfig.LINGER_MS_CONFIG, "20");
properties.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, Integer.toString(32*1024));
```

## Producer default partitioner when `key!=null`
- **Key Hasing** is the process of determining the mapping of a key to a partition
- In the default Kafka partitioner, the keys are hashed using the **murmur2** algorithm
    - `targetPartition = Math.abs(Utils.murmur2(keyBytes)) % (numPartitions -1)`
- This means that **the same key will go to the same parition** and **adding paritions to a topic will completly alter the formula**
- It is most likely prefferred to NOT override the behavior of the partitioner, but it is possible to do so using `partitioner.class`

## Producer default partitioner when `key==null`
- When `key==null` the producer has a *default partitioner* that varies: 
    - Round Robin : for Kafka 2.3 and below
    - Sticky Partitioner : for Kafka 2.4 and above
- Sticky Partitioner improves the performance of the producer especially when high throughput when the key is null

### Producer default partitioner Kafka <= 2.3 - Round Robin Paritioner
- With Kafka <= 2.3, when ther's no partition and no key specified, the default partitioner sends data in a *round-robin* fashion
- This results in **more batches** (one batch per partition) and **smaller batches**
- Smaller batches lead to more requests as well as higher latency

### Producer default partitioner Kafka >= 2.4 - Sticky Paritioner
- It would be better to have all the records sent to a single partition and not multiple partitions to improve batching
- The producer sticky partitioner : 
    - We "stick" to a parition until the batch is full or `linger.ms` has elapsed
    - After sending the batch, the partition that is sticky changes
- Larger batches and reduced latency (because larger requests, and `batch.size` more likely to be reached)
- Over time, records are still spread evenly across paritions
- Performance improvement

# Consumer delivery semantics
For most applications you should use **at least once** processing and ensure your transformations / processing are idempotent.

## At most once
Offsets are committed as soon as the message batch is received. If the processing goes wrong, the message will be lost (it won't be read again).

## At least once
Offsets are committed after the message is processed. If the processing goes wrong, the message will be read again. This can result in duplicate processing of messages. Make sure your processing is **idempotent** (i.e. processing again the messages won't impact your system).

## Exactly once
Can be achieved for Kafka => Kafka workflows using the *Transactional API* (easy with Kafka Streams API). For Kafka => Sink workflows, use an idempotent consumer.

# Consumer offset commit strategies
There are two most common patterns for committing offsets ina consumer application.

- 2 strategies:
- (easy) `enable.auto.commit=true` & synchronous processing of batches
- (medium) `enable.auto.commit=false` & manual commit of offsets

## Auto offset commit behavior
- In the Java Consumer API, offsets are regularly committed
- Enable "at least once* reading scenario by default (under conditions)
- Offsets are committed when you call `.poll()` and `auto.commit.interval.ms` has elapsed
- **Make sure messages are all successfully processed before you call `.poll()`again**
    - If you don't, you will not be in *at least once* reading scenario
    - In that (rare) case, you must disable `enable.auto.commit` and most likely do most processing to a separate thread, and then, from time to time, call either `.commitSync()` or `.commitAsync()` with the correct offsets manually (advanced)

### `enable.auto.commit=true` & synchronous processing of batches
```
while(true){
    List<Records> batch = consumer.poll(Duration.ofMillis(100));
    doSomethingSynchronous(batch);
}
```
- With auto-commit, offsets will be committed automatically for you at regular interval (`auto.commit.interval.ms=5000` **by default**) every time you call `.poll()`
- If you don't do synchronous processing, you will be in *at most once* behavior because offsets will be committed before your data is processed

### `enable.auto.commit=false` & synchronous processing of batches
```
while(true){
    batch += consumer.poll(Duration.ofMillis(100));
    if (isReady(batch)) {
        doSomethingSynchronous(batch);
        consumer.commitAsync();
    }
}
```
- You control when you commit offsets and what is the condition for committing them
- Example : accumulating records into a buffer and then flushing the buffer to a database + committing offsets asynchronously then

# Consumer offset reset behavior
- A consumer is expected to read from a log continuously
- But if your application has a bug, your consumer can be down
- If Kafka has a retention of 7 days, and your consumer is down for more than 7 days, the offsets are *invalid*
- The behavior for the consumer is to use : 
    - `auto.offset.reset=latest` : will read from the end of the log
    - `auto.offset.reset=earliest` : will read from the start of the log
    - `auto.offset.reset=none` : will throw exception if no offset is found
- Additionally, consumer offsets can be lost: 
    - If a consumer hasn't read new data in 1 day (Kafka < 2.0)
    - If a consumer hasn't read new data in 7 days (Kafka >= 2.0)
- This can be controlled by the broker setting `offset.retention.minutes`

# Replaying data for consumers
- To replay data for a consumer group : 
    - Take all the consumers from a specfic group down
    - Use `kafka-consumer-groups` command to set the offset to what you want
    - Restart consumer
- Bottom line : 
    - Set proper data retention period & offset retention period
    - Ensure the auto offset reset behavior is the one you expect / want
    - Use replay capability in case of unexpected behavior

# Controlling consumer liveliness
- Consumers in a *Group* talk to a *Consumer Groups Coordinator*
- To detect consumers that are down, ther is a heartbeat mechanism and a poll mechanism
- To avoid issues, consumers are encouraged to prcoess data fast and poll often

## Consumer heartbeat thread
- `heartbeat.interval.ms` (default 3 seconds)
    - How often to send hearbeats
    - Usually set to 1/3rd of `session.timeout.ms`
- `session.timeout.ms` (default 45 seconds in Kafka 3.0+, before was 10 seconds):
    - Heartbeats are sent periodically to the broker
    - If no heartbeat is sent during that period, the consumer is condisered dead
    - Set even lower for faster consumer rebalances
- This mechanism is used to detect a consumer application being down

## Consumer poll thread
- `max.poll.interval.ms` (default 5 minutes)
    - Maximum amount of time between two `.poll()`calls before declaring the consumer dead
    - This is relevant for Big Data frameworks like Spark in case the processing takes time
- This mechanism is used to detect a data processing issue with the consumer (the consumer is stuck)
- `max.poll.records` (default 500) : 
    - Controls how many records to receive per poll request
    - Increase if your messages are very small and have a lot of available RAM
    - Good to monitor hwo many records are polled per request
    - Lower if it takes you too much time to process records

## Consumer poll behavior
Change these settings only if your consumer maxes out on throughput

- `fetch.min.bytes` (default 1) : 
    - Controls how much data you want to pull at least on each request
    - Helps improving throughput and decreasing request number
    - At the cost of latency
- `fetch.max.wait.ms` (default 500) : 
    - The maximum amount of time the Kafka broker will block before answering the fetch request if there isn't sufficient data to immediately staisfy the requirement given by `fetch.min.bytes`
    - This means that until the requirement of `fetch.min.bytes` is statisfied, you will have up to 500 ms of latency before the fetch returns data to the consumer (e.g. introducing a potential delay to be more efficient in requests)
- `max.partition.fetch.bytes` (default 1MB) : 
    - The maximum amount of data per partition the server will return
    - If you read from 100 partitions, you will need a lot of RAM
- `fetch.max.bytes` (default 55MB) : 
    - Maximum data returned for each fetch request
    - If you have available memory, try increasing `fetch.max.bytes` to allow the consumer to read more data in each request

# Default consumer behavior with partition leaders
- Kafka consumers by default will read from the leader broker for a partition
- Possibly higher latency (multiple data center) and high network charges ($$$)

## Kafka consumers replica fetching (Kafka v2.4+) - Consumer Rack Awareness
- Since Kafka 2.4, it is possible to configure consumers to read from the **closest replica**
- This may help improve latency and decrease network costs if using the cloud

### How to setup
- Broker settings : 
    - Must be Kafka v2.4+
    - `rack.id` config must be set to the data center ID (ex: AZ ID in AWS)
        - Example for AWS : `rack.id=usw2-az1`
    - `replica.selector.class` must be set to òrg.apache.kafka.common.replica.RackAwareReplicaSelector`
- Consumer client setting : 
    - Set `client.rack` to the data center ID the consumer is launched on

# Kafka extended APIs
Kafka and its ecosystem have introduced over time some new API that are higher level that solve specific sets of problems : 
    - **Kafka Connect** solves External Source => Kafka and Kafka => External Sink
    - **Kafka Streams** solves transformation Kafka => Kafka
    - **Schema Registry** helps using Schema in Kafka

## Kafka Connect - High level
- **Source Connectors** to get data from common data sources
- **Sink Connectors** to publish data in common data stores
- Makes it easy for non-experienced dev to quickly get their data reliably int Kfka
- Part of your ETL piepline
- Scaling made easy from small pipelines to company wide pipelines
- Reusable code
- Connectors achieve fault tolerance, diempotence, distribution, ordering
- A lot of connectors can be found on **confluent.io/hub**

## Kafka Streams
- You want to do the following from the ẁikimedia.recentchange` topic : 
    - Count the number of times a change was created by a bot versus a human
    - Analyze the number of changes per website (ru.wikipedia.org vs en.wikipedia.org)
    - Get the number of edits per 10 seconds as a time series
- With the Kafka Producer and Consumer you can achieve this but it's very low level and not developer friendly

### What is Kafka Streams
- It easily allows you to do : 
    - Data transformation
    - Data enrichment
    - Fraud detection
    - Monitoring and alerting
- Standard Java application
- No need to create a separate cluster
- Highly scalable, elastic and fault tolerant
- Exactly-Once capabilities
- One record at a time processing (no batching)
- Works for any application size

## Schema registry
- Kafka takes bytes as input and publishes them
- No data verification
- The need for a schema registry : 
    - What if the producer sends bad data?
    - What if a field gets renamed?
    - What if the data format changes from one day to another?
    - => **The consumers break**
- We need data to be self describable
- We need to be able to evolve data without breaking downstream consumers
- What if the Kafka Brokers were verifying the messages they receive ?
    - It would break what makes Kafka so good : 
        - Kafka doesn't parse or even read your data (no CPU usage)
        - Kafka takes bytes as input without even loading them into memory (that's called zero copy)
        - Kafka distributes bytes
        - As fare as Kafka is concerned, it doesn't even know if your data is an integer, as string, etc ...
- The Schema Registry must be a separate component
- Producers and Consumers need to be able to talk to it
- The Schema REgistry must be able to reject bad data
- A common data format must be agreed upon
    - It needs to support schemas
    - It needs to support evolution
    - It needs to be lightweigh

# Real world insights and case studies
## Choosing partition count & repica factor
- The **two most important parameters** when creating a topic
- They impact performance and durability of the system overall
- It is best to get the parameters right the first time!
    - If the partitions count increases during a topic lifecycle, you will break your keys ordering guarantees
    - If the replication factor increases during a topic lifecycle, you put more pressure on your cluster which can lead to unexpeted performances decrease

### Choosing the partitions count
- Each partition can handle a throughput of a few MB/s (measure it for your setup!)
- More partitions implies: 
    - Better parallelism, better throughput
    - Ability to run more consumers ina group to scale (max as many consumers per group as partitions)
    - Ability to leverage more brokers if you have a large cluster
    - BUT more elections to perform for Zookeeper (if using Zookeeper)
    - BUT more files opened on Kafka
- *Guidelines* : 
    - **Partitions per topic = million dollar question**
        - (Intuition) Small cluster (< 6 brokers) : 3x the number of brokers
        - (Intuition) Big cluster (> 12 brokers) : 2x the number of brokers
        - Adjust for number of consumers you need to run in parallel at peak throughput
        - Adjust for producer throughput (increase if super high throughput or projected increase in the next 2 years)
    - **TEST!** Every Kafka cluster will have different performance
    - Don't systematically create topics with 1000 partitions!

### Choosing the replication factor
- Should be at least 2, usually 3, maximum 4
- The higher the replication factor (N) : 
    - Better durability of you system (N-1 brokers can fail)
    - Better availability of your system (N-min.insync.replicas if producer acks=all)
    - BUT more replication (higher latency if acks=all)
    - BUT more disk space on your system (50% more if RF is 3 instead of 2)
- *Guidelines* : 
    - **Set it to 3 to get started** (you must have at least 3 brokers for that)
    - If replication performance is an issue, get a better broker instead of less RF
    - **Never set it to 1 in production**
 
 # Advanced topics configuration
 - Brokers have defaults for all the topic configuration parameters
 - These parameters impact **performance** and **topic behavior**
 - Some topics may need different values than the defaults
    - Replication factor
    - Number of partitions
    - Message size
    - Compression level
    - Log cleanup policy
    - Min insync replicas
    - Other configurations

## Changing a topic configuration

We first start by creating a topic named *configured-topic* : 

```
➜  conduktor-platform kafka-topics.sh --create --bootstrap-server localhost:9092 --topic configured-topic --replication-factor 1 --partitions 3
Created topic configured-topic.
➜  conduktor-platform kafka-topics.sh  --bootstrap-server localhost:9092 --topic configured-topic --describe  
Topic: configured-topic	TopicId: IysKmDYBQmGCV31cZgmoxA	PartitionCount: 3	ReplicationFactor: 1	Configs: 
	Topic: configured-topic	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
	Topic: configured-topic	Partition: 1	Leader: 1	Replicas: 1	Isr: 1
	Topic: configured-topic	Partition: 2	Leader: 1	Replicas: 1	Isr: 1

```

Using `kafka-configs.sh` we can describe the configurations for the topic : 

```
➜  conduktor-platform kafka-configs.sh --bootstrap-server localhost:9092 --entity-type topics --entity-name configured-topic --describe
Dynamic configs for topic configured-topic are:
```

And currently there are none.

Now we can set `min.insync.replicas=2` and show the configuration again : 

```
➜  conduktor-platform kafka-configs.sh --bootstrap-server localhost:9092 --entity-type topics --entity-name configured-topic --alter --add-config min.insync.replicas=2
Completed updating config for topic configured-topic.
➜  conduktor-platform kafka-configs.sh --bootstrap-server localhost:9092 --entity-type topics --entity-name configured-topic --describe                                
Dynamic configs for topic configured-topic are:
  min.insync.replicas=2 sensitive=false synonyms={DYNAMIC_TOPIC_CONFIG:min.insync.replicas=2, DEFAULT_CONFIG:min.insync.replicas=1}
```

We can also shwo the topic's configuration using `kafka-topics.sh` : 

```
➜  conduktor-platform kafka-topics.sh  --bootstrap-server localhost:9092 --topic configured-topic --describe                                      
Topic: configured-topic	TopicId: IysKmDYBQmGCV31cZgmoxA	PartitionCount: 3	ReplicationFactor: 1	Configs: min.insync.replicas=2
	Topic: configured-topic	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
	Topic: configured-topic	Partition: 1	Leader: 1	Replicas: 1	Isr: 1
	Topic: configured-topic	Partition: 2	Leader: 1	Replicas: 1	Isr: 1
```

Finally, to delete a configuration we use : 

```
➜  conduktor-platform kafka-configs.sh --bootstrap-server localhost:9092 --entity-type topics --entity-name configured-topic --alter --delete-config min.insync.replicas
Completed updating config for topic configured-topic.
➜  conduktor-platform kafka-topics.sh  --bootstrap-server localhost:9092 --topic configured-topic --describe                                                            
Topic: configured-topic	TopicId: IysKmDYBQmGCV31cZgmoxA	PartitionCount: 3	ReplicationFactor: 1	Configs: 
	Topic: configured-topic	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
	Topic: configured-topic	Partition: 1	Leader: 1	Replicas: 1	Isr: 1
	Topic: configured-topic	Partition: 2	Leader: 1	Replicas: 1	Isr: 1

```

and describing the topic shows that the dynamic configuration was removed.

## Partitions and segments
- Topics are made of partitions
- Partitions are made of segments (files)
- Only one segment is ACTIVE (the one data is being written to)
- Two segment settings :
    - `log.segment.bytes` : the max size of a single semgent in bytes (default 1GB)
    - `log.segment.ms` : the time Kafka will wait before committing the segment if not null (default 1 week)
        - If you send data to Kafka over a week of time but you haven't reach 1GB of data, Kafka will no matter what close the segment (because it's been a week) and open a new segment for you
- Kafka keeps on creating new segments (or files) based on either size or time
- Segments come with two indexes (files) : 
    - An offset to position index : helps Kafka find where to read from to find a message
    - A timestamp to offset index : helps Kafka find messages with a specific timestamp
- A smaller `log.segment.bytes` means : 
    - More segments per partitions
    - Log compation happens more often
    - BUT Kafka must keep more files opened (*Error : Too many open files*)
    - => *Ask yourself* : how fast will I have new segments based on **throughput** ?
- A smaller `log.segment.ms` means : 
  - You set a max frequency for log compaction (more frequent triggers)
  - Maybe you want daily compaction instead of weekly
  - => *Ask yourself* : how often do I need log compaction to happen?

  ## Log cleanup policies
- Many Kafka clusters make data expire, according to a policy
- That concept is called **log cleanup**
- There are two policies : 
    - Policy 1 : `log.cleanup.policy=delete` (Kafka default for all user topics)
        - Delete based on age of data (default is one week)
        - Delete base on max size of log (default is -1== infinite)
    - Policy 2 : `log.clean.policy=compact` (Kafka default for topic *__consumer_offsets*)
        - Delete based on keys of your messages
        - Will delete old duplicate keys **after** the active segment is committed
        - Infinite time and space retention
- Delete data from Kafka allows you to : 
    - Control the size of the data on the disk, delete obsolete data
    - Overall : limit maintenance work on the Kafka cluster
- How often does log cleanup happen?
    - Log cleanup happens on your partition segments!
    - Smaller / more segments means that log cleanup will happen more often
    - Log cleanup shouldn't happen too often, it takes CPU and RAM resources
    - The cleaner checks for work every 15 seconds (`log.cleaner.backoff.ms`)
    - There is no API to trigger log compaction (a job runs in background and is not controllable)
- `log.retention.hours` : 
    - Number of hours to keep data for (default is 168 = one week)
    - Higher number mean more disk space
    - Lower number means that less data is retained (if you consumers are down for too long, the can miss data)
    - Other parameters allowed : `log.retention.ms`, `log.retention.minutes` (small unit has precedence)
- `log.retention.bytes` : 
    - Max size in bytes for each partition (default is -1 == infinite)
    - Useful to keep the size of a log under a threshold
- One week of retention : 
    - `log.retention.hours=168` and `log.retention.bytes=-1`
- Infinite time retention bounded by 500MB : 
    - `log.retention.ms=-1` and `log.retention.bytes=52428800`

### Log cleanup policy : Compact
- Log compaction ensures that your log contains at least the last knwon value for a specific key within a partition
- Very useful if we just require a snapshot instead of full history (such as for a data table in a database)
- The idea is that we only keep the latest "update" for a key in our log

#### Log compaction guarantees
- **Any consumer that is reading from the tail of a log (must current data) will still see all the messages sent to the topic**
- Ordering of messages is kept, log compaction only removes some messages, but does not re-order them
- The offset of a message is immutable. Offsets are just skipped if a message is missing
- Deleted records can still be seen by consumers for a period of `delete.retention.ms` (default is 24 hours)
