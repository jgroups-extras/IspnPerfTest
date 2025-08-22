Monitoring Benchmark with Prometheus and Grafana
===

This directory has an example on how to deploy Prometheus and Grafana to monitor the benchmark instances

# Requirements

* Docker
* Docker-Compose
* The benchmark instance must be accessible and started with option `-metrics-port <port>`. The `<port>` must be open in the firewall too.

# Procedure

* Update the file `infinispan.yaml` wit the IP and ports of all the benchmark instances. 
Under the `static_configs` section, set the `targets` list with all the IPs and port.
* Optionally, edit the `docker-compose.yaml` to use a different username and password for Grafana.
Those are set under `GF_SECURITY_ADMIN_USER` and `GF_SECURITY_ADMIN_PASSWORD` environment variables.
* Start the containers with `docker-compose up -d`.
* Grafana will be available at `http://127.0.0.1:3000/`.
It includes a simple dashboard with the CPU usage, reads and writes heat map and 99th percentile data.