
# Build: docker build -f Dockerfile -t belaban/ispn-perf-test .
# Push: docker push belaban/ispn-perf-test

FROM adoptopenjdk/openjdk11 as build-stage
RUN apt-get update ; apt-get install -y git maven net-tools netcat iputils-ping dnsutils emacs


# For the runtime, we only need a JRE (smaller footprint)
FROM adoptopenjdk/openjdk11:jre as make-dirs
LABEL maintainer="Bela Ban (belaban@mailbox.org)"
RUN useradd --uid 1000 --home /opt/ispn --create-home --shell /bin/bash ispn
RUN echo root:root | chpasswd ; echo ispn:ispn | chpasswd
RUN printf "\nispn ALL=(ALL) NOPASSWD: ALL\n" >> /etc/sudoers
# EXPOSE 7800-7900:7800-7900 9000-9100:9000-9100
ENV HOME /opt/ispn
ENV ISPN_HOME=$HOME/IspnPerfTest
ENV PATH $PATH:$ISPN_HOME/bin

WORKDIR /opt/ispn

COPY --from=build-stage /bin/ping /bin/netstat /bin/nc /bin/
COPY --from=build-stage /sbin/ifconfig /sbin/
COPY --from=build-stage /usr/bin/dig /usr/bin/nslookup /usr/bin/
COPY --from=build-stage /lib/x86_64-linux-gnu/lib* /lib/x86_64-linux-gnu/
COPY --from=build-stage /usr/lib/x86_64-linux-gnu/lib* /usr/lib/x86_64-linux-gnu/
COPY README.md $ISPN_HOME/
COPY ./bin    $ISPN_HOME/bin
COPY ./target $ISPN_HOME/target

RUN chown -R ispn.ispn $HOME/*

# Run everything below as the ispn user. Unfortunately, USER is only observed by RUN, *not* by ADD or COPY !!
USER ispn

RUN chmod u+x $HOME/*
# CMD /bin/bash

#CMD clear && cat $HOME/IspnPerfTest/README && /bin/bash
CMD exec $HOME/IspnPerfTest/bin/perf-test.sh -nohup $*


