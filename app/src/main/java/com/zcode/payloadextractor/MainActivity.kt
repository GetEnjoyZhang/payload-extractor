package com.zcode.payloadextractor

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.zcode.payloadextractor.core.Extractor
import com.zcode.payloadextractor.core.PayloadReader
import com.zcode.payloadextractor.core.RemoteZip
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var urlEdit: EditText
    private lateinit var parseBtn: Button
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var list: ListView

    private var zip: RemoteZip? = null
    private var entries: List<RemoteZip.Entry> = emptyList()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(f: () -> Unit) = runOnUiThread(f)
    private fun mb(v: Long) =
        if (v >= 1048576) "%.1f MB".format(v / 1048576.0) else "${v / 1024} KB"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            text = "OTA 在线提取"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        val subtitle = TextView(this).apply {
            text = "远程 ZIP + payload.bin 按需 Range 提取，无需下载整包"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        urlEdit = EditText(this).apply {
            hint = "粘贴 OTA 包 zip 的 URL (http/https)"
            setSingleLine(true)
        }
        parseBtn = Button(this).apply { text = "解析 ZIP 目录" }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
        }
        status = TextView(this).apply {
            setPadding(0, dp(10), 0, dp(6))
            setTextIsSelectable(true)
        }
        list = ListView(this)

        root.addView(title)
        root.addView(subtitle)
        root.addView(urlEdit)
        root.addView(parseBtn)
        root.addView(bar)
        root.addView(status)
        root.addView(
            list,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        setContentView(root)

        // 若剪贴板是 URL 则自动填入
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (t != null && (t.startsWith("http://") || t.startsWith("https://")) && t.contains(".zip")) {
                urlEdit.setText(t)
            }
        } catch (_: Exception) {}

        parseBtn.setOnClickListener { startParse() }
        list.setOnItemClickListener { _, _, pos, _ -> onEntryClick(entries[pos]) }
    }

    private fun startParse() {
        val url = urlEdit.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            status.text = "请输入有效的 http/https 链接"
            return
        }
        parseBtn.isEnabled = false
        bar.isIndeterminate = true
        bar.visibility = View.VISIBLE
        status.text = "正在解析远程 ZIP 中央目录…"
        thread {
            try {
                val z = RemoteZip(url)
                z.open()
                zip = z
                entries = z.listEntries()
                ui {
                    bar.visibility = View.GONE
                    status.text = "共 ${entries.size} 个条目，整包 ${mb(z.totalSize)}。" +
                        "\n点击普通条目直接提取；点击 payload.bin 选择分区。"
                    list.adapter = ArrayAdapter(
                        this, android.R.layout.simple_list_item_1,
                        entries.map { fmtEntry(it) }
                    )
                }
            } catch (e: Exception) {
                ui {
                    bar.visibility = View.GONE
                    status.text = "解析失败：${e.message}"
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
            } finally {
                ui { parseBtn.isEnabled = true }
            }
        }
    }

    private fun fmtEntry(e: RemoteZip.Entry): String {
        val kind = when {
            e.name.endsWith("payload.bin") -> "｜payload 包"
            e.randomAccess -> "｜已存储"
            else -> ""
        }
        return "${e.name}\n${mb(e.usize)}$kind"
    }

    private fun onEntryClick(e: RemoteZip.Entry) {
        if (e.name == "payload.bin" || e.name.endsWith("/payload.bin")) {
            bar.isIndeterminate = true
            bar.visibility = View.VISIBLE
            status.text = "正在解析 payload manifest…"
            thread {
                try {
                    val z = zip ?: throw IllegalStateException("请先解析 ZIP")
                    val mf = PayloadReader.parse { off, len -> z.readEntryBytes(e, off, len) }
                    val parts = mf.partitions
                    ui {
                        bar.visibility = View.GONE
                        status.text = "manifest 解析成功：${parts.size} 个分区，block=${mf.blockSize}"
                        AlertDialog.Builder(this)
                            .setTitle("选择要提取的分区")
                            .setItems(
                                parts.map { "${it.name}   (${mb(it.size)})" }.toTypedArray()
                            ) { _, i -> extractPartition(e, mf, parts[i]) }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                } catch (ex: Exception) {
                    ui {
                        bar.visibility = View.GONE
                        status.text = "payload 解析失败：${ex.message}"
                    }
                }
            }
        } else {
            extractEntryFile(e)
        }
    }

    private fun extractEntryFile(e: RemoteZip.Entry) {
        val out = File(File(getExternalFilesDir(null), "extracted"), e.name.substringAfterLast('/'))
        beginProgress()
        thread {
            try {
                val z = zip ?: throw IllegalStateException("请先解析 ZIP")
                Extractor.extractEntry(z, e, out) { ph, c, t -> report(ph, c, t) }
                succeed(out)
            } catch (ex: Exception) { failed(ex) }
        }
    }

    private fun extractPartition(
        e: RemoteZip.Entry, mf: PayloadReader.Manifest, p: PayloadReader.Partition
    ) {
        val out = File(File(getExternalFilesDir(null), "extracted"), "${p.name}.img")
        beginProgress()
        thread {
            try {
                val z = zip ?: throw IllegalStateException("请先解析 ZIP")
                Extractor.extractPartition(z, e, mf, p, out) { ph, c, t -> report(ph, c, t) }
                succeed(out)
            } catch (ex: Exception) { failed(ex) }
        }
    }

    private fun beginProgress() {
        ui {
            bar.isIndeterminate = true
            bar.visibility = View.VISIBLE
            status.text = "准备中…"
        }
    }

    private fun report(ph: String, c: Long, t: Long) = ui {
        if (t > 0) {
            bar.isIndeterminate = false
            bar.progress = ((c * 100) / t).toInt().coerceIn(0, 100)
            status.text = "$ph  ${mb(c)} / ${mb(t)}"
        } else status.text = ph
    }

    private fun succeed(out: File) = ui {
        bar.visibility = View.GONE
        status.text = "✅ 已保存：${out.absolutePath}"
        AlertDialog.Builder(this)
            .setTitle("提取完成")
            .setMessage("${out.name}\n${mb(out.length())}\n\n${out.absolutePath}")
            .setPositiveButton("分享") { _, _ -> share(out) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun failed(ex: Exception) = ui {
        bar.visibility = View.GONE
        status.text = "❌ ${ex.message}"
    }

    private fun share(f: File) {
        val uri = Uri.parse("content://$packageName.share/${f.name}")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享提取的文件"))
    }
}
