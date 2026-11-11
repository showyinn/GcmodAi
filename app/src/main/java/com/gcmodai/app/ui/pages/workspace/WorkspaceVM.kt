package com.gcmodai.app.ui.pages.workspace

import androidx.lifecycle.ViewModel
import com.gcmodai.app.AppScope
import com.gcmodai.app.data.workspace.FileContent
import com.gcmodai.app.data.workspace.WorkspaceEntry
import com.gcmodai.app.data.workspace.WorkspaceQuota
import com.gcmodai.app.data.workspace.WorkspaceRepository
import com.gcmodai.app.data.workspace.WorkspaceResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceUiState(
    val loading: Boolean = true,
    val quota: WorkspaceQuota? = null,
    val entries: List<WorkspaceEntry> = emptyList(),
    val currentDir: String = "",
    /** 正在编辑的文件；null 表示在浏览目录 */
    val editing: FileContent? = null,
    val editDraft: String = "",
    val saving: Boolean = false,
    val deploying: Boolean = false,
    val deployedUrl: String? = null,
    val error: String? = null,
    val errorDetail: String? = null,
    val toast: String? = null,
) {
    val isEmpty: Boolean get() = !loading && entries.isEmpty() && editing == null
    val inEditor: Boolean get() = editing != null
    val hasIndexHtml: Boolean
        get() = entries.any { !it.isDir && it.name.equals("index.html", ignoreCase = true) }
}

/** 待上传到工作区的文件：文件名 + 原始字节（不做任何编码转换）。 */
data class WorkspaceUpload(val name: String, val bytes: ByteArray)

class MyWorkspaceVM(
    private val repository: WorkspaceRepository,
    private val scope: AppScope,
) : ViewModel() {    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            val dir = _state.value.currentDir
            _state.update { it.copy(loading = true, error = null, errorDetail = null) }
            val q = repository.quota()
            val t = repository.tree(dir)
            _state.update { st ->
                when {
                    q is WorkspaceResult.Err -> st.copy(
                        loading = false,
                        error = q.message,
                        errorDetail = q.detail,
                    )

                    t is WorkspaceResult.Err -> st.copy(
                        loading = false,
                        error = t.message,
                        errorDetail = t.detail,
                    )

                    else -> st.copy(
                        loading = false,
                        quota = (q as WorkspaceResult.Ok).value,
                        entries = (t as WorkspaceResult.Ok).value,
                        error = null,
                        errorDetail = null,
                    )
                }
            }
        }
    }

    fun enterDir(path: String) {
        _state.update { it.copy(currentDir = path) }
        refresh()
    }

    /** 返回上一级；根目录时不做任何事。 */
    fun goUp() {
        val cur = _state.value.currentDir
        if (cur.isBlank()) return
        val parent = cur.substringBeforeLast('/', "")
        enterDir(parent)
    }

    fun openFile(path: String) {
        scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = repository.read(path)) {
                is WorkspaceResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        editing = r.value,
                        editDraft = r.value.content,
                    )
                }

                is WorkspaceResult.Err -> _state.update {
                    it.copy(loading = false, error = r.message, errorDetail = r.detail)
                }
            }
        }
    }

    fun closeEditor() {
        _state.update { it.copy(editing = null, editDraft = "") }
    }

    fun updateDraft(text: String) {
        _state.update { it.copy(editDraft = text) }
    }

    fun save() {
        val st = _state.value
        val file = st.editing ?: return
        if (st.saving) return
        scope.launch {
            _state.update { it.copy(saving = true, error = null) }
            when (val r = repository.write(file.path, st.editDraft)) {
                is WorkspaceResult.Ok -> _state.update {
                    it.copy(
                        saving = false,
                        editing = file.copy(content = st.editDraft),
                        toast = if (r.value.blocked) "已保存，但被安全扫描标记" else "已保存",
                    )
                }

                is WorkspaceResult.Err -> _state.update {
                    it.copy(saving = false, error = r.message, errorDetail = r.detail)
                }
            }
            refresh()
        }
    }

    /** 新建文件（AI 建站后用户手动补文件也走这里）。 */
    fun createFile(name: String, content: String = "") {
        val dir = _state.value.currentDir
        val path = if (dir.isBlank()) name else "$dir/$name"
        scope.launch {
            when (val r = repository.write(path, content)) {
                is WorkspaceResult.Ok -> {
                    _state.update { it.copy(toast = "已创建 $name") }
                    refresh()
                }

                is WorkspaceResult.Err -> _state.update {
                    it.copy(error = r.message, errorDetail = r.detail)
                }
            }
        }
    }

    fun delete(path: String) {
        scope.launch {
            when (val r = repository.delete(path)) {
                is WorkspaceResult.Ok -> {
                    _state.update { it.copy(toast = "已删除") }
                    refresh()
                }

                is WorkspaceResult.Err -> _state.update {
                    it.copy(error = r.message, errorDetail = r.detail)
                }
            }
        }
    }

    fun deploy() {
        val st = _state.value
        if (st.deploying) return
        scope.launch {
            _state.update { it.copy(deploying = true, error = null) }
            when (val r = repository.deploy(st.currentDir)) {
                is WorkspaceResult.Ok -> _state.update {
                    it.copy(deploying = false, deployedUrl = r.value)
                }

                is WorkspaceResult.Err -> _state.update {
                    it.copy(deploying = false, error = r.message, errorDetail = r.detail)
                }
            }
        }
    }

    fun clearDeployed() {
        _state.update { it.copy(deployedUrl = null) }
    }

    /**
     * 把本机文件上传进当前目录。
     *
     * 走 [WorkspaceRepository.writeBytes]（原始字节），所以 apk / zip / 7z / so
     * 不会被 UTF-8 转换破坏——这是"工作区能上传二进制"的关键。
     */
    fun uploadFiles(files: List<WorkspaceUpload>) {
        if (files.isEmpty()) return
        val dir = _state.value.currentDir
        scope.launch {
            _state.update { it.copy(saving = true) }
            var ok = 0
            val failures = ArrayList<String>()
            for (f in files) {
                val path = if (dir.isBlank()) f.name else "$dir/${f.name}"
                when (val r = repository.writeBytes(path, f.bytes)) {
                    is WorkspaceResult.Ok -> ok++
                    is WorkspaceResult.Err -> failures.add("${f.name}：${r.message}")
                }
            }
            _state.update { it.copy(saving = false) }
            val msg = buildString {
                if (ok > 0) append("已上传 $ok 个文件")
                if (failures.isNotEmpty()) {
                    if (ok > 0) append("；")
                    append(failures.take(2).joinToString("；"))
                    if (failures.size > 2) append(" 等 ${failures.size} 个失败")
                }
            }
            _state.update { it.copy(toast = msg.ifBlank { "没有可上传的文件" }) }
            refresh()
        }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null, errorDetail = null) }
    }
}
