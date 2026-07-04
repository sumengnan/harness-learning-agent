# Harness 学习小助手 · 前端

Vite + React + TypeScript 单页应用。提交任务 → SSE 实时看 agent L1–L6 逐步进展 → 展示结果与证据。

## 开发

    npm install
    npm run dev      # 5173，代理 /runs* 到后端 8080

先启动后端：`mvn spring-boot:run`（需 DEEPSEEK_API_KEY 才能真跑 agent）。

## 测试

    npm test         # Vitest + React Testing Library，假 EventSource，无需后端

## 构建

    npm run build    # 产物输出到 ../src/main/resources/static，由 Spring Boot 同源托管

`src/main/resources/static/` 是构建产物，已 gitignore，不提交。`mvn` 不联动 Node。
