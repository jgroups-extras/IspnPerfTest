
IspnPerfTest
============

Author: Bela Ban

Tests performance of Infinispan and other data caches.


Requirements
============

* Create an Oracle account at <https://profile.oracle.com/myprofile/account/create-account.jspx>
* Accept the license for the Maven Repo at <https://www.oracle.com/webapps/maven/register/license.html>
* Add to your maven-settings.xml the Oracle account credentials (usually located on MAVEN_HOME/conf/settings.xml or HOME/.m2/settings.xml):

```
<server>
    <id>maven.oracle.com</id>
    <username>put Oracle username</username>
    <password>put Oracle password</password>
    <configuration>
      <basicAuthScope>
        <host>ANY</host>
        <port>ANY</port>
        <realm>OAM 11g</realm>
      </basicAuthScope>
      <httpConfiguration>
        <all>
          <params>
            <property>
              <name>http.protocol.allow-circular-redirects</name>
              <value>%b,true</value>
            </property>
          </params>
        </all>
      </httpConfiguration>
    </configuration>
</server>
```
* Build with ```mvn clean install```

Infinispan Test
---------------

To run: bin/perf-test.sh -cfg infinispan.xml (for Infinispan)


Oracle Coherence test
---------------------

./bin/coh-per-test.sh
