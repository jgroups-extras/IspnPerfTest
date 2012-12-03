
 #!/bin/bash

 ## Starts a node in the LON site

 site=LON
 backupSites=SFO,NYC

 flags="-Djava.net.preferIPv4Stack=true -Dlog4j.configuration=file:/home/bela/log4j.properties"
 flags="$flags -Dsite=$site -DbackupSites=$backupSites"
 mvn $flags  exec:java -Dexec.mainClass=org.perf.Test