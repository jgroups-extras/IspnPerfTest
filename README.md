
IspnPerfTest
============

Author: Bela Ban

Tests performance of Infinispan and other data grids.

There's a dual build: one can either use ant/ivy or maven. To build using ant, execute 'ant'. To use maven, execute
'mvn install'.

To run: bin/perf-test.sh (for Infinispan), bin/hc-perf-test.sh (for Hazelcast) and bin/coh-perf-test.sh (for Coherence)

Run 4 instances (for example) and then populate the cache (press 'p'). This will insert keys 1 - 50'000, each with
a byte[] value of 1000 bytes. If the number of keys or the value size should be changed, do this before populating the
cache, after having started the instances. This is done via '7' (number of keys) and '8' (value size).

Make sure that the cluster forms correctly, or else performance numbers will not be correct, e.g. if there is only
a single instance in a cluser, performance will be much better than if 4 cluster nodes have to communicate with each
other and serialize data over the network.

Once every node has 50'000 keys, press '2' in one instance. This will tell every node to start their tests and tally
the results when done. The test does 50'0000 gets (80%) and puts (20%) on randomly selected keys.

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
  
Hazelcast test
--------------
* Run bin/hc-perf-test.sh to test Hazelcast
* To configure the Hazelcast cache, change conf/hazelcast.xml
* You may need to remove the hazelcast-sources.jar in ./lib as it contains a duplicate configuration.


Oracle Coherence test
---------------------
To run the Coherence test, you'll need to
* download the Coherence JAR (requires registration) into the local maven repo (see [1] for details)
* uncomment the Coherence section in ivy.xml, and run "ant clean-all compile" to fetch the coherence jar from
  the local maven repo
* ant clean-all compile, then run ./bin/coh-per-test.sh
* The configuration used for Coherence is conf/coh.xml


[1] http://coherence-community.github.io/coherence-incubator/12.1.0/building.html