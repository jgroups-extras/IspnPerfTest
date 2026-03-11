
## *****************************************************************************
## Make sure you have IspnPerfTest compiled (mvn clean package) before doing so!
## *****************************************************************************

# Multi-arch images with podman:
# First, initialise the manifest
# (optionally) podman manifest remove belaban/ispn-perf-test
# podman manifest create belaban/ispn-perf-test

# Build the image attaching them to the manifest
# podman build --platform linux/amd64,linux/arm64  --manifest belaban/ispn-perf-test  .

# Finally publish the manifest
# podman manifest push belaban/ispn-perf-test


FROM eclipse-temurin:24-jre as build-stage
RUN apt-get update ; apt-get install -y git maven ant net-tools netcat-traditional iputils-ping dnsutils emacs

# For the runtime, we only need a JRE (smaller footprint)
FROM eclipse-temurin:24-jre as make-dirs
LABEL maintainer="Bela Ban (belaban@mailbox.org)"
RUN useradd --uid 1001 --home /opt/ispn --create-home --shell /bin/bash ispn && \
    echo root:root | chpasswd && \
    echo ispn:ispn | chpasswd && \
    printf "\nispn ALL=(ALL) NOPASSWD: ALL\n" >> /etc/sudoers

ENV PATH $PATH:/opt/ispn/bin
WORKDIR /opt/ispn

COPY --from=build-stage /bin/ping /bin/netstat /bin/nc /bin/
COPY --from=build-stage /sbin/ifconfig /sbin/
COPY README.md /opt/ispn/
COPY ./bin    /opt/ispn/bin
COPY ./target /opt/ispn/target

RUN chown -R ispn:ispn /opt/ispn/*

# Run everything below as the ispn user. Unfortunately, USER is only observed by RUN, *not* by ADD or COPY !!
USER ispn

RUN chmod u+x $HOME/*
CMD exec $HOME/bin/perf-test.sh -nohup $*


