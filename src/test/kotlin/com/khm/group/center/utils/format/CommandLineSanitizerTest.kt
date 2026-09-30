package com.khm.group.center.utils.format

import com.khm.group.center.db.model.client.GpuTaskInfoModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [CommandLineSanitizer] 的凭据脱敏单测。
 *
 * 断言分两类，缺一不可：
 * - **该脱敏的**：各种凭据写法（`=`/空格/环境变量/引号）都必须变成 `***`，
 *   否则等于没脱敏——这是安全属性。
 * - **不该脱敏的**：`--num_workers 8`、`--max_tokens 4096` 这类普通参数一旦被误伤，
 *   详情面板显示的命令就废了，用户没法复现任务——这是可用性属性。
 */
class CommandLineSanitizerTest {

    private fun mask(command: String) = CommandLineSanitizer.maskCredentials(command)

    // ==================== 赋值形式（key=value） ====================

    @Test
    fun `wandb key in equals form is masked`() {
        assertEquals(
            "python train.py --wandb_key=***",
            mask("python train.py --wandb_key=abc123")
        )
    }

    @Test
    fun `env style wandb key is masked`() {
        // 大小写不敏感：命令行参数与环境变量写法等价
        assertEquals(
            "WANDB_API_KEY=*** python train.py",
            mask("WANDB_API_KEY=abc123 python train.py")
        )
    }

    @Test
    fun `huggingface token in both separator forms is masked`() {
        assertEquals("python train.py --hf_token=***", mask("python train.py --hf_token=hf_xxx"))
        assertEquals("python train.py --hf-token=***", mask("python train.py --hf-token=hf_xxx"))
    }

    @Test
    fun `token api key secret and password are all masked`() {
        val masked = mask(
            "python train.py --token abc --api_key=xyz --api-key qrs --secret tuv --password wxy"
        )
        assertEquals(
            "python train.py --token *** --api_key=*** --api-key *** --secret *** --password ***",
            masked
        )
    }

    // ==================== 空格分隔形式（key value） ====================

    @Test
    fun `space separated value is masked and keeps the parameter name`() {
        assertEquals(
            "python train.py --hf_token *** --num_workers 8",
            mask("python train.py --hf_token hf_xxx --num_workers 8")
        )
    }

    @Test
    fun `value without dash prefix is not treated as a command flag`() {
        // 无前缀的裸 `token xxx` 更像位置参数；赋值形式已经由 ASSIGN_FORM 覆盖
        assertEquals("token abc python train.py", mask("token abc python train.py"))
    }

    @Test
    fun `quoted value keeps its quotes`() {
        // 引号本身不是凭据的一部分，保留后前端展示仍然读得懂
        assertEquals(
            """python train.py --wandb_key="***" --password='***'""",
            mask("""python train.py --wandb_key="abc 123" --password='p@ss word'""")
        )
    }

    // ==================== 混合：脱敏与保留并存 ====================

    @Test
    fun `mixed command masks credentials and keeps ordinary arguments`() {
        val command = "torchrun --nproc_per_node=8 train.py --num_workers 8 --batch_size 32 " +
                "--wandb_key=abc123 --lr 0.001 --HF_TOKEN hf_xxx --save_dir /data/exp1"

        assertEquals(
            "torchrun --nproc_per_node=8 train.py --num_workers 8 --batch_size 32 " +
                    "--wandb_key=*** --lr 0.001 --HF_TOKEN *** --save_dir /data/exp1",
            mask(command)
        )
    }

    @Test
    fun `ordinary command without credentials is returned unchanged`() {
        val command = "python -u /workspace/proj/train.py --config configs/llama.yaml --epochs 3"
        assertEquals(command, mask(command))
    }

    // ==================== 不得误伤普通参数 ====================

    @Test
    fun `num_workers and other numeric arguments are never masked`() {
        val command = "python train.py --num_workers 8 --batch_size 32 --nproc_per_node=4 --lr 0.001"
        assertEquals(command, mask(command))
    }

    @Test
    fun `parameters merely starting with a credential word are not masked`() {
        // 这些名字里有 token/secret，但值是计数或路径，不是凭据
        val command = "python train.py --max_tokens 4096 --tokenizer_path /data/tok " +
                "--token_type_ids --num_tokens 8 --secret_key_file /root/k --db_path /data/db " +
                "--master_port 29500 --access_log /var/log/app.log --enable_token_cache true"
        assertEquals(command, mask(command))
    }

    @Test
    fun `wandb non secret arguments are kept readable`() {
        // 详情面板要能看出实验名/团队名，所以 project/entity 不脱敏
        val command = "python train.py --wandb_project myproj --wandb_entity myteam"
        assertEquals(command, mask(command))
    }

    @Test
    fun `credential flag without value does not swallow the next flag`() {
        // --token 没给值，后面跟的是另一个开关；把它当值吃掉会改写成 `--token *** 8`
        val command = "python train.py --token --num_workers 8"
        assertEquals(command, mask(command))
    }

    // ==================== 边界 ====================

    @Test
    fun `empty command line is returned as is`() {
        assertEquals("", mask(""))
    }

    @Test
    fun `credential flag at the end without value is not masked`() {
        val command = "python train.py --wandb_key"
        assertEquals(command, mask(command))
    }

    @Test
    fun `masking is idempotent`() {
        // 同一批对象可能被多个出口各脱敏一次，重复处理不能变成 --wandb_key=******
        val once = mask("python train.py --wandb_key=abc123 --HF_TOKEN hf_xxx")
        assertEquals(once, mask(once))
        assertEquals("python train.py --wandb_key=*** --HF_TOKEN ***", once)
    }

    @Test
    fun `credential inside a path is not masked`() {
        // 路径里的 secret 目录名不是凭据
        val command = "/data/secret/tokenizer --num_workers 8"
        assertEquals(command, mask(command))
    }

    // ==================== 模型级入口 ====================

    @Test
    fun `sanitize rewrites commandLine in place and keeps other fields`() {
        val task = GpuTaskInfoModel().apply {
            taskId = "t-1"
            projectDirectory = "/data/exp1"
            commandLine = "python train.py --wandb_key=abc123"
        }

        val result = CommandLineSanitizer.sanitize(task)

        assertEquals("python train.py --wandb_key=***", result.commandLine)
        // 只动 commandLine：projectDirectory 是产品功能，保留
        assertEquals("/data/exp1", result.projectDirectory)
        assertEquals("t-1", result.taskId)
    }

    @Test
    fun `sanitizeAll masks every task in the list`() {
        val tasks = listOf(
            GpuTaskInfoModel().apply { commandLine = "python a.py --token=abc" },
            GpuTaskInfoModel().apply { commandLine = "python b.py --num_workers 8" }
        )

        CommandLineSanitizer.sanitizeAll(tasks)

        assertEquals("python a.py --token=***", tasks[0].commandLine)
        assertEquals("python b.py --num_workers 8", tasks[1].commandLine)
    }

    @Test
    fun `masked command no longer contains the original secret`() {
        val secret = "abc123"
        val masked = mask("python train.py --wandb_key=$secret --HF_TOKEN hf_xxx")
        assertFalse(masked.contains(secret), "原始凭据不得残留在结果中，实际: $masked")
        assertFalse(masked.contains("hf_xxx"), "原始凭据不得残留在结果中，实际: $masked")
        assertTrue(masked.contains("--wandb_key=***"), "参数名必须保留，实际: $masked")
    }
}
