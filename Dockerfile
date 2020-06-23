
## The first stage is used to git-clone and build JGroups; this requires a JDK/javac/git/ant
FROM adoptopenjdk/openjdk11 as build-stage
RUN apt-get update ; apt-get install -y git maven net-tools netcat iputils-ping

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

COPY --from=build-stage /IspnPerfTest /opt/ispn/IspnPerfTest
COPY --from=build-stage /bin/ping /bin/netstat /bin/nc /bin/
COPY --from=build-stage /sbin/ifconfig /sbin/

RUN chown -R ispn.ispn $HOME/*

# Run everything below as the ispn user. Unfortunately, USER is only observed by RUN, *not* by ADD or COPY !!
USER ispn

RUN chmod u+x $HOME/*
#CMD clear && cat $HOME/IspnPerfTest/README && /bin/bash
CMD exec $HOME/IspnPerfTest/bin/perf-test.sh -nohup $*


