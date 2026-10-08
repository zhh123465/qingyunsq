# CampusForum

基于多租户开源架构与 AI 增强的高校轻量化学习社群平台。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)
[![Vue 3](https://img.shields.io/badge/Vue-3.x-green.svg)](https://vuejs.org/)
[![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)

> ⚠️ **生产部署前必读：[deploy/SECURITY.md](deploy/SECURITY.md)**
> 自 commit `39875dc` 起完成了一轮全面安全加固。`application-prod.yml` 已移除多个弱默认值，缺失关键环境变量时后端会启动失败。请按 SECURITY.md 与 `deploy/.env.example` 完整配置。

## 项目简介

CampusForum 是一款面向全国高校、以学习互助为核心、采用"全校广场 + 专业学习空间"双层架构的开源轻量化校园学习社群平台。

- **学生侧**：提供专业、私密、有同伴感的学习与生活交流空间
- **学校侧**：零成本获得自主可控、数据归属本校的官方学习社群平台
- **行业侧**：MIT 协议开源，构建国内首个"轻量化、多租户、AI 增强"开源校园社区生态

## 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue3 + Composition API + Vite 5 + Naive UI + Pinia |
| 后端 | Java 17 + Spring Boot 3.x + MyBatis-Plus + Sa-Token |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7 + Caffeine（二级缓存） |
| 搜索 | MeiliSearch（MySQL FULLTEXT 兜底） |
| 存储 | 阿里云 OSS（生产默认）/ Local（开发与测试） |
| AI | LangChain4j + OpenAI 兼容协议 |
| 部署 | Docker Compose + K8s Helm（可选） |

## 快速开始

```bash
git clone git@github.com:zhh123465/qingyunsq.git
cd qingyunsq/deploy
cp .env.example .env
# 编辑 .env 文件，按 deploy/SECURITY.md 设置所有必填项（弱默认值会启动失败）
bash install.sh
```

## 项目结构

```
qingyunsq/
├─ backend/      # Spring Boot 应用
├─ frontend/     # Vue3 + Vite
├─ deploy/       # Docker Compose / Helm / 一键脚本
├─ docs/         # 需求/设计/用户/二次开发文档
├─ db/           # schema.sql + migrations
├─ .github/      # CI/CD workflows
└─ LICENSE       # MIT
```

## 开发指南

详见 [docs/DEVELOPMENT_GUIDE.md](docs/DEVELOPMENT_GUIDE.md) 和 [agents.md](agents.md)。

## 开源协议

MIT License. 详见 [LICENSE](LICENSE)。
