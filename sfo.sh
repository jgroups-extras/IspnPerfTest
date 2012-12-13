
#!/bin/bash

## Starts a node in the SFO site

site=SFO
backupSites=LON,NYC

flag="-Xms600m -Xmx600m"
flags="$flags -Djava.net.preferIPv4Stack=true -Dlog4j.configuration=file:/home/bela/log4j.properties"
flags="$flags -Dsite=$site -DbackupSites=$backupSites"
mvn $flags  exec:java -Dexec.mainClass=org.perf.Test