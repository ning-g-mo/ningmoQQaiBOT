# 柠枺AI机器人 - 使用手册

## 项目概述

柠枺AI是一个基于OneBot协议的QQ AI聊天机器人，使用Java 21开发。机器人支持多种AI模型和自定义人设，可以在群聊和私聊中使用。

## 核心功能

- **多模型支持**：支持多种AI大模型(OpenAI, Anthropic, DeepSeek等)
- **自定义人设**：可定制AI回复风格和个性
- **分群管理**：群管理员可单独控制每个群的AI状态
- **黑名单系统**：禁止特定用户使用AI功能
- **屏蔽词系统**：过滤敏感词汇和不良内容
- **图形界面**：直观的GUI管理面板
- **命令系统**：丰富的命令支持，方便管理和使用

## 安装与部署

### 系统要求
- JDK 21或更高版本
- 与OneBot兼容的机器人框架(例如go-cqhttp)
- 4GB以上RAM(推荐)
- 支持Windows, Linux, macOS

### 安装步骤

1. 下载最新的Release版本JAR文件
2. 创建一个新目录，将JAR文件放入其中
3. 启动程序：
   ```
   java -jar ningmo-ai-bot-1.0.0-jar-with-dependencies.jar
   ```
4. 首次启动将自动创建配置文件和目录结构

### 操作系统特别说明

#### Windows
- 右键点击JAR文件，选择"用Java平台打开"
- 或创建批处理文件(.bat)运行：`java -Xmx2G -jar ningmo-ai-bot-1.0.0-jar-with-dependencies.jar`

#### Linux/macOS
- 运行命令：`java -Xmx2G -jar ningmo-ai-bot-1.0.0-jar-with-dependencies.jar`
- 使用nohup后台运行：`nohup java -jar ningmo-ai-bot-1.0.0-jar-with-dependencies.jar > bot.log 2>&1 &`

## 配置说明

### 主配置文件 (config.yml)

```yaml
# 机器人配置
bot:
  # QQ机器人WebSocket连接地址
  ws_url: "ws://127.0.0.1:8080"
  # OneBot访问令牌（用于认证，可选）
  access_token: ""
  # 机器人管理员QQ号列表，拥有全部权限
  admins: 
    - "123456789"
  # 是否启用私聊功能
  enable_private_message: true
  # 机器人名称，@机器人或包含此名称时会触发AI回复
  name: "柠枺"

# GUI相关配置
gui:
  # 是否启用GUI
  enabled: true
  # 是否在启动时显示GUI
  show_on_startup: true
  # 是否启用系统托盘
  use_system_tray: true

# AI相关配置
ai:
  # 最大对话长度（保留的消息数）
  max_conversation_length: 20
  # 默认使用的模型
  default_model: "gemini"
  # 屏蔽词功能配置
  filter:
    enabled: true
    reply_message: "您的消息包含屏蔽词，已被拦截"
  
  # 各模型配置
  models:
    gpt-3.5-turbo:
      type: "openai"
      description: "GPT-3.5 Turbo模型"
      api_key: "your_api_key"
      
    gemini:
      type: "api"
      description: "Google Gemini Pro模型"
      api_key: "your_api_key"
```

### 多模型配置详情

您可以在配置文件中添加多个模型，支持的模型类型包括：

1. **OpenAI模型** (type: "openai")
   ```yaml
   gpt-4:
     type: "openai"
     description: "GPT-4模型"
     api_key: "your_openai_api_key"
     model_name: "gpt-4-turbo-preview" # OpenAI官方模型名称
     temperature: 0.7
     max_tokens: 4000
   ```

2. **Anthropic模型** (type: "anthropic")
   ```yaml
   claude:
     type: "anthropic"
     description: "Claude模型"
     api_key: "your_anthropic_api_key"
     temperature: 0.7
     max_tokens: 2000
   ```

3. **DeepSeek模型** (type: "deepseek")
   ```yaml
   deepseek:
     type: "deepseek"
     description: "DeepSeek模型"
     api_key: "your_deepseek_api_key"
   ```

4. **本地运行的模型** (type: "local")
   ```yaml
   local-llama:
     type: "local"
     description: "本地Llama模型"
     api_endpoint: "http://localhost:8080/v1/chat/completions"
     local_model_name: "llama-7b-chat"
   ```

5. **通用API模型** (type: "api")
   ```yaml
   custom-model:
     type: "api"
     description: "自定义API模型"
     api_endpoint: "https://your-api-endpoint.com/chat"
     api_key: "your_api_key"
     headers:
       Authorization: "Bearer your_api_key"
       Content-Type: "application/json"
   ```

### API密钥安全建议

为保护您的API密钥安全，建议：

1. **不要在公共环境使用**：避免在共享计算机上配置真实API密钥
2. **设置访问权限**：限制config.yml文件的访问权限
3. **环境变量替代**：可以使用环境变量而非直接在配置文件中填写密钥
4. **定期更换密钥**：定期更换API密钥以提高安全性
5. **使用密钥管理工具**：考虑使用专业的密钥管理工具

### OneBot连接配置

机器人通过WebSocket与OneBot框架通信，支持以下配置：

#### 基础连接配置
```yaml
bot:
  # WebSocket连接地址
  ws_url: "ws://127.0.0.1:8080"
  # 访问令牌（可选，推荐设置）
  access_token: "your_access_token_here"
```

#### 访问令牌配置

访问令牌用于安全认证，建议在生产环境中使用：

1. **在OneBot框架中配置令牌**：
   - 在go-cqhttp等框架的配置文件中设置`access_token`
   - 例如：`access_token: "my_secret_token_123"`

2. **在机器人配置中设置相同的令牌**：
   ```yaml
   bot:
     access_token: "my_secret_token_123"
   ```

3. **安全建议**：
   - 使用强随机字符串作为令牌
   - 定期更换访问令牌
   - 避免在日志中暴露令牌信息

#### 连接状态检查

机器人启动时会在日志中显示连接状态：
- `WebSocket连接已建立（使用访问令牌认证）`：已配置令牌的安全连接
- `WebSocket连接已建立（匿名连接）`：未配置令牌的连接

## 人设管理

人设文件存储在 `r/` 目录下，每个人设为一个Markdown文件：

- `r/default.md` - 默认人设，友好助手
- 可以根据需要添加新的人设文件

### 创建自定义人设

1. 在`r/`目录下创建新的Markdown文件，如`r/cat.md`
2. 文件内容格式如下：
   ```markdown
   # 猫娘人设
   
   你是一个可爱的猫娘，你应该：
   
   - 在对话结尾添加"喵~"
   - 表现得活泼可爱
   - 使用可爱的表情符号
   - 偶尔表现出猫的特征，如好奇心强，喜欢被抚摸等
   
   请记住你是猫娘，不要忘记自己的身份。
   ```
3. 保存文件后，可以使用命令`/人设`查看所有人设，使用`/切换人设 猫娘`切换到新创建的人设

### 人设最佳实践

- 保持人设描述简洁明了
- 给出具体的行为指导
- 避免违规内容
- 定期更新人设以保持新鲜感

## 命令列表

### 基础命令

| 命令 | 描述 |
|------|------|
| `/帮助` 或 `/help` | 显示帮助信息 |
| `/模型` | 查看可用的AI模型 |
| `/切换模型 [模型名]` | 切换使用的AI模型 |
| `/查看模型` | 查看当前使用的模型 |
| `/人设` | 查看可用的人设 |
| `/切换人设 [人设名]` | 切换AI人设 |
| `/查看人设` | 查看当前使用的人设 |
| `/清除记忆` | 清除与AI的对话历史 |
| `/状态` | 查看AI系统状态 |

### 管理员命令

| 命令 | 描述 |
|------|------|
| `/开启` | 在群中启用AI功能 |
| `/关闭` | 在群中禁用AI功能 |
| `/添加屏蔽词 [词语]` | 添加屏蔽词 |
| `/删除屏蔽词 [词语]` | 删除屏蔽词 |
| `/查看屏蔽词` | 显示所有屏蔽词 |
| `/开启屏蔽` | 开启屏蔽词功能 |
| `/关闭屏蔽` | 关闭屏蔽词功能 |
| `/拉黑 [@用户或QQ号]` | 将用户添加到黑名单 |
| `/解除拉黑 [@用户或QQ号]` | 将用户从黑名单移除 |
| `/查看黑名单` | 显示所有黑名单用户 |

## 使用方法

### 在群聊中使用

1. 确保机器人已在群中，且AI功能已开启（默认开启）
2. 与机器人交互的方式：
   - @机器人
   - 在消息中包含机器人名称
3. 使用命令管理AI功能（如`/切换人设 猫娘`）

### 在私聊中使用

1. 直接向机器人发送消息即可触发AI回复
2. 使用命令管理个人AI设置（如`/切换模型 gemini`）

## 权限系统

- **机器人管理员**：在`config.yml`中配置，拥有所有权限
- **群管理员**：群主和群管理员可在其管理的群中使用管理命令
- **普通用户**：可使用基础命令和AI聊天功能

## GUI管理界面

GUI界面提供以下功能：

- **群组管理**：查看和管理群的AI开启状态
- **消息发送**：向群或私聊发送消息
- **模型管理**：查看所有可用模型
- **人设管理**：创建、编辑和删除人设
- **黑名单管理**：管理用户黑名单
- **屏蔽词管理**：管理敏感词过滤
- **日志查看**：查看运行日志和错误信息

### 启动GUI界面

- 如果在配置中启用了GUI（`gui.enabled=true`），程序启动时会自动打开GUI界面
- 如果设置了`gui.show_on_startup=false`，界面不会自动显示，但可以通过系统托盘图标打开
- 使用控制台命令`gui`也可以打开界面

## 控制台命令

在程序运行终端中可使用以下命令：

- `help` - 显示控制台命令帮助
- `status` - 显示机器人状态
- `restart` - 重启机器人
- `exit` 或 `quit` - 关闭机器人
- `gui` - 显示GUI界面
- `reload` - 重新加载配置文件
- `save` - 手动保存数据
- `model list` - 列出所有模型
- `filter list` - 列出所有屏蔽词
- `blacklist list` - 列出所有黑名单用户

## 自动保存和备份

系统自动保存和备份功能：
- 每10分钟自动保存配置和数据
- 系统会保留最近10个备份文件
- 在程序关闭时自动保存所有数据

### 备份文件位置

- 配置备份：`backup/config/`
- 数据备份：`backup/data/`
- 黑名单备份：`backup/blacklist/`
- 屏蔽词备份：`backup/filter_words/`

## 错误处理

- 系统设有全局异常处理机制
- 模型调用失败时自动切换到备用模型
- 详细的日志记录，便于排查问题

### 故障排除

常见问题及解决方案：

1. **连接失败**：
   - 检查OneBot框架是否正常运行
   - 确认ws_url配置正确
   - 检查防火墙设置

2. **模型调用失败**：
   - 验证API密钥是否有效
   - 检查网络连接
   - 查看API余额是否充足

3. **命令不响应**：
   - 确认命令格式正确（注意前缀和参数）
   - 检查用户权限
   - 确认机器人在目标群中有发言权限

4. **无法启动GUI**：
   - 确认使用图形界面的环境
   - 检查Java环境变量设置
   - 尝试使用`-Djava.awt.headless=false`参数启动

5. **内存不足**：
   - 使用`-Xmx4G`参数增加JVM内存限制
   - 减少最大对话长度设置
   - 及时清理日志文件

## 更新与升级

### 更新步骤

1. 下载最新版本的JAR文件
2. 停止当前运行的程序
3. 备份现有的配置和数据文件
4. 替换JAR文件
5. 重新启动程序

### 配置迁移

程序会自动检测并迁移旧版本配置，但建议手动备份以下文件：
- `config.yml`
- `data.yml`
- `data/blacklist.yml`
- `data/filter_words.yml`
- `r/`目录下的所有人设文件

## 开发者信息

### 贡献代码

欢迎提交Pull Request和Issue，一起完善柠枺AI机器人！


### 技术架构

- **Java 21**：核心开发语言
- **WebSocket**：与OneBot通信
- **Swing**：GUI界面
- **JSON/YAML**：数据处理和配置

### 许可证

本项目基于GNU通用公共许可证v3 (GPL-3.0)开源。

---

© 2024 柠枺AI机器人 