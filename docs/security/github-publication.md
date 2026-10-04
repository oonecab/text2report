# GitHub 发布说明

## 发布内容与本机配置

仓库中的 YAML、文档和示例使用环境变量或占位符表示凭据。本机 profile、环境配置笔记、Claude 本机设置和私钥文件由 `.gitignore` 排除。前端 `VITE_*` 配置会进入浏览器，只能放置可公开的配置。

提交前检查当前文件；如果需要检查暂存区中的实际内容，使用第二条命令：

```bash
python3 scripts/check_repository_privacy.py
python3 scripts/check_repository_privacy.py --staged
```

脚本检查常见 Token、私钥、密码字面量、敏感文件名、个人目录和联系人信息，输出仅包含路径、行号和问题类型。它是补充检查，专业扫描可使用 [Gitleaks](https://github.com/gitleaks/gitleaks)：

```bash
gitleaks git --log-opts="--all" --redact=100
```

## 新仓库的提交历史

此项目的早期历史包含过凭据。普通的删除提交和 `.gitignore` 不会清除旧提交中的内容；已提交过的 API Key 和数据库密码应更换。[GitHub 的凭据清理说明](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository) 介绍了历史清理和凭据轮换的边界。

新仓库可以从已脱敏并复查的当前源码初始化。先提交当前清理结果，再导出源码：

```bash
git archive --format=tar --output=../text2report-source.tar HEAD
```

将源码包解压到新目录后，运行 `python3 scripts/check_repository_privacy.py --directory .` 和 `gitleaks dir . --redact=100`，然后在该目录初始化 Git。初次提交前，可将提交者邮箱配置为 GitHub 提供的 noreply 地址，以控制公开的个人信息。

如果需要保留完整历史，应在独立克隆中完成历史脱敏，复查全部分支和标签后再发布。
