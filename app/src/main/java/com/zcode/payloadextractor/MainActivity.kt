package com.zcode.payloadextractor

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.zcode.payloadextractor.core.Extractor
import com.zcode.payloadextractor.core.OperationCancelledException
import com.zcode.payloadextractor.core.OutputImage
import com.zcode.payloadextractor.core.PayloadReader
import com.zcode.payloadextractor.core.RemoteZip
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private enum class Mode { NONE, ENTRIES, PARTITIONS }

    private class ItemState {
        var active = false
        var done = false
        var failed = false
        var msg = ""
        var pct = -1
        var cur = 0L
        var total = 0L
        var speed = 0.0
        var lastCur = 0L
        var lastTm = 0L
    }

    private class Row(
        val key: String, val name: String, val icon: String, val iconBg: Int,
        val meta: String, val sizeBytes: Long
    )

    private lateinit var urlEdit: EditText
    private lateinit var parseBtn: Button
    private lateinit var saveDirRow: TextView
    private lateinit var infoCard: View
    private lateinit var infoSize: TextView
    private lateinit var infoEntries: TextView
    private lateinit var deviceCol: View
    private lateinit var infoDevice: TextView
    private lateinit var infoFilename: TextView
    private lateinit var listHeader: View
    private lateinit var backBtn: TextView
    private lateinit var extractAllBtn: TextView
    private lateinit var searchRow: View
    private lateinit var searchEdit: EditText
    private lateinit var imgOnly: CheckBox
    private lateinit var list: ListView
    private lateinit var statusText: TextView

    private var zip: RemoteZip? = null
    private var entries: List<RemoteZip.Entry> = emptyList()
    private var mf: PayloadReader.Manifest? = null
    private var payloadEntry: RemoteZip.Entry? = null
    private var currentUrl = ""
    private var mode = Mode.NONE

    private val states = ConcurrentHashMap<String, ItemState>()
    private var rows: List<Row> = emptyList()
    private var lastNotify = 0L

    @Volatile private var busy = false
    private val cancelFlag = AtomicBoolean(false)

    private var treeUri: Uri? = null
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private companion object {
        const val REQ_TREE = 101
        const val REQ_LEGACY_WRITE = 102
    }

    /**
     * 输出目标：三种模式
     * 1) 默认：先提取到应用缓存（可随机访问）→ 校验通过后复制到公共 Download (API 29+)
     * 2) 用户用 SAF 选择的目录：直接写入
     * 3) API 26-28：有存储权限时直接写公共 Download
     */
    private inner class OutputTarget(val name: String) {
        var sink: OutputImage? = null
        var tempFile: File? = null     // Download 模式的提取缓存
        var docUri: Uri? = null        // SAF 模式
        var directFile: File? = null   // 旧版直接写 Download
        var finalUri: Uri? = null      // 发布后的 MediaStore uri

        fun verifyStream(): InputStream? = when {
            docUri != null -> contentResolver.openInputStream(docUri!!)
            tempFile != null -> FileInputStream(tempFile)
            directFile != null -> FileInputStream(directFile)
            else -> null
        }

        fun ref(): OutRef = when {
            finalUri != null -> OutRef(name, null, finalUri)
            docUri != null -> OutRef(name, null, docUri)
            directFile != null -> OutRef(name, directFile, null)
            else -> OutRef(name, tempFile, null)
        }

        /** 校验通过后：把缓存文件复制到公共 Download（覆盖同名）。 */
        fun publish() {
            val tmp = tempFile ?: return
            runCatching {
                contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(name, Environment.DIRECTORY_DOWNLOADS + "/"), null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val u = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)
                        )
                        contentResolver.delete(u, null, null)
                    }
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法写入 Download 目录")
            contentResolver.openOutputStream(uri)?.use { os ->
                FileInputStream(tmp).use { it.copyTo(os, 1 shl 20) }
            } ?: throw IOException("无法写入 Download 目录")
            finalUri = uri
        }

        fun discard() {
            runCatching { sink?.close() }
            tempFile?.delete()
            docUri?.let { runCatching { contentResolver.delete(it, null, null) } }
            directFile?.delete()
        }
    }

    /** 提取结果的引用：本地文件或 SAF 文档。 */
    private inner class OutRef(val name: String, val file: File?, val uri: Uri?) {
        val location: String
            get() = uri?.toString() ?: file?.absolutePath ?: ""
        fun delete() {
            try { file?.delete() } catch (_: Exception) {}
            try { uri?.let { contentResolver.delete(it, null, null) } } catch (_: Exception) {}
        }
    }

    private lateinit var adapter: RowAdapter

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(f: () -> Unit) = runOnUiThread(f)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun mb(v: Long): String = when {
        v >= 1073741824L -> "%.2f GB".format(v / 1073741824.0)
        v >= 1048576L -> "%.1f MB".format(v / 1048576.0)
        v >= 1024L -> "%.1f KB".format(v / 1024.0)
        else -> "$v B"
    }

    private fun outFile(name: String) =
        File(File(getExternalFilesDir(null), "extracted"), name)

    private val urlFileName: String
        get() = currentUrl.substringBefore('?').substringAfterLast('/').ifEmpty { "—" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlEdit = findViewById(R.id.urlEdit)
        parseBtn = findViewById(R.id.parseBtn)
        saveDirRow = findViewById(R.id.saveDirRow)
        infoCard = findViewById(R.id.infoCard)
        infoSize = findViewById(R.id.infoSize)
        infoEntries = findViewById(R.id.infoEntries)
        deviceCol = findViewById(R.id.deviceCol)
        infoDevice = findViewById(R.id.infoDevice)
        infoFilename = findViewById(R.id.infoFilename)
        listHeader = findViewById(R.id.listHeader)
        backBtn = findViewById(R.id.backBtn)
        extractAllBtn = findViewById(R.id.extractAllBtn)
        searchRow = findViewById(R.id.searchRow)
        searchEdit = findViewById(R.id.searchEdit)
        imgOnly = findViewById(R.id.imgOnly)
        list = findViewById(R.id.list)
        statusText = findViewById(R.id.statusText)
        list.emptyView = findViewById(R.id.emptyText)

        adapter = RowAdapter { rows }
        list.adapter = adapter

        try {
            val cm = getSystemService(ClipboardManager::class.java)
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (t != null && (t.startsWith("http://") || t.startsWith("https://")) && t.contains(".zip")) {
                urlEdit.setText(t)
            }
        } catch (_: Exception) {}

        parseBtn.setOnClickListener { startParse() }
        backBtn.setOnClickListener {
            mode = Mode.ENTRIES
            backBtn.visibility = View.GONE
            refreshRows()
        }
        extractAllBtn.setOnClickListener { confirmExtractAll() }
        imgOnly.setOnCheckedChangeListener { _, _ -> refreshRows() }
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refreshRows() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, pos, _ -> onRowClick(rows[pos]) }

        // 保存位置：默认公共 Download；SAF 目录为可选覆盖，长按恢复默认
        try { treeUri = prefs.getString("tree", null)?.let(Uri::parse) } catch (_: Exception) {}
        updateSaveDirLabel()
        saveDirRow.setOnClickListener { pickSaveDir() }
        saveDirRow.setOnLongClickListener {
            treeUri = null
            prefs.edit().remove("tree").apply()
            updateSaveDirLabel()
            toast("已恢复 Download 默认保存位置")
            true
        }
        // 旧系统直接写公共 Download 需要存储权限
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_LEGACY_WRITE)
        }
    }

    private fun updateSaveDirLabel() {
        val where = if (treeUri == null) "Download（默认）"
        else try {
            DocumentsContract.getTreeDocumentId(treeUri!!).substringAfter(':').ifEmpty { "已选择的目录" }
        } catch (_: Exception) { "已选择的目录" }
        saveDirRow.text = "保存位置：$where\n点击选择其他文件夹，长按恢复 Download 默认"
    }

    private fun pickSaveDir() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
        } catch (e: Exception) {
            toast("无法打开目录选择器: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            treeUri = u
            prefs.edit().putString("tree", u.toString()).apply()
            updateSaveDirLabel()
        }
    }

    /** 在用户选择的目录中找到并删除同名文档后新建，返回文档 uri。 */
    private fun safFindOrCreate(name: String): Uri? {
        val tree = treeUri ?: return null
        return try {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) {
                        runCatching {
                            contentResolver.delete(
                                DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                                null, null
                            )
                        }
                        break
                    }
                }
            }
            DocumentsContract.createDocument(
                contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                "application/octet-stream", name
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 打开输出目标（按保存位置模式）。 */
    private fun openOutput(name: String): OutputTarget? {
        val t = OutputTarget(name)
        if (treeUri != null) {
            val doc = safFindOrCreate(name) ?: return null
            t.docUri = doc
            try {
                val pfd = contentResolver.openFileDescriptor(doc, "rw") ?: return null
                val fos = FileOutputStream(pfd.fileDescriptor)
                t.sink = OutputImage.of(fos.channel) { runCatching { pfd.close() } }
            } catch (e: Exception) {
                runCatching { contentResolver.delete(doc, null, null) }
                return null
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            // 先提取到应用缓存（可随机访问 + 校验），通过后复制进公共 Download
            val tmp = File(File(getExternalFilesDir(null), "tmp"), name)
            tmp.parentFile?.mkdirs()
            tmp.delete()
            t.tempFile = tmp
            t.sink = OutputImage.of(tmp)
        } else if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val f = File(dir, name)
            f.delete()
            t.directFile = f
            t.sink = OutputImage.of(f)
        } else {
            // 无权限的旧设备：退回应用目录
            val f = outFile(name)
            f.parentFile?.mkdirs()
            f.delete()
            t.tempFile = f
            t.sink = OutputImage.of(f)
            toast("未授予存储权限，本次保存到应用目录")
        }
        return t
    }

    /** 完成后校验：payload 分区比对 manifest 中的 SHA-256；zip 条目比对中央目录 CRC32。 */
    private fun verifyOutput(key: String, target: OutputTarget): Boolean? {
        return try {
            val s = target.verifyStream() ?: return null
            s.use { ins ->
                if (key.startsWith("part:")) {
                    val p = mf?.partitions?.firstOrNull { it.name == key.removePrefix("part:") } ?: return null
                    val h = p.imageHash ?: return true // manifest 未提供哈希，跳过
                    val md = MessageDigest.getInstance("SHA-256")
                    val buf = ByteArray(1 shl 20)
                    while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                    md.digest().contentEquals(h)
                } else {
                    val e = entries.firstOrNull { it.name == key.removePrefix("entry:") } ?: return null
                    if (e.usize == 0L) return true
                    val crc = CRC32()
                    val buf = ByteArray(1 shl 20)
                    while (true) { val n = ins.read(buf); if (n < 0) break; crc.update(buf, 0, n) }
                    crc.value == e.crc
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- 解析 ----------------

    private fun startParse() {
        if (busy) return
        val url = urlEdit.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            statusText.text = "请输入有效的 http/https 链接"
            return
        }
        busy = true
        cancelFlag.set(false)
        parseBtn.isEnabled = false
        parseBtn.text = "解析中…"
        statusText.text = getString(R.string.parsing)
        thread {
            try {
                val z = RemoteZip(url)
                z.open()
                zip = z
                entries = z.listEntries()
                var device = ""
                entries.firstOrNull { it.name == "META-INF/com/android/metadata" }?.let { me ->
                    try {
                        val txt = String(z.readEntryBytes(me, 0, me.usize.toInt().coerceAtMost(65536)) {
                            if (cancelFlag.get()) throw OperationCancelledException()
                        })
                        for (line in txt.lines()) {
                            val i = line.indexOf('=')
                            if (i > 0 && line.substring(0, i) == "pre-device") device = line.substring(i + 1)
                        }
                    } catch (_: Exception) {}
                }
                currentUrl = url
                ui {
                    infoCard.visibility = View.VISIBLE
                    listHeader.visibility = View.VISIBLE
                    searchRow.visibility = View.VISIBLE
                    backBtn.visibility = View.GONE
                    infoSize.text = mb(z.totalSize)
                    infoEntries.text = "${entries.size}"
                    infoDevice.text = device.ifEmpty { "—" }
                    deviceCol.visibility = if (device.isEmpty()) View.INVISIBLE else View.VISIBLE
                    infoFilename.text = urlFileName
                    mode = Mode.ENTRIES
                    refreshRows()
                    statusText.text = getString(R.string.save_path_hint)
                    // 含 payload.bin 时自动解析并进入分区视图
                    if (entries.any { it.name.endsWith("payload.bin") }) parsePayload()
                }
            } catch (e: Exception) {
                ui { statusText.text = "解析失败：${e.message}" }
            } finally {
                ui {
                    busy = false
                    parseBtn.isEnabled = true
                    parseBtn.text = getString(R.string.parse)
                }
            }
        }
    }

    private fun parsePayload() {
        if (busy) return
        busy = true
        statusText.text = "正在解析 payload manifest…"
        thread {
            try {
                val z = zip!!
                val pe = entries.first { it.name.endsWith("payload.bin") }
                val m = PayloadReader.parse { off, len ->
                    z.readEntryBytes(pe, off, len) {
                        if (cancelFlag.get()) throw OperationCancelledException()
                    }
                }
                mf = m
                payloadEntry = pe
                ui {
                    mode = Mode.PARTITIONS
                    backBtn.visibility = View.VISIBLE
                    refreshRows()
                    statusText.text = "manifest 解析成功：${m.partitions.size} 个分区，block=${m.blockSize}"
                }
            } catch (e: Exception) {
                ui { statusText.text = "payload 解析失败：${e.message}" }
            } finally {
                ui { busy = false }
            }
        }
    }

    // ---------------- 列表 ----------------

    private fun refreshRows() {
        val q = searchEdit.text.toString().trim().lowercase()
        rows = when (mode) {
            Mode.ENTRIES -> entries.map { e ->
                val n = e.name
                val icon: String; val bg: Int; val extra: String
                when {
                    n.endsWith("payload.bin") -> {
                        icon = "PAY"; bg = R.drawable.bg_icon_violet; extra = "payload 包 · 点击解析分区"
                    }
                    n.endsWith(".img", true) -> { icon = "IMG"; bg = R.drawable.bg_icon_blue; extra = "" }
                    n.endsWith(".dat.br", true) || n.endsWith(".patch.dat", true) ||
                        n.endsWith(".transfer.list", true) -> {
                        icon = "DAT"; bg = R.drawable.bg_icon_gray; extra = "动态分区"
                    }
                    else -> { icon = "ZIP"; bg = R.drawable.bg_icon_gray; extra = "" }
                }
                val dir = if (n.contains('/')) " · " + n.substringBeforeLast('/') else ""
                Row("entry:" + n, n.substringAfterLast('/'), icon, bg, mb(e.usize) + dir + extra, e.usize)
            }
            Mode.PARTITIONS -> (mf?.partitions ?: emptyList()).map { p ->
                Row("part:" + p.name, p.name, "IMG", R.drawable.bg_icon_blue, mb(p.size), p.size)
            }
            Mode.NONE -> emptyList()
        }.filter { r ->
            // payload.bin 是进入分区视图的入口，任何过滤条件下都保留并置顶
            r.key.endsWith("payload.bin") ||
                ((q.isEmpty() || r.name.lowercase().contains(q) || r.key.lowercase().contains(q)) &&
                    (!imgOnly.isChecked || mode == Mode.PARTITIONS || r.name.endsWith(".img", true)))
        }.sortedBy { if (it.key.endsWith("payload.bin")) 0 else 1 }
        adapter.notifyDataSetChanged()
    }

    private inner class RowAdapter(val supply: () -> List<Row>) : BaseAdapter() {
        override fun getCount() = supply().size
        override fun getItem(position: Int) = supply()[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_entry, parent, false)
            val row = supply()[position]
            val st = states[row.key]
            val iconBox = v.findViewById<View>(R.id.iconBox)
            val iconText = v.findViewById<TextView>(R.id.iconText)
            val name = v.findViewById<TextView>(R.id.itemName)
            val meta = v.findViewById<TextView>(R.id.itemMeta)
            val bar = v.findViewById<ProgressBar>(R.id.itemBar)
            val btn = v.findViewById<Button>(R.id.itemBtn)

            iconBox.setBackgroundResource(row.iconBg)
            iconText.text = row.icon
            name.text = row.name

            when {
                st != null && st.active -> {
                    meta.text = buildString {
                        append(mb(st.cur)).append(" / ").append(mb(st.total))
                        if (st.speed > 0.05) append("  ·  ").append("%.1f MB/s".format(st.speed))
                        if (st.msg.isNotEmpty()) append("  ·  ").append(st.msg)
                    }
                    meta.setTextColor(getColor(R.color.accent))
                    bar.visibility = View.VISIBLE
                    if (st.pct >= 0) { bar.isIndeterminate = false; bar.progress = st.pct }
                    else bar.isIndeterminate = true
                    btn.text = if (st.pct >= 0) "${st.pct}%" else "…"
                    btn.isEnabled = false
                }
                st != null && st.done -> {
                    meta.text = "✓ 已完成"
                    meta.setTextColor(getColor(R.color.success))
                    bar.visibility = View.GONE
                    btn.text = getString(R.string.share)
                    btn.isEnabled = true
                }
                st != null && st.failed -> {
                    meta.text = "✗ ${st.msg}"
                    meta.setTextColor(getColor(R.color.fail))
                    bar.visibility = View.GONE
                    btn.text = "重试"
                    btn.isEnabled = !busy
                }
                else -> {
                    meta.text = row.meta
                    meta.setTextColor(getColor(R.color.hint))
                    bar.visibility = View.GONE
                    btn.text = getString(R.string.extract)
                    btn.isEnabled = !busy
                }
            }

            btn.setOnClickListener { onRowClick(row) }
            return v
        }
    }

    private fun onRowClick(row: Row) {
        if (busy) { toast("有任务进行中"); return }
        if (row.key.endsWith("payload.bin")) { parsePayload(); return }
        if (row.key.startsWith("entry:")) launchSingle(row.key)
        else launchSingle(row.key)
    }

    // ---------------- 提取 ----------------

    private fun beginTask() {
        busy = true
        cancelFlag.set(false)
        parseBtn.isEnabled = false
        extractAllBtn.isEnabled = false
        adapter.notifyDataSetChanged()
    }

    private fun endTask() {
        busy = false
        cancelFlag.set(false)
        ui {
            parseBtn.isEnabled = true
            extractAllBtn.isEnabled = true
            adapter.notifyDataSetChanged()
        }
    }

    private fun checkCancel() {
        if (cancelFlag.get()) throw OperationCancelledException()
    }

    private fun updateProgress(key: String, cur: Long, total: Long) {
        val st = states[key] ?: return
        st.cur = cur
        st.total = total
        st.pct = if (total > 0) ((cur * 100) / total).toInt().coerceIn(0, 100) else -1
        val now = System.currentTimeMillis()
        if (st.lastTm > 0 && now - st.lastTm > 400) {
            val dt = (now - st.lastTm) / 1000.0
            if (dt > 0) st.speed = (cur - st.lastCur) / dt / 1048576.0
            st.lastCur = cur
            st.lastTm = now
        } else if (st.lastTm == 0L) {
            st.lastCur = cur; st.lastTm = now
        }
        val n = System.currentTimeMillis()
        if (n - lastNotify > 250) {
            lastNotify = n
            ui { adapter.notifyDataSetChanged() }
        }
    }

    /** 阻塞执行单项（校验失败自动重试一次），返回结果引用；失败/取消返回 null。 */
    private fun runItem(key: String): OutRef? {
        val st = states.getOrPut(key) { ItemState() }
        st.active = true; st.done = false; st.failed = false
        st.pct = -1; st.cur = 0; st.total = 0; st.speed = 0.0; st.lastTm = 0
        st.msg = ""
        ui { adapter.notifyDataSetChanged() }
        val name = if (key.startsWith("entry:")) key.removePrefix("entry:").substringAfterLast('/')
        else key.removePrefix("part:") + ".img"

        var attempt = 0
        while (attempt < 2) {
            attempt++
            if (attempt == 2) {
                st.msg = "校验失败，正在重新提取…"
                ui { adapter.notifyDataSetChanged() }
            }
            val target = openOutput(name)
            if (target == null || target.sink == null) {
                st.active = false; st.failed = true; st.msg = "无法创建输出文件"
                ui { adapter.notifyDataSetChanged() }
                return null
            }
            try {
                if (key.startsWith("entry:")) {
                    val e = entries.first { it.name == key.removePrefix("entry:") }
                    Extractor.extractEntry(zip!!, e, target.sink!!, { _, c, total -> updateProgress(key, c, total) }) { checkCancel() }
                } else {
                    val p = mf!!.partitions.first { it.name == key.removePrefix("part:") }
                    Extractor.extractPartition(zip!!, payloadEntry!!, mf!!, p, target.sink!!,
                        { _, c, total -> updateProgress(key, c, total) }) { checkCancel() }
                }
                runCatching { target.sink?.close() }
                st.msg = "校验中…"
                ui { adapter.notifyDataSetChanged() }
                when (verifyOutput(key, target)) {
                    true, null -> {
                        if (target.tempFile != null) {
                            st.msg = "正在保存到 Download…"
                            ui { adapter.notifyDataSetChanged() }
                            target.publish()
                            target.tempFile?.delete()
                        }
                        st.active = false; st.done = true; st.pct = 100; st.msg = "✓ 校验通过"
                        ui { adapter.notifyDataSetChanged() }
                        return target.ref()
                    }
                    false -> target.discard() // 校验失败：删除后重试
                }
            } catch (c: OperationCancelledException) {
                target.discard()
                st.active = false; st.failed = true; st.msg = "已取消"
                ui { adapter.notifyDataSetChanged() }
                return null
            } catch (e: Exception) {
                target.discard()
                st.active = false; st.failed = true; st.msg = e.message ?: "失败"
                ui { adapter.notifyDataSetChanged() }
                return null
            }
        }
        st.active = false; st.failed = true; st.msg = "校验失败（已自动重试）"
        ui { adapter.notifyDataSetChanged() }
        return null
    }

    private fun launchSingle(key: String) {
        if (busy) return
        beginTask()
        thread {
            val ref = runItem(key)
            endTask()
            if (ref != null) ui { showDoneDialog(ref) }
        }
    }

    private fun confirmExtractAll() {
        if (busy) return
        val keys: List<String>; val total: Long
        when (mode) {
            Mode.PARTITIONS -> {
                keys = mf!!.partitions.map { "part:" + it.name }
                total = mf!!.partitions.sumOf { it.size }
            }
            Mode.ENTRIES -> {
                val imgs = entries.filter { it.name.endsWith(".img", true) }
                keys = imgs.map { "entry:" + it.name }
                total = imgs.sumOf { it.usize }
            }
            else -> return
        }
        if (keys.isEmpty()) { toast("没有可提取的项"); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.extract_all))
            .setMessage("将按顺序提取 ${keys.size} 项，预计流量约 ${mb(total)}。\n过程中可随时取消。")
            .setPositiveButton("开始") { _, _ ->
                beginTask()
                thread {
                    var ok = 0; var fail = 0
                    for (k in keys) {
                        if (cancelFlag.get()) break
                        if (runItem(k) != null) ok++ else fail++
                    }
                    endTask()
                    ui { toast("提取完成：成功 $ok 项" + (if (fail > 0) "，失败/取消 $fail 项" else "")) }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showDoneDialog(ref: OutRef) {
        AlertDialog.Builder(this)
            .setTitle("提取完成（已校验）")
            .setMessage("${ref.name}\n\n${ref.location}")
            .setPositiveButton(R.string.share) { _, _ -> share(ref) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun share(ref: OutRef) {
        val uri = ref.uri ?: Uri.parse("content://$packageName.share/${ref.name}")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享提取的文件"))
    }
}
