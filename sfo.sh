
 #!/bin/bash

 ## Starts a node in the LON site

 site=SFO
 backupSites=LON,NYC

 flags="-Djava.net.preferIPv4Stack=true -Dsite=$site -DbackupSites=$backupSites"
 mvn $flags  exec:java -Dexec.mainClass=org.perf.Test