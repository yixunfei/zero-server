# KCP monitoring templates

Import grafana.json and select your existing Prometheus datasource. The runtime MetricRegistry must already be connected to your exporter; these files do not start exporters or provision services.

Alerts are examples: tune thresholds to the workload. Counters are absolute cumulative values, byte fields and session counts are gauges. Labels only contain the configured listener and fixed reason; never add user IDs, addresses or conv.
