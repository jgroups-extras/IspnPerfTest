
IspnPerfTest
============

Author: Bela Ban

Tests performance of Infinispan and other data caches.

To run: bin/perf-test.sh (for Infinispan), bin/hc-perf-test.sh -cfg hazelcast.xml (for Hazelcast)
        and bin/coh-perf-test.sh (for Coherence)

Run 4 instances (for example) and then populate the cache (press 'p'). This will insert keys 1 - 50'000, each with
a byte[] value of 1000 bytes.

Once every node has 50'000 keys, press '2' in one instance. This will tell every
node to start their tests and tally the results when done. The test does 50'0000 gets (80%) and puts (20%) on random
keys.


Oracle Coherence test
---------------------
To run the Coherence test, you'll need to
* download the Coherence JAR (requires registration) into the local maven repo (see [1] for details)
* uncomment the Coherence section in ivy.xml, and run "ant clean-all compile" to fetch the coherence jar from
  the local maven repo
* ant clean-all compile, then run ./bin/coh-per-test.sh

Hazelcast
---------
* You may need to remove the hazelcast-sources.jar in ./lib as it contains a duplicate configuration.


[1] http://coherence-community.github.io/coherence-incubator/12.1.0/building.html