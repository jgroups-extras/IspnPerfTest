
IspnPerfTest
============

Author: Bela Ban

Tests performance of Infinispan and other data caches.

Oracle Coherence test
---------------------
To run the Coherence test, you'll need to

* download the Coherence JAR (requires registration) into the local maven repo (see [1] for details)
* uncomment the Coherence section in ivy.xml, and run "ant clean-all compile" to fetch the coherence jar from
  the local maven repo
* ant clean-all compile, then run ./bin/coh-per-test.sh

[1] http://coherence-community.github.io/coherence-incubator/12.1.0/building.html