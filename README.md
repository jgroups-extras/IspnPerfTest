
IspnPerfTest
============

Author: Bela Ban, Jose Bolina

Tests performance of Infinispan and other data grids.

There's a dual build: one can either use ant/ivy or maven. 

Ant and ivy build
-----------------
* Execute `ant` to retrieve all dependent JARs and compile all source files except the Coherence files
* Execute `ant compile-coh` to retrieve the Coherence JAR and then compile all source files. Note that ivy-coh.xml is
  used to retrieve the Coherence JAR file. See below for how to set up the Oracle maven repo for Coherence.

To run: bin/perf-test.sh (for Infinispan), bin/hc-perf-test.sh (for Hazelcast) and bin/coh-perf-test.sh (for Coherence)

Run 4 instances (for example) and then populate the cache (press 'p'). This will insert keys 1 - 50'000, each with
a byte[] value of 1000 bytes. If the number of keys or the value size should be changed, do this before populating the
cache, after having started the instances. This is done via '5' (number of keys) and '6' (payload size).

Make sure that the cluster forms correctly, or else performance numbers will not be correct, e.g. if there is only
a single instance in a cluser, performance will be much better than if 4 cluster nodes have to communicate with each
other and serialize data over the network.

Once every node has 50'000 keys, press '1' in one instance. This will tell every node to start their tests and tally
the results when done. The test invokes 50'000 gets (80%) and puts (20%) on randomly selected keys.

Note that there's a JGroups cluster created (via conf/control.xml) which is used to send configuration changes across
the cluster, and allow new members to join a cluster and get the current configuration. This is minimal traffic and
there is close to zero traffic during the actual tests.

The configurations used are all stored in conf. If a different configuration should be used, start the test with the
-cfg <config file> option.



Infinispan test
---------------
* To run the Infinispan test, execute bin/perf-test.sh
* This will configure Infinispan via conf/dist-sync.xml
* conf/dist-synx.xml refers to jgroups_tcp.xml (also in conf) which configures the JGroups subsystem to run over TCP.
  If UDP should be picked, change this to point to jgroups-udp.xml instead.
  
Hot Rod test
------------

* Requirements

Start the Infinispan server.
The easiest way would be to use docker:

`docker run -p 11222:11222 -e USER=my_user -e PASS=my_pass infinispan/server`

Note: adjust the ports as needed.

* Configure the Hot Rod client

Configure the client via `-Dhotrod.properties.file=/path/to/hotrod.properties`.

The minimal configuration includes the server list and the username/password.

```bash
infinispan.client.hotrod.server_list = 127.0.0.1:11222
infinispan.client.hotrod.auth_username = my_user
infinispan.client.hotrod.auth_password = my_pass
```

* Configure the cache

The same configuration file can be used as in `Infinispan test` (by default, `dist-sync.xml`).

You can override the configuration using `-cfg path/to/ispn.xml`. 

* Running the test

Putting everything together:

`FLAGS="-Dhotrod.properties.file=/path/to/hotrod.properties" ./bin/perf-test.sh -factory hr -cfg /path/to/ispn.xml`

  
Hazelcast test
--------------
* Run bin/hc-perf-test.sh to test Hazelcast
* To configure the Hazelcast cache, change conf/hazelcast.xml
* You may need to remove the hazelcast-sources.jar in ./lib as it contains a duplicate configuration.


Oracle Coherence test
---------------------
To run the Coherence test, you'll need to
* Download the Coherence JAR (requires registration) into the local maven repo, or setup a maven repo for 
  Oracle Coherence (see [1] for details)
* Run `ant compile-coh`, then run `./bin/coh-per-test.sh`
* The configuration used for Coherence is `conf/coh.xml`


DistCache test
--------------
This simple JGroups based cache mimicks Infinispan's DIST mode (with a fixed replication count of 2). A PUT is sent
to the primary node, which locks the cache and (asynchronously) updates the backup node.

To run:
* Run `bin/dist-perf-test.sh`


TriCache test
-------------
This cache mimicks the 'triangle approach' in Infinispan in which a PUT is sent to the primary by the originator,
the primary then forwards it to the backup, and the backup sends an ACK with the previous value back to the
originator. All calls are asynchronous and the caller blocks until it gets the ACK from the backup node, or the
timeout kicks in.
For details see ./doc/TriCache.txt.

To run:
* Run `bin/tri-perf-test.sh`


[1] http://coherence-community.github.io/coherence-incubator/12.1.0/building.html