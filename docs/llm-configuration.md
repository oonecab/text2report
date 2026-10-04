# LLM 配置

聊天模型通过管理员页面 `/admin/models` 维护。Provider 与能力路由保存在元数据库中；新环境需要新增、启用并激活模型。激活操作会建立 `SQL_GENERATION`、`SQL_REPAIR`、`RESULT_SUMMARY` 三条路由。Embedding 单独使用 Ollama 的 BGE-M3，不随聊天模型激活而切换。

## 协议与配置

当前实现使用 OpenAI 兼容的 Chat Completions 请求。以 DeepSeek 为例，Base URL 可填写 `https://api.deepseek.com/v1`，端点填写 `/chat/completions`，模型编码按服务提供方的实际名称填写。支持该能力的服务可选择 `response_format`，后端发送 `{"type":"json_object"}`；Prompt 约束模式不发送这个字段，但仍要求返回可解析的 JSON。

无鉴权的本地兼容网关可选择 `NONE`。当前其他鉴权类型使用 `Authorization: Bearer <API Key>`；自定义 Header、AK/SK 签名及原生 Gemini、Anthropic 等协议需要额外实现。填写这些选项不代表对应协议已经可用。

## 密钥与本机配置

API Key 由管理员在模型配置界面提供，列表接口仅返回脱敏信息。**当前后端将密钥以 Base64 编码写入 `meta_llm_provider_secret`，版本标记为 `base64-v1`；这是可逆编码，不是加密。** 应将元数据库及其备份按含明文凭据的敏感资产管理，生产部署前完成真正的加密或密钥托管。

本机数据库密码、JWT 密钥等部署凭据保存在被 Git 忽略的 `backend/src/main/resources/application-local.yml` 或环境变量中。可从 `application-local.yml.template` 创建本机配置，并为各环境生成独立凭据。聊天模型路由由管理功能建立，单独设置 YAML 或旧版 `LLM_API_KEY` / `LLM_BASE_URL` 不会激活当前路由。

仓库中的配置示例只保留变量名和占位符，文档、截图、评审记录和测试代码也应如此。发布前运行 `python3 scripts/check_repository_privacy.py`，并参考 [GitHub 发布说明](security/github-publication.md) 检查源码和提交历史。
