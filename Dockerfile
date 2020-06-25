
## The first stage is used to git-clone and build JGroups; this requires a JDK/javac/git/ant
FROM adoptopenjdk/openjdk11 as build1
RUN apt-get update ; apt-get install -y git maven net-tools netcat iputils-ping dnsutils

FROM build1 as build2
## Download and build JGroups src code
RUN git clone https://github.com/belaban/IspnPerfTest.git
RUN cd IspnPerfTest ; mvn -DskipTests=true package dependency:copy-dependencies

# For the runtime, we only need a JRE (smaller footprint)
FROM adoptopenjdk/openjdk11:jre as setup-stage
LABEL maintainer="Bela Ban (belaban@mailbox.org)"
RUN useradd --uid 1000 --home /opt/ispn --create-home --shell /bin/bash ispn
RUN echo root:root | chpasswd ; echo ispn:ispn | chpasswd
RUN printf "\nispn ALL=(ALL) NOPASSWD: ALL\n" >> /etc/sudoers
# EXPOSE 7800-7900:7800-7900 9000-9100:9000-9100
ENV HOME /opt/ispn
ENV PATH $PATH:$HOME/IspnPerfTest/bin
WORKDIR /opt/ispn

COPY --from=build2 /IspnPerfTest /opt/ispn/IspnPerfTest
COPY --from=build2 /bin/ping /bin/netstat /bin/nc /bin/
COPY --from=build2 /sbin/ifconfig /sbin/
COPY --from=build2 /usr/bin/dig /usr/bin/nslookup /usr/bin/
COPY --from=build2 /lib/libpcap* /lib/
COPY --from=build2 /usr/lib/x86_64-linux-gnu/libdns.* /usr/lib/x86_64-linux-gnu/

RUN chown -R ispn.ispn $HOME/*

# Run everything below as the ispn user. Unfortunately, USER is only observed by RUN, *not* by ADD or COPY !!
USER ispn

RUN chmod u+x $HOME/*
#CMD clear && cat $HOME/IspnPerfTest/README && /bin/bash
CMD exec $HOME/IspnPerfTest/bin/perf-test.sh -nohup $*


