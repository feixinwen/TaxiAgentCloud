# TaxiAgent Cloud

TaxiAgent Cloud 是 TaxiAgent 的云原生微服务重构项目。项目将保留原单体系统作为业务参考，按照服务边界重新实现代码，并逐步完成容器化、服务治理、可观测性和 Kubernetes 部署。

## 目标技术栈

- Java 21
- Spring Boot 3
- Spring Cloud 与 Spring Cloud Alibaba
- Nacos、Spring Cloud Gateway、OpenFeign、Sentinel
- MySQL、Redis、MongoDB、Elasticsearch
- RocketMQ（在需要可靠领域事件时引入）
- Docker、Docker Compose、K3s/Kubernetes
- OpenTelemetry、Prometheus、Grafana、Loki

## 当前状态

项目处于初始化阶段。技术版本、模块结构和服务边界将在架构决策记录确认后逐步落地。

## 设计原则

- 每个业务服务拥有自己的数据，禁止跨服务直接访问数据库。
- 服务内部使用本地事务，跨服务优先采用幂等、Outbox、可靠事件和补偿机制。
- Agent Service 负责编排，通过服务 API 使用用户、订单、工单和知识库能力。
- 所有密钥和密码通过环境变量或 Secret 注入，不提交到 Git。
