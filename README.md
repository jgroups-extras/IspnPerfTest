
IspnPerfTest
============

Author: Bela Ban

Tests performance of Infinispan and other data caches.

Oracle Coherence test
---------------------
To run the Coherence test, you'll need to

* download the Coherence JAR (requires registration) into the local maven repo
* uncomment the Coherence section in ivy.xml, and run "ant clean-all compile" to fetch the coherence jar from
  the local maven repo
* move CohTest.java.txt to CohTest.java