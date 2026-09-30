package com.khm.group.center.utils.format

import com.khm.group.center.db.model.client.GpuTaskInfoModel

/**
 * 训练启动命令（gpu_task_info.command_line）的凭据脱敏。
 *
 * 研发习惯把 wandb key、HF token、OpenAI key 直接写进命令行参数（`--wandb_key=xxx`），
 * 而 command_line 会随任务详情一起返回给前端、且查询端点无鉴权，
 * 因此必须在【返回给前端之前】把凭据值替换成 [MASK]。
 *
 * 设计约束（两条，都是为了不破坏详情面板的可用性）：
 * 1. 只脱敏**值**，保留参数名本身：`--wandb_key=***` 而不是整段抹掉，
 *    详情面板仍能看出这个任务跑了哪些参数。
 * 2. 不做通用「值长得像密钥就脱敏」的猜测，只按**参数名**匹配：
 *    否则 `--lr 0.001`、`--max_tokens 4096` 之类普通参数会被误伤。
 *
 * 新增一种凭据时只需要往 [CREDENTIAL_NAME_PATTERNS] 里加一条正则，
 * 匹配范围（大小写、`-`/`_` 互换、可选的 `--` 前缀）由外层统一处理。
 */
object CommandLineSanitizer {

    /** 脱敏后的占位符。 */
    const val MASK: String = "***"

    /**
     * 凭据参数名的正则片段列表 —— 本类唯一的扩展点。
     *
     * 每个片段只描述「参数名的形状」，两侧的边界由 [ASSIGN_FORM]/[FLAG_FORM] 统一加上：
     * - 大小写不敏感（`WANDB_API_KEY` 与 `--wandb_key` 等价）；
     * - 允许 `-` 与 `_` 互换（`--api-key` 与 `--api_key` 等价）；
     * - 允许不带/带 `-`、`--` 前缀（PyTorch 风格的单横线同样覆盖）。
     *
     * 词元边界由 `(?![\w-])` 兜住，所以 `token_count`、`tokenizer_path`、
     * `password-file` 这类「以凭据词开头但并不是凭据」的参数不会被脱敏。
     */
    private val CREDENTIAL_NAME_PATTERNS: List<String> = listOf(
        // wandb：--wandb_key / --wandb-api-key / --wandb_token / WANDB_API_KEY
        "wandb[_-]?(?:api[_-]?)?(?:key|token)",

        // HuggingFace：--hf_token / --hf-token / --huggingface_token / HUGGING_FACE_HUB_TOKEN
        "hugging[_-]?face(?:[_-]?hub)?[_-]?token",
        "hf[_-]?token",

        // 通用：--api_key / --access-token / --secret / --password / --openai_api_key / --aws_secret_access_key
        // 前缀段允许任意单词，便于覆盖 OPENAI_API_KEY、AWS_SECRET_ACCESS_KEY 这类多词名；
        // 后缀限定在「密钥类」词上，所以 --db_path / --master_port / --access_log 不会被脱敏。
        "(?:[a-z0-9]+[_-])*(?:api|access|secret|private|auth|openai|master|app|aws|azure|gcp|db|database)" +
                "[_-]?(?:key|keys|token|secret|password|passwd|pwd|credential|credentials)",

        // 裸词：--token / --secret / --password / --bearer
        "token",
        "secret",
        "secrets",
        "password",
        "passwd",
        "pwd",
        "credential",
        "credentials",
        "bearer",
        "salt",
    )

    /** 参数名片段合并后的匹配体（非捕获组包裹，避免与外层交替产生优先级歧义）。 */
    private val NAME: String = CREDENTIAL_NAME_PATTERNS.joinToString("|", prefix = "(?:", postfix = ")")

    /** 左侧边界：前面不能是「标识符字符」，否则 `--num_workers` 里的 `workers` 之类会被误当作参数名起点。 */
    private const val LEFT_BOUNDARY = """(?<![A-Za-z0-9_./:-])"""

    /** 参数名之后的边界：后面不能接着标识符字符或连字符（`token_count`、`password-file` 因此不匹配）。 */
    private const val RIGHT_BOUNDARY = """(?![\w-])"""

    /** 单个值：整串带引号的，或不含空白/引号的一串。 */
    private const val VALUE = """(?:"[^"]*"|'[^']*'|[^\s'"]+)"""

    /**
     * 赋值形式：`--wandb_key=abc`、`WANDB_API_KEY=abc`、`--token = "abc 123"`。
     * 捕获组 1 = 参数名（含横线前缀）、2 = 分隔符（保留原样，避免改动参数之间的空白）、3 = 值。
     */
    private val ASSIGN_FORM = Regex(
        """$LEFT_BOUNDARY((?:--|-)?$NAME)$RIGHT_BOUNDARY(\s*=\s*)($VALUE)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 空格分隔形式：`--hf_token hf_xxx`、`--password hunter2`。
     * 这里要求参数名带 `-`/`--` 前缀：无前缀的裸 `token xxx` 更可能是位置参数，
     * 而赋值形式（`TOKEN=xxx`）已经由 [ASSIGN_FORM] 覆盖。
     */
    private val FLAG_FORM = Regex(
        """$LEFT_BOUNDARY((?:--|-)$NAME)$RIGHT_BOUNDARY(\s+)($VALUE)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 看起来像另一个开关而不是值的 token（`--num_workers`）。
     * 空格分隔形式下必须排除，否则 `--token --num_workers 8` 会被改写成
     * `--token *** 8`，把一个「没给值的开关」吃掉、把后面的开关吞掉。
     */
    private val FLAG_LIKE = Regex("""-{1,2}[A-Za-z_][\w-]*""")

    /**
     * 脱敏一条命令行里的凭据值。
     *
     * @param commandLine 原始命令行
     * @return 脱敏后的命令行；无凭据时原样返回（不改变空白与引号）
     */
    fun maskCredentials(commandLine: String): String {
        if (commandLine.isEmpty()) return commandLine

        val assigned = ASSIGN_FORM.replace(commandLine) { m ->
            // 显式写了 `=` 的值一律按值处理，即便它长得像开关
            m.groupValues[1] + m.groupValues[2] + maskValue(m.groupValues[3], flagGuard = false)
        }
        return FLAG_FORM.replace(assigned) { m ->
            m.groupValues[1] + m.groupValues[2] + maskValue(m.groupValues[3], flagGuard = true)
        }
    }

    /**
     * 脱敏单条任务的 [GpuTaskInfoModel.commandLine]。
     *
     * 原地改写：这些对象都是每次查询现查现用（不缓存、不跨请求共享），
     * 深拷贝 36 个字段只会让调用点更容易漏掉某个字段。数据库写入路径不走这里。
     */
    fun sanitize(task: GpuTaskInfoModel): GpuTaskInfoModel {
        task.commandLine = maskCredentials(task.commandLine)
        return task
    }

    /** 批量脱敏（见 [sanitize] 关于原地改写的说明）。 */
    fun sanitizeAll(tasks: List<GpuTaskInfoModel>): List<GpuTaskInfoModel> {
        tasks.forEach { sanitize(it) }
        return tasks
    }

    /**
     * 把一个捕获到的值替换成 [MASK]，保留原有的引号。
     *
     * @param flagGuard 空格分隔形式下跳过「看起来像开关」的值（见 [FLAG_FORM]）
     */
    private fun maskValue(raw: String, flagGuard: Boolean): String {
        if (raw.length >= 2) {
            val quote = raw.first()
            if ((quote == '"' || quote == '\'') && raw.last() == quote) {
                return "$quote$MASK$quote"
            }
        }
        if (flagGuard && FLAG_LIKE.matches(raw)) return raw
        // 幂等：已是 MASK 的值再脱敏仍是 MASK
        return MASK
    }
}
